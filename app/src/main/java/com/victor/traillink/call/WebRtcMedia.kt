package com.victor.traillink.call

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.victor.traillink.data.Settings
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/** Audio-only WebRTC peer connection: Opus over DTLS-SRTP, ICE with STUN and an optional TURN relay. */
class WebRtcMedia(ctx: Context, s: Settings, private val ev: CallMedia.Events) : CallMedia {
    companion object {
        private const val TAG = "SCCall"
        @Volatile private var factory: PeerConnectionFactory? = null
        @Volatile private var adm: JavaAudioDeviceModule? = null

        @Synchronized private fun factory(ctx: Context): PeerConnectionFactory {
            factory?.let { return it }
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            val a = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(m: String?) { Log.w(TAG, "record init: $m") }
                    override fun onWebRtcAudioRecordStartError(c: JavaAudioDeviceModule.AudioRecordStartErrorCode?, m: String?) { Log.w(TAG, "record start: $m") }
                    override fun onWebRtcAudioRecordError(m: String?) { Log.w(TAG, "record: $m") }
                })
                .createAudioDeviceModule()
            adm = a
            return PeerConnectionFactory.builder().setAudioDeviceModule(a).createPeerConnectionFactory().also { factory = it }
        }

        /** ICE servers from settings. Never a paid service by default: public STUN only, TURN only if the user adds one. */
        fun iceServers(s: Settings): List<PeerConnection.IceServer> = buildList {
            s.stunUrl.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                add(PeerConnection.IceServer.builder(if (it.startsWith("stun:")) it else "stun:$it").createIceServer())
            }
            s.turnUrl.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                val url = if (it.startsWith("turn:") || it.startsWith("turns:")) it else "turn:$it"
                add(PeerConnection.IceServer.builder(url).setUsername(s.turnUser).setPassword(s.turnPass).createIceServer())
            }
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val pc: PeerConnection
    private val source: AudioSource
    private val track: AudioTrack
    private val pendingRemote = ArrayList<IceCandidate>()
    private var remoteSet = false
    @Volatile private var closed = false
    @Volatile private var route: String? = null
    private val hasTurn = s.turnUrl.isNotBlank()

    init {
        val f = factory(ctx)
        val cfg = PeerConnection.RTCConfiguration(iceServers(s)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (s.turnOnly && hasTurn) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
        }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) { Log.i(TAG, "ice: $st") }
            override fun onConnectionChange(st: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "pc: $st")
                if (closed) return
                when (st) {
                    PeerConnection.PeerConnectionState.CONNECTING -> ev.onNet(CallMedia.Net.CONNECTING)
                    PeerConnection.PeerConnectionState.CONNECTED -> { ev.onNet(CallMedia.Net.CONNECTED); probeRoute(); startStatsLog() }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> ev.onNet(CallMedia.Net.RECONNECTING)
                    PeerConnection.PeerConnectionState.FAILED -> ev.onNet(CallMedia.Net.FAILED,
                        if (hasTurn) "Couldn't connect the audio, even through your relay (TURN) server. Check its address, username and password in More → Calls."
                        else "Couldn't connect the audio. A relay (TURN) server may be needed — see More → Calls (Settings).")
                    PeerConnection.PeerConnectionState.CLOSED -> ev.onNet(CallMedia.Net.CLOSED)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                if (closed) return
                Log.i(TAG, "local cand: ${candSummary(c.sdp)}")
                ev.onLocalCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
            }
            override fun onIceCandidateError(e: IceCandidateErrorEvent?) { Log.w(TAG, "ice server error: ${e?.url} ${e?.errorCode} ${e?.errorText}") }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(e: CandidatePairChangeEvent?) { probeRoute() }
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("WebRTC: couldn't create the connection")
        source = f.createAudioSource(MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        })
        track = f.createAudioTrack("sc-audio", source)
        pc.addTrack(track, listOf("sc"))
    }

    private fun sdpObs(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { exec.execute { if (!closed) onCreate(d) } }
        override fun onSetSuccess() { exec.execute { if (!closed) onSet() } }
        override fun onCreateFailure(e: String?) { Log.w(TAG, "sdp create: $e"); ev.onNet(CallMedia.Net.FAILED, "Call setup failed ($e)") }
        override fun onSetFailure(e: String?) { Log.w(TAG, "sdp set: $e"); ev.onNet(CallMedia.Net.FAILED, "Call setup failed ($e)") }
    }

    private val audioOnly = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
    }

    override fun createOffer() = exec.execute {
        pc.createOffer(sdpObs(onCreate = { d ->
            pc.setLocalDescription(sdpObs(onSet = { ev.onLocalDescription("offer", d.description) }), d)
        }), audioOnly)
    }

    override fun applyRemote(type: String, sdp: String) = exec.execute {
        if (closed) return@execute
        val t = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        pc.setRemoteDescription(sdpObs(onSet = {
            remoteSet = true
            pendingRemote.forEach { pc.addIceCandidate(it) }; pendingRemote.clear()
            if (t == SessionDescription.Type.OFFER) pc.createAnswer(sdpObs(onCreate = { d ->
                pc.setLocalDescription(sdpObs(onSet = { ev.onLocalDescription("answer", d.description) }), d)
            }), audioOnly)
        }), SessionDescription(t, sdp))
    }

    override fun addRemoteCandidate(mid: String?, idx: Int, cand: String) = exec.execute {
        if (closed) return@execute
        Log.i(TAG, "remote cand: ${candSummary(cand)}")
        val c = IceCandidate(mid, idx, cand)
        if (remoteSet) pc.addIceCandidate(c) else pendingRemote += c
    }

    /** "udp ipv4 srflx" style summary (no addresses in the log). */
    private fun candSummary(sdp: String): String {
        val f = sdp.removePrefix("a=").split(' ')
        val proto = f.getOrNull(2) ?: "?"
        val ip = f.getOrNull(4) ?: ""
        val fam = when { ip.contains(':') -> "ipv6"; ip.endsWith(".local") -> "mdns"; else -> "ipv4" }
        val typ = f.indexOf("typ").takeIf { it >= 0 }?.let { f.getOrNull(it + 1) } ?: "?"
        return "$proto $fam $typ"
    }

    override fun setMuted(muted: Boolean) { exec.execute { if (!closed) track.setEnabled(!muted) } }

    override fun routeLabel(): String? = route

    private fun probeRoute() {
        if (closed) return
        runCatching {
            pc.getStats { r ->
                val m = r.statsMap
                val pair = m.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                    ?: m.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")?.let { m[it] }
                val local = pair?.members?.get("localCandidateId")?.let { m[it] }
                val remote = pair?.members?.get("remoteCandidateId")?.let { m[it] }
                val lt = local?.members?.get("candidateType") as? String
                val rt = remote?.members?.get("candidateType") as? String
                route = when {
                    lt == "relay" || rt == "relay" -> "via relay (TURN)"
                    lt == "host" && rt == "host" -> "direct (same network)"
                    lt != null -> "direct"
                    else -> null
                }
                Log.i(TAG, "route: local=$lt remote=$rt -> $route")
            }
        }
    }

    private var statsThread: Thread? = null
    /** Logs audio flow every 5 s (adb logcat -s SCCall) — handy to diagnose one-way audio. */
    private fun startStatsLog() {
        if (statsThread != null) return
        statsThread = Thread {
            while (!closed) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                if (closed) break
                runCatching {
                    pc.getStats { r ->
                        val inb = r.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }?.members
                        val out = r.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "audio" }?.members
                        val codec = r.statsMap.values.firstOrNull { it.type == "codec" && (it.members["mimeType"] as? String)?.startsWith("audio") == true }?.members?.get("mimeType")
                        Log.i(TAG, "stats: codec=$codec rx=${inb?.get("bytesReceived")}B lost=${inb?.get("packetsLost")} jitter=${inb?.get("jitter")} level=${inb?.get("audioLevel")} tx=${out?.get("bytesSent")}B route=$route")
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun close() {
        if (closed) return
        closed = true
        statsThread?.interrupt()
        exec.execute {
            runCatching { pc.close() }; runCatching { pc.dispose() }
            runCatching { source.dispose() }
        }
        exec.shutdown()
    }
}
