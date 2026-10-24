package com.victor.traillink.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.victor.traillink.R
import com.victor.traillink.ble.MeshBle
import com.victor.traillink.core.Geo
import com.victor.traillink.core.MeshCodec
import com.victor.traillink.core.Psk
import com.victor.traillink.data.NodeEntity
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.TrailDb
import com.victor.traillink.ui.MainActivity
import kotlinx.coroutines.*
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.Portnums.PortNum
import kotlin.random.Random

class LinkService : LifecycleService(), MeshBle.Listener, LinkRepo.Controller {

    companion object {
        const val ACTION_CONNECT = "connect"; const val ACTION_DISCONNECT = "disconnect"
        @Volatile var sharingActive = false
        @Volatile var sharingSince = 0L
        const val ACTION_NET_REFRESH = "net_refresh"; const val ACTION_NET_STOP_FORGET = "net_stop_forget"
        const val EXTRA_ADDRESS = "address"; const val EXTRA_NAME = "name"
        const val CH_LINK = "link"; const val CH_ALARM = "lost_contact"; const val CH_MSG = "messages"; const val CH_SOS = "sos"
        const val ACTION_SEND_MSG = "send_msg"; const val ACTION_MARK_READ = "mark_read"

        fun sendMessage(ctx: Context, convo: String, text: String, sos: Boolean) {
            instance?.let { svc -> if (svc.net != null || svc.wantConnected != null) { svc.lifecycleScope.launch(Dispatchers.IO) { svc.messenger.send(convo, text, sos) }; return } }
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_SEND_MSG)
                .putExtra("convo", convo).putExtra("text", text).putExtra("sos", sos))
        }
        @Volatile var instance: LinkService? = null
        /** UI saved/edited/deleted one of my pins: share it if the service is running (else on next online). */
        fun publishPin(id: String) { instance?.let { svc -> svc.lifecycleScope.launch(Dispatchers.IO) { svc.pins.publish(id) } } }
        fun markRead(ctx: Context, convo: String) {
            instance?.let { svc -> svc.lifecycleScope.launch(Dispatchers.IO) { svc.messenger.markRead(convo) }; return }
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_MARK_READ).putExtra("convo", convo))
        }
        const val NOTIF_ID = 7

        fun send(ctx: Context, action: String, address: String? = null, name: String? = null) {
            val i = Intent(ctx, LinkService::class.java).setAction(action)
            address?.let { i.putExtra(EXTRA_ADDRESS, it) }; name?.let { i.putExtra(EXTRA_NAME, it) }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            com.victor.traillink.call.CallManager.createChannels(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LINK, "Sovereign Comms link status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows partners' last positions while Sovereign Comms is connected"
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Lost contact alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a partner hasn't been heard for the set time"
                enableVibration(true); vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            })
            nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Group and direct messages from partners"
            })
            nm.createNotificationChannel(NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A partner pressed SOS (Need help)"
                enableVibration(true); vibrationPattern = longArrayOf(0, 1000, 200, 1000, 200, 1000, 200, 1000)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setBypassDnd(true)
            })
        }
    }

    private val dao by lazy { TrailDb.get(this).dao() }
    private val store by lazy { NodeStore(dao) }
    private var ble: MeshBle? = null
    private var connectJob: Job? = null
    internal var wantConnected: String? = null
    private var configId = 0
    private val alarmed = mutableSetOf<Long>()
    private var wake: PowerManager.WakeLock? = null
    internal var net: NetLink? = null
    val messenger by lazy {
        Messenger(this, lifecycleScope, messengerHost)
    }
    private val messengerHost by lazy {
        object : Messenger.Host {
            override val net get() = this@LinkService.net
            override val netKey get() = this@LinkService.netKey
            override val radioConnected get() = LinkRepo.state.value.conn == Conn.CONNECTED
            override val myRadioNum get() = LinkRepo.state.value.radio.myNodeNum
            override suspend fun sendToRadio(bytes: ByteArray): Boolean =
                runCatching { ble?.send(bytes) ?: return false; true }.getOrDefault(false)
        }
    }
    private var netKey: ByteArray? = null
    /** Group key for call signaling (only while internet sharing is on). */
    internal val callNetKey: ByteArray? get() = if (net != null) netKey else null
    private var netSig: String? = null
    private val guard = com.victor.traillink.core.ReplayGuard()
    private var lastPubFix: android.location.Location? = null
    private var lastPubAt = 0L
    private var lastLoc: android.location.Location? = null
    /** 2.5: 45 s out-of-service track queue (filesDir/backfill), sent as a backfill when the link is back. */
    private val outage by lazy { com.victor.traillink.core.OutageQueue(java.io.File(filesDir, "backfill")) }
    @Volatile private var liveSentAt = 0L          // last live position handed to MQTT and not yet confirmed (0 = none)
    @Volatile private var backfillBusy = false
    @Volatile private var backfillTriedAt = 0L
    private var locator: PhoneLocator? = null
    val pins: PinHub by lazy { PinHub(this, lifecycleScope, messengerHost) }
    val voice: VoiceHub by lazy { VoiceHub(this, lifecycleScope, { net }, { netKey }) }
    private val locReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { onLocationSwitch() }
    }
    @Volatile private var adminResponse: CompletableDeferred<AdminProtos.AdminMessage>? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        createChannels(this)
        instance = this
        LinkRepo.controller = this
        LinkRepo.update { it.copy(serviceRunning = true) }
        goForeground()
        lifecycleScope.launch { while (isActive) { checkLost(); updateNotification(); updateLocationRequests(); delay(15_000) } }
        lifecycleScope.launch(Dispatchers.IO) { dao.prune(System.currentTimeMillis() - 30L * 86_400_000) }
        LinkRepo.update { it.copy(locationOn = PhoneLocator.systemEnabled(this@LinkService)) }
        ContextCompat.registerReceiver(this, locReceiver, android.content.IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { Prefs.settings.collect { refreshNet() } }
        lifecycleScope.launch { while (isActive) { delay(10_000); maybePublish(force = false); checkService() } }
        lifecycleScope.launch(Dispatchers.IO) { outage.size() }   // load the 2.5 backfill queue off the main thread
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let { connect(it, intent.getStringExtra(EXTRA_NAME)) }
            ACTION_DISCONNECT -> { disconnect(); stopIfIdle() }
            ACTION_NET_REFRESH -> { refreshNet(); stopIfIdle() }
            ACTION_SEND_MSG -> {
                val c = intent.getStringExtra("convo"); val t = intent.getStringExtra("text"); val sos = intent.getBooleanExtra("sos", false)
                refreshNet()
                lifecycleScope.launch {
                    if (c != null && t != null) withContext(Dispatchers.IO) { messenger.send(c, t, sos) }
                    stopIfIdle()
                }
            }
            ACTION_MARK_READ -> lifecycleScope.launch {
                intent.getStringExtra("convo")?.let { c -> withContext(Dispatchers.IO) { messenger.markRead(c) } }
                stopIfIdle()
            }
            ACTION_NET_STOP_FORGET -> { stopNet(clearRetained = true); Prefs.update(this) { it.copy(netSharing = false, netKeyHex = null, netGroup = null) }; stopIfIdle() }
            null -> { // restarted by the system: resume last connection / internet sharing
                refreshNet()
                Prefs.settings.value.lastDevice?.takeIf { Prefs.settings.value.autoReconnect && Prefs.settings.value.radioAllowed }?.let { connect(it, Prefs.settings.value.lastDeviceName) }
                    ?: stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        disconnect()
        stopNet(clearRetained = false)
        locator?.stop(); locator = null
        runCatching { unregisterReceiver(locReceiver) }
        LinkRepo.controller = null
        LinkRepo.update { it.copy(serviceRunning = false, conn = Conn.IDLE) }
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (wantConnected == null && net == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val bt = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
            if (bt) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC // before permissions are granted
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…", null), type)
        } catch (e: Exception) {
            LinkRepo.log("foreground start failed: $e")
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- BLE connection
    private fun connect(address: String, name: String?) {
        wantConnected = address
        Prefs.update(this) { it.copy(lastDevice = address, lastDeviceName = name ?: it.lastDeviceName) }
        LinkRepo.radio { RadioInfo(address = address, name = name) }
        connectJob?.cancel()
        ble?.close()
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrailLink:link")
        connectJob = lifecycleScope.launch {
            var backoff = 3_000L
            while (isActive && wantConnected == address) {
                val b = MeshBle(this@LinkService, lifecycleScope, this@LinkService)
                ble = b
                try {
                    LinkRepo.log("Connecting to ${name ?: address}")
                    b.connect(address)
                    backoff = 3_000L
                    // stay here until the link drops
                    while (isActive && !b.closed && LinkRepo.state.value.conn != Conn.RECONNECTING) delay(1000)
                } catch (e: CancellationException) {
                    b.close(); throw e
                } catch (e: Exception) {
                    LinkRepo.log("Connect failed: ${e.message}")
                    LinkRepo.update { it.copy(conn = Conn.RECONNECTING, error = e.message) }
                }
                b.close()
                if (wantConnected != address) break
                LinkRepo.update { it.copy(conn = Conn.RECONNECTING) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun disconnect() {
        wantConnected = null
        connectJob?.cancel(); connectJob = null
        ble?.let { b -> runCatching { runBlocking { withTimeoutOrNull(800) { b.send(MeshCodec.disconnect()) } } } ; b.close() }
        ble = null
        wake?.let { if (it.isHeld) it.release() }
        LinkRepo.update { it.copy(conn = Conn.IDLE, error = null) }
    }

    override fun onState(state: MeshBle.State, detail: String?) {
        LinkRepo.update {
            it.copy(conn = when (state) {
                MeshBle.State.BONDING -> Conn.BONDING
                MeshBle.State.CONNECTING -> Conn.CONNECTING
                MeshBle.State.CONNECTED -> Conn.SYNCING
                MeshBle.State.DISCONNECTED -> if (wantConnected != null) Conn.RECONNECTING else Conn.IDLE
            })
        }
        if (state == MeshBle.State.DISCONNECTED) LinkRepo.log("Link dropped ($detail)")
        updateNotification()
    }

    override fun onReady() {
        configId = Random.nextInt(1, Int.MAX_VALUE)
        lifecycleScope.launch {
            try { ble?.send(MeshCodec.wantConfig(configId)) } catch (e: Exception) { LinkRepo.log("want_config failed: $e") }
        }
    }

    override fun onFromRadio(bytes: ByteArray) {
        val fr = MeshCodec.parseFromRadio(bytes)
        val my = LinkRepo.state.value.radio.myNodeNum
        when (fr.payloadVariantCase) {
            FromRadio.PayloadVariantCase.MY_INFO -> {
                val num = store.u32(fr.myInfo.myNodeNum)
                LinkRepo.radio { it.copy(myNodeNum = num) }
                lifecycleScope.launch(Dispatchers.IO) { dao.clearSelfExcept(num) }
            }
            FromRadio.PayloadVariantCase.NODE_INFO -> {
                val n = fr.nodeInfo
                if (store.u32(n.num) == my) LinkRepo.radio { it.copy(longName = n.user.longName, shortName = n.user.shortName, hwModel = n.user.hwModel.name) }
                runBlocking(Dispatchers.IO) { store.onNodeInfo(n, my) }
            }
            FromRadio.PayloadVariantCase.CONFIG -> {
                val c = fr.config
                LinkRepo.radio { it.copy(configs = it.configs + (c.payloadVariantCase.number to c)) }
            }
            FromRadio.PayloadVariantCase.CHANNEL -> LinkRepo.radio { it.copy(channels = it.channels + (fr.channel.index to fr.channel)) }
            FromRadio.PayloadVariantCase.METADATA -> LinkRepo.radio { it.copy(firmware = fr.metadata.firmwareVersion) }
            FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> if (fr.configCompleteId == configId) {
                LinkRepo.update { it.copy(conn = Conn.CONNECTED, error = null) }
                wake?.let { if (!it.isHeld) it.acquire() }
                val r = LinkRepo.state.value.radio
                LinkRepo.log("Synced: ${r.longName ?: "?"} fw ${r.firmware ?: "?"} ${r.lora?.region ?: ""} ${r.lora?.modemPreset ?: ""} ch '${r.primary?.settings?.name ?: ""}'")
                updateNotification()
            }
            FromRadio.PayloadVariantCase.PACKET -> {
                val p = fr.packet
                if (p.hasDecoded() && p.decoded.portnum == PortNum.ADMIN_APP) {
                    runCatching { AdminProtos.AdminMessage.parseFrom(p.decoded.payload) }.getOrNull()?.let { a ->
                        if (!a.sessionPasskey.isEmpty) LinkRepo.sessionPasskey = a.sessionPasskey
                        adminResponse?.complete(a)
                    }
                    return
                }
                if (p.hasDecoded() && p.decoded.portnum == PortNum.TEXT_MESSAGE_APP) { lifecycleScope.launch(Dispatchers.IO) { messenger.onLoraText(p) } }
                if (p.hasDecoded() && p.decoded.portnum == PortNum.WAYPOINT_APP) { lifecycleScope.launch(Dispatchers.IO) { pins.onLora(p) } }
                if (p.hasDecoded() && p.decoded.portnum == PortNum.ROUTING_APP) { lifecycleScope.launch(Dispatchers.IO) { messenger.onRouting(p) } }
                val desc = runBlocking(Dispatchers.IO) { store.onPacket(p, my) }
                if (desc != null) LinkRepo.log(desc)
                if (p.hasDecoded() && p.decoded.portnum == PortNum.POSITION_APP) forwardToNet(store.u32(p.from))
                if (p.hasDecoded() && p.decoded.portnum == PortNum.POSITION_APP) updateNotification()
            }
            FromRadio.PayloadVariantCase.REBOOTED -> LinkRepo.log("Radio rebooted")
            else -> {}
        }
    }

    // ---------------------------------------------------------------- radio setup wizard
    override fun applySetup(plan: MeshCodec.SetupPlan) {
        lifecycleScope.launch {
            val b = ble
            val st = LinkRepo.state.value
            if (b == null || st.conn != Conn.CONNECTED) {
                LinkRepo.setup { SetupProgress(error = "Connect to the radio first") }; return@launch
            }
            LinkRepo.setup { SetupProgress(running = true, steps = listOf("Deriving key from passcode…")) }
            try {
                val chName = Psk.channelName(plan.passcode)
                val psk = withContext(Dispatchers.Default) { Psk.derive(plan.passcode, chName) }
                step("Key ready (fingerprint ${Psk.fingerprint(psk)}), channel $chName")
                // Ask for a session passkey (needed by newer firmware for admin writes).
                adminResponse = CompletableDeferred()
                b.send(MeshCodec.adminPacket(st.radio.myNodeNum, MeshCodec.admin(null) {
                    getConfigRequest = AdminProtos.AdminMessage.ConfigType.LORA_CONFIG
                }))
                withTimeoutOrNull(6_000) { adminResponse?.await() }
                adminResponse = null
                val msgs = MeshCodec.setupMessages(plan, psk, chName, st.radio.configs, LinkRepo.sessionPasskey)
                for ((label, msg) in msgs) {
                    b.send(MeshCodec.adminPacket(st.radio.myNodeNum, msg))
                    step("✓ $label")
                    delay(500)
                }
                step("Radio is saving and will reboot. Sovereign Comms reconnects automatically.")
                LinkRepo.setup { it.copy(running = false, done = true) }
                LinkRepo.log("Setup sent: ${plan.role} '${plan.longName}' channel $chName aead=${plan.useAead}")
            } catch (e: Exception) {
                LinkRepo.setup { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }
    private fun step(s: String) = LinkRepo.setup { it.copy(steps = it.steps + s) }

    // ---------------------------------------------------------------- internet sharing (MQTT)
    private fun refreshNet() {
        val s = Prefs.settings.value
        val want = s.netSharing && s.netReady && s.internetAllowed
        val sig = if (want) "${s.brokerUrl}|${s.brokerUser}|${s.brokerPass}|${s.netKeyHex}|${s.deviceId}" else null
        if (sig == netSig) { updateLocationRequests(); if (net != null) ensureLocationOn("sharing"); return }
        stopNet(clearRetained = false)
        netSig = sig
        if (!want) {
            // sharing is off: what was recorded while out of service is never sent later
            lifecycleScope.launch(Dispatchers.IO) { if (outage.size() > 0) { outage.clear(); LinkRepo.log("Out-of-service track discarded (sharing off)") } }
            updateLocationRequests(); return
        }
        val key = s.netKeyHex!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        netKey = key
        outage.bind(com.victor.traillink.core.NetCrypto.groupId(key))
        goForeground()
        try {
            val link = NetLink(s.brokerUrl, s.brokerUser, s.brokerPass, key, s.deviceId,
                onPosition = { p -> onNetPosition(p) },
                onOther = { rel, bytes -> onNetOther(rel, bytes) },
                onStatus = { st, d ->
                    LinkRepo.update { it.copy(net = st, netDetail = d) }
                    if (st == NetLink.NetStatus.ONLINE) {
                        LinkRepo.log("Internet: online (${s.brokerUrl})"); maybePublish(force = true)
                        lifecycleScope.launch(Dispatchers.IO) { messenger.publishOutbox(); pins.publishAllMine() }
                    }
                    else if (st == NetLink.NetStatus.OFFLINE) { outage.markTrouble(); LinkRepo.log("Internet: offline ${d ?: ""}") }
                    updateNotification()
                })
            net = link
            sharingActive = true; sharingSince = System.currentTimeMillis()
            link.start()
            ensureLocationOn("sharing started")
            if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrailLink:link")
            wake?.let { if (!it.isHeld) it.acquire() }
        } catch (e: Exception) {
            LinkRepo.update { it.copy(net = NetLink.NetStatus.OFFLINE, netDetail = e.message) }
            LinkRepo.log("Internet: bad broker settings: ${e.message}")
        }
        updateLocationRequests()
    }

    private fun stopNet(clearRetained: Boolean) {
        val n = net ?: return
        net = null; netSig = null
        sharingActive = false
        val forwardedSubs = forwardedNodes.map { "${Prefs.settings.value.deviceId}-%08x".format(it) }
        lifecycleScope.launch(Dispatchers.IO) {
            if (clearRetained) n.clearRetained(forwardedSubs)
            n.stop()
        }
        forwardedNodes.clear()
        LinkRepo.update { it.copy(net = NetLink.NetStatus.OFF, netDetail = null) }
        if (clearRetained) LinkRepo.log("Internet sharing stopped; retained positions removed from the broker")
        if (wantConnected == null) wake?.let { if (it.isHeld) it.release() }
        updateLocationRequests()
    }

    private fun onNetOther(rel: String, bytes: ByteArray) {
        when {
            rel.startsWith("o/") -> lifecycleScope.launch(Dispatchers.IO) { messenger.onOutbox(bytes) }
            rel.startsWith("p/") -> lifecycleScope.launch(Dispatchers.IO) { pins.onNet(bytes) }
            rel.startsWith("b/") -> lifecycleScope.launch(Dispatchers.IO) { onBackfill(rel, bytes) }
            rel.startsWith("v/") || rel.startsWith("vc/") -> voice.onNet(rel, bytes)
            rel.startsWith("c/") -> com.victor.traillink.call.CallManager.onNet(this, rel, bytes)
        }
    }

    private fun onNetPosition(p: com.victor.traillink.core.NetPosition) {
        when (val v = guard.check(p)) {
            com.victor.traillink.core.ReplayGuard.Verdict.OK -> {}
            else -> { if (v != com.victor.traillink.core.ReplayGuard.Verdict.REPLAY) LinkRepo.log("Internet: dropped ${v.name.lowercase()} message from ${p.name}"); return }
        }
        // ignore our own radio forwarded back by someone else? no: merge by newest timestamp handles it
        val updated = runBlocking(Dispatchers.IO) { store.onNet(p) }
        if (updated) { LinkRepo.log("Internet: ${if (p.src == "lora") "gateway position of node %08x".format(p.node) else "position from ${p.name}"}"); updateNotification() }
    }

    private val forwardedNodes = mutableSetOf<Long>()
    private fun forwardToNet(nodeNum: Long) {
        val s = Prefs.settings.value
        val n = net ?: return
        if (!s.gateway || !n.online) return
        lifecycleScope.launch(Dispatchers.IO) {
            val e = dao.node(nodeNum) ?: return@launch
            if (e.lat == null || e.posSource != "lora") return@launch
            val p = com.victor.traillink.core.NetPosition(id = s.deviceId, name = e.longName, short = e.shortName, lat = e.lat, lon = e.lon!!,
                alt = e.altM, battery = e.battery, ts = e.posTime, seq = Prefs.nextSeq(this@LinkService),
                src = com.victor.traillink.core.NetPosition.SRC_LORA, node = nodeNum, snr = e.snr, rssi = e.rssi)
            if (n.publish(p)) {
                forwardedNodes += nodeNum
                LinkRepo.update { it.copy(forwarded = it.forwarded + 1) }
                LinkRepo.log("Gateway: forwarded ${e.longName} to internet")
            }
        }
    }

    private var noLocPermLogged = false
    private fun updateLocationRequests() {
        // 2.2: also keep GPS on while a radio link is active, so my own track (for Backtrack) keeps recording in the background
        val want = net != null || (wantConnected != null && Prefs.settings.value.recordMyTrack)
        if (want && locator == null) {
            val l = PhoneLocator(this, 5000L) { onPhoneLocation(it) }
            if (l.start()) {
                locator = l
                locatorStartedAt = System.currentTimeMillis()
                LinkRepo.log("Location: ${if (l.usingFused) "fused (Google)" else "GPS + network"}; system Location ${if (PhoneLocator.systemEnabled(this)) "ON" else "OFF"}")
            } else if (!noLocPermLogged) { noLocPermLogged = true; LinkRepo.log("Location: no permission") }
        } else if (!want && locator != null) {
            locator?.stop(); locator = null
        }
    }

    private fun onLocationSwitch() {
        val on = PhoneLocator.systemEnabled(this)
        if (on == LinkRepo.state.value.locationOn) return
        LinkRepo.update { it.copy(locationOn = on) }
        LinkRepo.log("System Location turned ${if (on) "ON" else "OFF"}")
        if (!on && net != null) lifecycleScope.launch { delay(1500); ensureLocationOn("Location was switched off while sharing") }
        if (on && locator != null) { locator?.stop(); locator = null; updateLocationRequests() }
        updateNotification()
    }

    /** 'Keep Location on while sharing': needs  adb shell pm grant com.victor.traillink android.permission.WRITE_SECURE_SETTINGS */
    private fun ensureLocationOn(why: String) {
        if (!Prefs.settings.value.keepLocationOn || net == null || PhoneLocator.systemEnabled(this)) return
        if (!granted(Manifest.permission.WRITE_SECURE_SETTINGS)) { LinkRepo.log("Location is off; can't turn it on (WRITE_SECURE_SETTINGS not granted)"); return }
        val lm = getSystemService(android.location.LocationManager::class.java)
        var ok = runCatching {
            android.location.LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java)
                .invoke(lm, true, android.os.Process.myUserHandle())
            PhoneLocator.systemEnabled(this)
        }.getOrDefault(false)
        if (!ok) ok = runCatching {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.putInt(contentResolver, android.provider.Settings.Secure.LOCATION_MODE, android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
            Thread.sleep(300); PhoneLocator.systemEnabled(this)
        }.getOrDefault(false)
        LinkRepo.log(if (ok) "Turned Location ON ($why)" else "Couldn't turn Location on ($why)")
        if (ok) onLocationSwitch()
    }

    private var locatorStartedAt = 0L
    private fun onPhoneLocation(l: android.location.Location) {
        // Ignore cached/stale fixes (e.g. the last-known position from hours ago in town, handed out
        // when Location is switched on) and, for the first minute, coarse cell/Wi-Fi fixes while GPS warms up.
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 120_000) { LinkRepo.log("Location: ignored a ${ageMs / 60000} min old cached fix"); return }
        val coarse = !l.hasAccuracy() || l.accuracy > 100f
        if (coarse && System.currentTimeMillis() - locatorStartedAt < 60_000) return
        lastLoc = l
        PhoneLocation.last = l.latitude to l.longitude
        MyTrack.onFix(this, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, if (l.hasAltitude()) l.altitude.toInt() else null)
        LinkRepo.update { it.copy(lastFixAt = System.currentTimeMillis()) }
        // 2.5: every ~45 s while sharing; kept only while out of service (see core/Backfill.kt)
        if (net != null) {
            val ts = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                outage.offer(com.victor.traillink.core.BfPoint(ts, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null,
                    if (l.hasSpeed()) l.speed else null, if (l.hasAltitude()) l.altitude.toInt() else null))
            }
        }
        maybePublish(force = false)
    }

    private fun maybePublish(force: Boolean) {
        val n = net ?: return
        val l = lastLoc ?: return
        if (!n.online) return
        val s = Prefs.settings.value
        val now = System.currentTimeMillis()
        val since = now - lastPubAt
        val moved = lastPubFix?.distanceTo(l) ?: Float.MAX_VALUE
        val moving = moved > maxOf(25f, l.accuracy) || (l.hasSpeed() && l.speed > 0.7f)
        val due = force || (moving && since >= s.netMovingSec * 1000L) || since >= s.netStillSec * 1000L
        if (!due) return
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val bat = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val p = com.victor.traillink.core.NetPosition(id = s.deviceId, name = s.myName, short = s.myShort, lat = l.latitude, lon = l.longitude,
            alt = if (l.hasAltitude()) l.altitude.toInt() else null, acc = if (l.hasAccuracy()) l.accuracy else null,
            speed = if (l.hasSpeed()) l.speed else null, heading = if (l.hasBearing()) l.bearing else null,
            battery = bat, ts = now, seq = Prefs.nextSeq(this), src = com.victor.traillink.core.NetPosition.SRC_PHONE)
        val sent = n.publish(p) { ok ->
            if (ok) {
                liveSentAt = 0L
                lifecycleScope.launch(Dispatchers.IO) { val had = outage.troubled; outage.onLiveAck(p.ts); if (had) LinkRepo.log("Back in service: ${outage.pendingCount()} recorded points to send"); sendBackfill() }
            } else { outage.markTrouble(); liveSentAt = 0L }
        }
        if (sent) {
            if (liveSentAt == 0L) liveSentAt = now
            lastPubAt = now; lastPubFix = l
            LinkRepo.update { it.copy(lastPublish = now) }
        } else outage.markTrouble()
    }

    /** 2.5: out-of-service detection: sharing is on but positions can't be delivered (link down, or not confirmed in 45 s). */
    private fun checkService() {
        val n = net ?: return
        val now = System.currentTimeMillis()
        if (!n.online && lastLoc != null) outage.markTrouble()
        val sentAt = liveSentAt
        if (sentAt > 0 && now - sentAt > com.victor.traillink.core.OutageQueue.ACK_TIMEOUT_MS) {
            if (!outage.troubled) LinkRepo.log("Internet: position not confirmed for 45 s; recording track for backfill")
            outage.markTrouble()
        }
        LinkRepo.update { it.copy(backfillQueued = outage.size(), backfillOut = outage.troubled) }
        if (n.online && !outage.troubled && outage.pendingCount() > 0 && now - backfillTriedAt > 60_000)
            lifecycleScope.launch(Dispatchers.IO) { sendBackfill() }
    }

    /** Sends the recorded out-of-service points in chunks of 500, one at a time; each is removed only after the broker confirms it. */
    private suspend fun sendBackfill() {
        if (backfillBusy) return
        backfillBusy = true
        try {
            backfillTriedAt = System.currentTimeMillis()
            val s = Prefs.settings.value
            var sentPts = 0; var chunks = 0
            while (true) {
                val n = net ?: break; val key = netKey ?: break
                if (!n.online || outage.troubled) break
                val batch = outage.nextBatch()
                if (batch.isEmpty()) break
                val b = com.victor.traillink.core.Backfill(s.deviceId, s.myName, s.myShort, Prefs.nextSeq(this), batch)
                val bytes = com.victor.traillink.core.BackfillWire.seal(key, b)
                if (!n.publishConfirmed(com.victor.traillink.core.BackfillWire.sub(s.deviceId, outage.slot), bytes, retained = true, timeoutMs = 20_000)) {
                    LinkRepo.log("Backfill: send not confirmed, will retry"); break
                }
                outage.confirmSent(batch)
                sentPts += batch.size; chunks++
                delay(1_500)   // rate limit
            }
            LinkRepo.update { it.copy(backfillQueued = outage.size(), backfillOut = outage.troubled) }
            if (sentPts > 0) LinkRepo.log("Backfill: sent $sentPts recorded points ($chunks chunk${if (chunks == 1) "" else "s"})")
        } finally { backfillBusy = false }
    }

    private suspend fun onBackfill(rel: String, bytes: ByteArray) {
        val key = netKey ?: return
        val b = com.victor.traillink.core.BackfillWire.open(key, bytes) ?: return
        if (!rel.startsWith("b/${b.id}/") || b.id == Prefs.settings.value.deviceId) return
        val added = store.onBackfill(b)
        if (added > 0) { LinkRepo.log("Internet: $added backfilled track points from ${b.name}"); updateNotification() }
    }

    // ---------------------------------------------------------------- notifications & lost contact
    private suspend fun partners(): List<NodeEntity> {
        return withContext(Dispatchers.IO) { dao.nodesNow() }.filter { !it.isSelf && !it.demo }
    }

    private suspend fun checkLost() {
        val s = Prefs.settings.value
        if (!s.lostAlarm) return
        val st = LinkRepo.state.value
        if (st.conn != Conn.CONNECTED && st.net != NetLink.NetStatus.ONLINE) return
        val now = System.currentTimeMillis()
        val limit = s.lostMinutes * 60_000L
        for (p in partners()) {
            val age = now - p.lastHeard
            if (p.lastHeard == 0L || age > 12 * 3_600_000L) continue // never/long ago: not part of this trip
            if (age > limit && !p.alarmMuted) {
                if (alarmed.add(p.num)) postLost(p, age)
            } else if (age <= limit && alarmed.remove(p.num)) {
                NotificationManagerCompat.from(this).cancel(1000 + (p.num and 0xFFFF).toInt())
                LinkRepo.log("Back in contact: ${p.longName}")
            }
        }
    }

    private fun postLost(p: NodeEntity, age: Long) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val n = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle("Lost contact: ${p.longName}")
            .setContentText("Not heard for ${Geo.formatAge(age).removeSuffix(" ago")}. Last position on the map.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(p.num))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(1000 + (p.num and 0xFFFF).toInt(), n)
        LinkRepo.log("Lost contact alarm: ${p.longName}")
    }

    private fun openApp(focus: Long? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        focus?.let { i.putExtra("focusNode", it) }
        return PendingIntent.getActivity(this, (focus ?: 0L).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(title: String, text: String?): Notification =
        NotificationCompat.Builder(this, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text ?: ""))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun updateNotification() {
        lifecycleScope.launch {
            val st = LinkRepo.state.value
            val list = partners().filter { it.lat != null }.sortedByDescending { it.lastHeard }.take(4)
            val now = System.currentTimeMillis()
            val phone = PhoneLocation.last
            val lines = list.joinToString("\n") { p ->
                val where = if (phone != null && p.lat != null) {
                    val d = Geo.distanceM(phone.first, phone.second, p.lat, p.lon!!)
                    val b = Geo.bearingDeg(phone.first, phone.second, p.lat, p.lon)
                    "${Geo.formatDistance(d, Prefs.settings.value.metric)} ${Geo.cardinal(b)}"
                } else "%.5f, %.5f".format(p.lat, p.lon)
                "${p.shortName} [${srcBadge(p.posSource)}]: $where · ${Geo.formatAge(now - p.posTime)}"
            }
            val title = listOfNotNull(
                    if (wantConnected != null) "Radio: ${st.conn.label}" else null,
                    if (net != null) "Internet: ${st.net.label}" else null).joinToString(" · ").ifBlank { "Sovereign Comms" }
            val warn = when {
                net != null && !st.locationOn -> "⚠ Phone Location is OFF — your position is not being sent.\n"
                net != null && lastLoc == null -> "Waiting for a GPS fix…\n"
                else -> ""
            }
            val n = buildNotification(title, warn + lines.ifBlank { "No partner positions yet" })
            if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                NotificationManagerCompat.from(this@LinkService).notify(NOTIF_ID, n)
        }
    }
}

fun srcBadge(s: String?) = when (s) { "lora" -> "LoRa"; "net" -> "Net"; "gw" -> "Net via radio"; else -> "LoRa" }

/** Last known phone location (lat, lon), shared between the UI and the service notification. */
object PhoneLocation { @Volatile var last: Pair<Double, Double>? = null }
