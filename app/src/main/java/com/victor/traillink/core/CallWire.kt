package com.victor.traillink.core

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Call signaling (2.0) — sent over the group's encrypted MQTT link, never retained.
 *
 *  topic   = <group topic>/c/<toId>/<fromId>
 *  key     = HMAC-SHA256(netKey, "com.victor.traillink/call/v1")
 *  message = NetCrypto.encrypt(key, <group topic>, json)        (AES-256-GCM, "TL1" framing)
 *
 * Types: ring, cancel, accept, decline, busy, offer, answer, ice, hangup.
 * The audio itself never goes through MQTT: it is WebRTC (Opus over DTLS-SRTP), whose DTLS fingerprints
 * travel inside the encrypted offer/answer, so only the two phones hold the media keys.
 */
data class CallSignal(
    val type: String,
    val call: String,            // call id (16 hex)
    val from: String,            // sender install id (8 hex)
    val name: String,            // sender display name
    val ts: Long,
    val sdp: String? = null,     // offer / answer
    val mid: String? = null,     // ice
    val idx: Int = 0,            // ice
    val cand: String? = null,    // ice
    val reason: String? = null,  // hangup / decline detail
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("t", type); put("call", call); put("from", from); put("name", name); put("ts", ts)
        sdp?.let { put("sdp", it) }; mid?.let { put("mid", it) }; if (cand != null) { put("idx", idx); put("cand", cand) }
        reason?.let { put("why", it) }
    }.toString()

    companion object {
        const val RING = "ring"; const val CANCEL = "cancel"; const val ACCEPT = "accept"; const val DECLINE = "decline"
        const val BUSY = "busy"; const val OFFER = "offer"; const val ANSWER = "answer"; const val ICE = "ice"; const val HANGUP = "hangup"
        val TYPES = setOf(RING, CANCEL, ACCEPT, DECLINE, BUSY, OFFER, ANSWER, ICE, HANGUP)

        fun fromJson(s: String): CallSignal? = try {
            val o = JSONObject(s)
            val t = o.getString("t")
            if (o.optInt("v") != 1 || t !in TYPES) null else CallSignal(
                type = t, call = o.getString("call").take(32), from = o.getString("from").take(32), name = o.optString("name").take(40),
                ts = o.getLong("ts"), sdp = o.optString("sdp").takeIf { o.has("sdp") }?.take(20_000),
                mid = if (o.has("mid")) o.getString("mid").take(16) else null, idx = o.optInt("idx"),
                cand = if (o.has("cand")) o.getString("cand").take(1000) else null, reason = if (o.has("why")) o.getString("why").take(80) else null)
        } catch (e: Exception) { null }
    }
}

object CallWire {
    /** Signals older than this (or this far in the future) are ignored, so a captured ring can't be replayed later. */
    const val MAX_SKEW_MS = 90_000L

    fun deriveCallKey(netKey: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(netKey, "HmacSHA256")) }.doFinal("com.victor.traillink/call/v1".toByteArray())

    fun sub(toId: String, fromId: String) = "c/$toId/$fromId"

    fun seal(netKey: ByteArray, s: CallSignal): ByteArray =
        NetCrypto.encrypt(deriveCallKey(netKey), NetCrypto.groupTopic(netKey), s.toJson().toByteArray(Charsets.UTF_8))

    fun open(netKey: ByteArray, b: ByteArray, now: Long = System.currentTimeMillis()): CallSignal? =
        NetCrypto.decrypt(deriveCallKey(netKey), NetCrypto.groupTopic(netKey), b)
            ?.let { CallSignal.fromJson(String(it, Charsets.UTF_8)) }
            ?.takeIf { kotlin.math.abs(now - it.ts) <= MAX_SKEW_MS }
}
