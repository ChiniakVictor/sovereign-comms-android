package com.victor.traillink.call

/**
 * Who a call goes to. Today only [AppPeer] (another Sovereign Comms phone in the group) is built.
 *
 * Seam for a later optional "Phone line" (SIP, e.g. VoIP.ms): add a SipCallTransport that handles [PhoneNumber],
 * register it in [CallTransports.all], and give it its own settings. CallManager, the call screen, the ringing
 * notification and the foreground service only talk to [CallTransport] / [CallMedia], so they stay as they are.
 */
sealed class CallTarget {
    abstract val display: String
    /** Another Sovereign Comms install, by its install id (the "n:<id>" chat key without the prefix). */
    data class AppPeer(val deviceId: String, override val display: String) : CallTarget()
    /** NOT BUILT: a real phone number through an optional SIP provider. */
    data class PhoneNumber(val e164: String, override val display: String = e164) : CallTarget()
}

/** One live audio path. WebRTC today; a SIP user agent could implement the same thing later. */
interface CallMedia {
    /** Connection states shown on the call screen. */
    enum class Net { NEW, CONNECTING, CONNECTED, RECONNECTING, FAILED, CLOSED }
    interface Events {
        fun onLocalDescription(type: String, sdp: String)
        fun onLocalCandidate(mid: String?, idx: Int, cand: String)
        fun onNet(state: Net, detail: String? = null)
    }
    fun createOffer()
    fun applyRemote(type: String, sdp: String)
    fun addRemoteCandidate(mid: String?, idx: Int, cand: String)
    fun setMuted(muted: Boolean)
    /** Short text for the call screen, e.g. "direct" or "via relay". */
    fun routeLabel(): String?
    fun close()
}

/** A way of placing/receiving calls. Signaling lives in the transport; media in [CallMedia]. */
interface CallTransport {
    val label: String
    fun handles(t: CallTarget): Boolean
    /** True when a call could be placed right now (e.g. internet sharing online). */
    fun ready(): Boolean
    /** Error text for the user when not [ready]. */
    fun notReadyReason(): String
}

object CallTransports {
    val all: List<CallTransport> get() = listOf(AppCallTransport)   // + SipCallTransport later
    fun forTarget(t: CallTarget): CallTransport? = all.firstOrNull { it.handles(t) }
}
