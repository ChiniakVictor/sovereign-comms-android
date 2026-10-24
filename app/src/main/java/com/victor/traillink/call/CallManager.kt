package com.victor.traillink.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.victor.traillink.R
import com.victor.traillink.core.CallSignal
import com.victor.traillink.core.CallWire
import com.victor.traillink.data.Prefs
import com.victor.traillink.service.LinkRepo
import com.victor.traillink.service.LinkService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import java.util.concurrent.Executors

/** What the call screen shows. */
data class CallUi(
    val phase: Phase = Phase.IDLE,
    val peerId: String? = null,
    val peerName: String = "",
    val callId: String? = null,
    val incoming: Boolean = false,
    val net: CallMedia.Net = CallMedia.Net.NEW,
    val connectedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val route: String? = null,
    val detail: String? = null,
) {
    enum class Phase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }
    val busy get() = phase != Phase.IDLE && phase != Phase.ENDED
}

/** App-to-app calls: signaling over the group's encrypted MQTT link (see [CallWire]). */
object AppCallTransport : CallTransport {
    override val label = "Sovereign Comms (internet)"
    override fun handles(t: CallTarget) = t is CallTarget.AppPeer
    override fun ready() = LinkService.instance?.net?.online == true && LinkService.instance?.callNetKey != null
    override fun notReadyReason() = "Calls need Internet sharing on and online (🌐 tab), on both phones."

    private val io = Executors.newSingleThreadExecutor()
    fun send(toId: String, s: CallSignal) = io.execute {
        val svc = LinkService.instance ?: return@execute
        val net = svc.net ?: return@execute
        val key = svc.callNetKey ?: return@execute
        val ok = net.publishRaw(CallWire.sub(toId, Prefs.settings.value.deviceId), CallWire.seal(key, s), 1, false)
        if (s.type != CallSignal.ICE) Log.i("SCCall", "→ ${s.type} ${s.call} to $toId ${if (ok) "" else "(NOT sent)"}")
    }
    fun open(bytes: ByteArray): CallSignal? = LinkService.instance?.callNetKey?.let { CallWire.open(it, bytes) }
}

object CallManager {
    private const val TAG = "SCCall"
    const val CH_CALL = "calls"; const val CH_MISSED = "calls_missed"
    const val NOTIF_RING = 6100; const val NOTIF_ONGOING = 6101; const val NOTIF_MISSED = 6102
    private const val RING_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> get() = _ui
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var media: CallMedia? = null
    private val earlyIce = ArrayList<CallSignal>()
    private var timeout: Job? = null
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var ringback: Job? = null
    private var prox: PowerManager.WakeLock? = null
    private val ended = LinkedHashSet<String>()   // recently finished call ids (ignore late signals)
    private val rng = SecureRandom()

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }
    private val myId get() = Prefs.settings.value.deviceId
    private val myName get() = Prefs.settings.value.myName.ifBlank { "Partner" }
    private fun sig(type: String, sdp: String? = null, mid: String? = null, idx: Int = 0, cand: String? = null, why: String? = null) =
        CallSignal(type, _ui.value.callId!!, myId, myName, System.currentTimeMillis(), sdp, mid, idx, cand, why)
    private fun sendToPeer(s: CallSignal) { _ui.value.peerId?.let { AppCallTransport.send(it, s) } }
    private fun set(f: (CallUi) -> CallUi) { _ui.value = f(_ui.value) }

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming and ongoing Sovereign Comms calls"
            setSound(null, null)          // the app plays the ringtone itself (looping) and stops it on answer
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // ------------------------------------------------------------------ user actions
    /** Returns an error text, or null when the call is being placed. */
    fun start(ctx: Context, target: CallTarget): String? {
        init(ctx)
        if (!Prefs.settings.value.callsEnabled) return "Calls are turned off in More → Calls."
        val t = CallTransports.forTarget(target) ?: return "Calling a phone number isn't available (no phone line set up)."
        if (!t.ready()) return t.notReadyReason()
        if (_ui.value.busy) return "You're already on a call."
        if (!micOk()) return "Microphone permission needed"
        val peer = target as CallTarget.AppPeer
        val id = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        earlyIce.clear()
        _ui.value = CallUi(CallUi.Phase.OUTGOING, peer.deviceId, peer.display, id, incoming = false)
        LinkRepo.log("Call: calling ${peer.display}")
        sendToPeer(sig(CallSignal.RING))
        CallService.start(app)
        audioOn()
        startRingback()
        timeout = scope.launch { delay(RING_TIMEOUT_MS); if (_ui.value.phase == CallUi.Phase.OUTGOING) { sendToPeer(sig(CallSignal.CANCEL, why = "timeout")); end("No answer") } }
        openScreen(ctx)
        return null
    }

    fun accept(ctx: Context) {
        val u = _ui.value
        if (u.phase != CallUi.Phase.INCOMING) return
        if (!micOk()) return
        stopRinging()
        timeout?.cancel()
        set { it.copy(phase = CallUi.Phase.CONNECTING, net = CallMedia.Net.CONNECTING) }
        CallService.start(app)
        audioOn()
        if (!makeMedia()) return
        sendToPeer(sig(CallSignal.ACCEPT))
        armConnectTimeout()
        LinkRepo.log("Call: answered ${u.peerName}")
    }

    fun decline() {
        if (_ui.value.phase != CallUi.Phase.INCOMING) return
        sendToPeer(sig(CallSignal.DECLINE)); end("Declined", quiet = true)
    }

    fun hangup() {
        when (_ui.value.phase) {
            CallUi.Phase.OUTGOING -> { sendToPeer(sig(CallSignal.CANCEL)); end("Call cancelled", quiet = true) }
            CallUi.Phase.INCOMING -> decline()
            CallUi.Phase.CONNECTING, CallUi.Phase.ACTIVE -> { sendToPeer(sig(CallSignal.HANGUP)); end("Call ended") }
            else -> {}
        }
    }

    fun toggleMute() { val m = !_ui.value.muted; media?.setMuted(m); set { it.copy(muted = m) } }
    fun toggleSpeaker() { val sp = !_ui.value.speaker; set { it.copy(speaker = sp) }; route(sp) }

    // ------------------------------------------------------------------ signals from the network
    /** rel = "c/<myId>/<fromId>" */
    fun onNet(ctx: Context, rel: String, bytes: ByteArray) {
        init(ctx)
        val fromTopic = rel.split('/').getOrNull(2) ?: return
        val s = AppCallTransport.open(bytes) ?: return
        if (s.from != fromTopic || s.from == myId) return
        scope.launch { handle(s) }
    }

    private fun handle(s: CallSignal) {
        val u = _ui.value
        if (s.call in ended && s.type != CallSignal.RING) return
        if (s.type == CallSignal.RING) {
            if (s.call == u.callId || s.call in ended) return                   // duplicate
            val rk = com.victor.traillink.data.RemovedPartners
            if (!rk.active(com.victor.traillink.service.NodeStore.netNum(s.from), s.ts)) {   // blocked on this phone: decline quietly
                AppCallTransport.send(s.from, CallSignal(CallSignal.DECLINE, s.call, myId, myName, System.currentTimeMillis(), reason = "unavailable")); return
            }
            if (!Prefs.settings.value.callsEnabled) { AppCallTransport.send(s.from, CallSignal(CallSignal.DECLINE, s.call, myId, myName, System.currentTimeMillis(), reason = "calls off")); return }
            if (u.busy) { AppCallTransport.send(s.from, CallSignal(CallSignal.BUSY, s.call, myId, myName, System.currentTimeMillis())); return }
            earlyIce.clear()
            _ui.value = CallUi(CallUi.Phase.INCOMING, s.from, s.name.ifBlank { "Partner" }, s.call, incoming = true)
            LinkRepo.log("Call: incoming from ${s.name}")
            ring()
            timeout = scope.launch { delay(RING_TIMEOUT_MS); if (_ui.value.phase == CallUi.Phase.INCOMING) { missed(s.name); end("Missed call", quiet = true) } }
            return
        }
        if (s.call != u.callId || s.from != u.peerId) return
        if (s.type != CallSignal.ICE) Log.i(TAG, "← ${s.type} ${s.call}")
        when (s.type) {
            CallSignal.CANCEL -> if (u.phase == CallUi.Phase.INCOMING) { missed(u.peerName); end("Missed call", quiet = true) } else end("Call ended")
            CallSignal.DECLINE -> end("Declined")
            CallSignal.BUSY -> end("Busy — on another call")
            CallSignal.HANGUP -> end(if (u.phase == CallUi.Phase.ACTIVE) "Call ended" else "Call ended (${s.reason ?: "other side hung up"})")
            CallSignal.ACCEPT -> if (u.phase == CallUi.Phase.OUTGOING) {
                stopRingback(); timeout?.cancel()
                set { it.copy(phase = CallUi.Phase.CONNECTING, net = CallMedia.Net.CONNECTING) }
                if (makeMedia()) { media?.createOffer(); armConnectTimeout() }
            }
            CallSignal.OFFER -> if (!u.incoming) return else s.sdp?.let { media?.applyRemote("offer", it) }
            CallSignal.ANSWER -> if (u.incoming) return else s.sdp?.let { media?.applyRemote("answer", it) }
            CallSignal.ICE -> { val c = s.cand ?: return; media?.addRemoteCandidate(s.mid, s.idx, c) ?: earlyIce.add(s) }
        }
    }

    // ------------------------------------------------------------------ media
    private fun makeMedia(): Boolean {
        return try {
            val m = WebRtcMedia(app, Prefs.settings.value, object : CallMedia.Events {
                override fun onLocalDescription(type: String, sdp: String) { scope.launch { if (_ui.value.busy) sendToPeer(sig(if (type == "offer") CallSignal.OFFER else CallSignal.ANSWER, sdp = sdp)) } }
                override fun onLocalCandidate(mid: String?, idx: Int, cand: String) { scope.launch { if (_ui.value.busy) sendToPeer(sig(CallSignal.ICE, mid = mid, idx = idx, cand = cand)) } }
                override fun onNet(state: CallMedia.Net, detail: String?) { scope.launch { onMediaNet(state, detail) } }
            })
            media = m
            earlyIce.forEach { m.addRemoteCandidate(it.mid, it.idx, it.cand!!) }; earlyIce.clear()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "media", e)
            sendToPeer(sig(CallSignal.HANGUP, why = "audio failed")); end("Couldn't start audio: ${e.message}")
            false
        }
    }

    private fun onMediaNet(st: CallMedia.Net, detail: String?) {
        val u = _ui.value
        if (!u.busy) return
        when (st) {
            CallMedia.Net.CONNECTED -> {
                timeout?.cancel()
                set { it.copy(phase = CallUi.Phase.ACTIVE, net = st, connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt) }
                route(_ui.value.speaker)
                scope.launch { repeat(5) { delay(1500); media?.routeLabel()?.let { r -> set { it.copy(route = r) } } } }
                LinkRepo.log("Call: connected with ${u.peerName}")
            }
            CallMedia.Net.RECONNECTING -> set { it.copy(net = st) }
            CallMedia.Net.FAILED -> { sendToPeer(sig(CallSignal.HANGUP, why = "couldn't connect")); end(detail ?: "Couldn't connect") }
            else -> set { it.copy(net = st) }
        }
    }

    private fun armConnectTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_ui.value.phase == CallUi.Phase.CONNECTING) {
                sendToPeer(sig(CallSignal.HANGUP, why = "couldn't connect"))
                end(if (Prefs.settings.value.turnUrl.isBlank()) "Couldn't connect. Your networks may block direct calls (common on Starlink and some cell carriers). Add a relay (TURN) server in More → Calls."
                    else "Couldn't connect, even through your relay server. Check the TURN address, user and password.")
            }
        }
    }

    // ------------------------------------------------------------------ ending
    private fun end(why: String, quiet: Boolean = false) {
        val u = _ui.value
        if (!u.busy) return
        u.callId?.let { ended += it; while (ended.size > 20) ended.remove(ended.first()) }
        timeout?.cancel()
        stopRinging(); stopRingback()
        media?.close(); media = null
        earlyIce.clear()
        audioOff()
        NotificationManagerCompat.from(app).cancel(NOTIF_RING)
        CallService.stop(app)
        val dur = if (u.connectedAt > 0) (System.currentTimeMillis() - u.connectedAt) / 1000 else 0
        LinkRepo.log("Call: $why${if (dur > 0) " (${dur / 60}:%02d)".format(dur % 60) else ""}")
        if (!quiet) runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).startTone(ToneGenerator.TONE_PROP_PROMPT, 300) }
        set { it.copy(phase = CallUi.Phase.ENDED, detail = why) }
        scope.launch { delay(if (why.length > 40) 6000 else 2500); if (_ui.value.phase == CallUi.Phase.ENDED) _ui.value = CallUi() }
    }

    fun dismissEnded() { if (_ui.value.phase == CallUi.Phase.ENDED) _ui.value = CallUi() }

    // ------------------------------------------------------------------ ringing (incoming)
    private fun ring() {
        val u = _ui.value
        val fsi = PendingIntent.getActivity(app, 1, Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(app, 2, Intent(app, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(app, 3, Intent(app, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle("📞 ${u.peerName} is calling")
            .setContentText("Sovereign Comms call")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setAutoCancel(false)
            .setContentIntent(fsi).setFullScreenIntent(fsi, true)
            .addAction(0, "Decline", decline).addAction(0, "Answer", answer)
            .setTimeoutAfter(RING_TIMEOUT_MS + 5000)
            .build()
        if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            NotificationManagerCompat.from(app).notify(NOTIF_RING, n)
        // If our app is open, show the call screen right away.
        if (AppVisible.resumed > 0) openScreen(app)
        // ringtone + vibration, respecting the ringer switch
        val am = app.getSystemService(AudioManager::class.java)
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                isLooping = true; prepare(); start()
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) runCatching {
            vib()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
        // wake the screen briefly even if the full-screen permission is off
        runCatching {
            @Suppress("DEPRECATION")
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "SovereignComms:ring").acquire(10_000)
        }
    }

    @Suppress("DEPRECATION")
    private fun vib(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator else app.getSystemService(Vibrator::class.java)

    private fun stopRinging() {
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        runCatching { vib()?.cancel() }
        NotificationManagerCompat.from(app).cancel(NOTIF_RING)
    }

    private fun missed(name: String) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val peer = _ui.value.peerId
        val open = PendingIntent.getActivity(app, 4, Intent(app, com.victor.traillink.ui.MainActivity::class.java)
            .putExtra("tab", "messages").apply { peer?.let { putExtra("convo", "n:$it") } }
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(app).notify(NOTIF_MISSED, NotificationCompat.Builder(app, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_trail).setContentTitle("Missed call from $name")
            .setContentText("Tap to open your chat").setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL).build())
    }

    // ------------------------------------------------------------------ ringback (outgoing)
    private fun startRingback() {
        ringback?.cancel()
        ringback = scope.launch {
            val t = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 50) }.getOrNull().also { tone = it }
            while (isActive && _ui.value.phase == CallUi.Phase.OUTGOING) {
                runCatching { t?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 2000) }
                delay(4000)
            }
        }
    }
    private fun stopRingback() { ringback?.cancel(); ringback = null; tone?.let { runCatching { it.stopTone(); it.release() } }; tone = null }

    // ------------------------------------------------------------------ audio routing
    private fun audioOn() {
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route(_ui.value.speaker)
    }

    @SuppressLint("WakelockTimeout")
    private fun route(speaker: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val bt = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            val wired = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val want = when {
                speaker -> devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                bt != null -> bt
                wired != null -> wired
                else -> devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (want != null) am.setCommunicationDevice(want) else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        }
        // screen off when the phone is at your ear (earpiece only)
        val pm = app.getSystemService(PowerManager::class.java)
        val inCall = _ui.value.phase == CallUi.Phase.ACTIVE || _ui.value.phase == CallUi.Phase.CONNECTING || _ui.value.phase == CallUi.Phase.OUTGOING
        if (!speaker && inCall && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (prox == null) prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SovereignComms:prox").also { it.acquire() }
        } else releaseProx()
    }

    private fun releaseProx() { prox?.let { if (it.isHeld) runCatching { it.release() } }; prox = null }

    private fun audioOff() {
        releaseProx()
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }

    // ------------------------------------------------------------------ helpers
    private fun micOk() = granted(Manifest.permission.RECORD_AUDIO)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    fun openScreen(ctx: Context) {
        runCatching { ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    /** Android 14+: may this app show the call screen over the lock screen? */
    fun canRingFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + ctx.packageName))
        else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
}

/** Counts resumed activities, so an incoming call opens the call screen directly when the app is on screen. */
object AppVisible { @Volatile var resumed = 0 }

class CallActionReceiver : android.content.BroadcastReceiver() {
    companion object { const val DECLINE = "com.victor.traillink.call.DECLINE"; const val HANGUP = "com.victor.traillink.call.HANGUP" }
    override fun onReceive(c: Context, i: Intent) {
        CallManager.init(c)
        when (i.action) { DECLINE -> CallManager.decline(); HANGUP -> CallManager.hangup() }
    }
}
