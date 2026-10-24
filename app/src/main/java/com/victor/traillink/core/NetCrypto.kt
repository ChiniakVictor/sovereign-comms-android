package com.victor.traillink.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A position shared over the internet (MQTT). */
data class NetPosition(
    val id: String,              // sender install id (8 hex) — or gateway id when forwarded
    val name: String,
    val short: String,
    val lat: Double, val lon: Double,
    val alt: Int? = null, val acc: Float? = null,
    val speed: Float? = null, val heading: Float? = null,
    val battery: Int? = null,
    val ts: Long,                // ms epoch when the position was taken
    val seq: Long,
    val src: String = SRC_PHONE, // "phone" = sender's own GPS, "lora" = forwarded from a radio by a gateway
    val node: Long? = null,      // Meshtastic node number when src == lora
    val snr: Float? = null, val rssi: Int? = null,
) {
    companion object { const val SRC_PHONE = "phone"; const val SRC_LORA = "lora" }

    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("id", id); put("name", name); put("short", short)
        put("lat", lat); put("lon", lon); put("ts", ts); put("seq", seq); put("src", src)
        alt?.let { put("alt", it) }; acc?.let { put("acc", it.toDouble()) }
        speed?.let { put("spd", it.toDouble()) }; heading?.let { put("hdg", it.toDouble()) }
        battery?.let { put("bat", it) }; node?.let { put("node", it) }
        snr?.let { put("snr", it.toDouble()) }; rssi?.let { put("rssi", it) }
    }.toString()

    object Parse {
        fun fromJson(s: String): NetPosition? = try {
            val o = JSONObject(s)
            if (o.optInt("v") != 1) null else NetPosition(
                id = o.getString("id").take(32), name = o.optString("name").take(40), short = o.optString("short").take(6),
                lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                alt = if (o.has("alt")) o.getInt("alt") else null, acc = if (o.has("acc")) o.getDouble("acc").toFloat() else null,
                speed = if (o.has("spd")) o.getDouble("spd").toFloat() else null, heading = if (o.has("hdg")) o.getDouble("hdg").toFloat() else null,
                battery = if (o.has("bat")) o.getInt("bat") else null, ts = o.getLong("ts"), seq = o.getLong("seq"),
                src = o.optString("src", SRC_PHONE), node = if (o.has("node")) o.getLong("node") else null,
                snr = if (o.has("snr")) o.getDouble("snr").toFloat() else null, rssi = if (o.has("rssi")) o.getInt("rssi") else null,
            ).takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        } catch (e: Exception) { null }
    }
}

/**
 * Internet sharing keys, derived from the same passcode as the radio channel (one-step pairing) but with a
 * different PBKDF2 context, so the LoRa PSK and the internet key are unrelated.
 *
 *  netKey  = PBKDF2-HMAC-SHA256(passcode, "com.victor.traillink/net/v1/" + channelName, 100k, 32 bytes)
 *  topic   = "traillink/v1/" + first 16 hex of SHA-256(netKey || "topic") + "/" + senderId
 *  message = "TL1" | 12-byte random nonce | AES-256-GCM(json, aad = group topic)    (tag 128 bit)
 */
object NetCrypto {
    const val NET_SALT = "com.victor.traillink/net/v1/"
    const val TOPIC_ROOT = "traillink/v1/"
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    private val rng = SecureRandom()

    fun deriveNetKey(passcode: String, iterations: Int = Psk.ITERATIONS): ByteArray {
        val name = Psk.channelName(passcode)
        return Psk.pbkdf2Sha256(Psk.normalize(passcode).toCharArray(), (NET_SALT + name).toByteArray(Charsets.UTF_8), iterations, 32)
    }

    fun groupId(netKey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(netKey); md.update("topic".toByteArray(Charsets.UTF_8))
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }
    fun groupTopic(netKey: ByteArray) = TOPIC_ROOT + groupId(netKey)
    fun deviceTopic(netKey: ByteArray, senderId: String) = groupTopic(netKey) + "/" + senderId
    fun subscribeFilter(netKey: ByteArray) = groupTopic(netKey) + "/+"

    fun encrypt(netKey: ByteArray, groupTopic: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(netKey, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(groupTopic.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plaintext)
        return ByteBuffer.allocate(3 + 12 + ct.size).put(MAGIC).put(nonce).put(ct).array()
    }

    /** Returns null when the message isn't ours, was tampered with, or uses another key. */
    fun decrypt(netKey: ByteArray, groupTopic: String, msg: ByteArray): ByteArray? {
        if (msg.size < 3 + 12 + 16 || !msg.copyOfRange(0, 3).contentEquals(MAGIC)) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(netKey, "AES"), GCMParameterSpec(128, msg, 3, 12))
            c.updateAAD(groupTopic.toByteArray(Charsets.UTF_8))
            c.doFinal(msg, 15, msg.size - 15)
        } catch (e: Exception) { null }
    }

    fun seal(netKey: ByteArray, p: NetPosition): ByteArray = encrypt(netKey, groupTopic(netKey), p.toJson().toByteArray(Charsets.UTF_8))
    fun open(netKey: ByteArray, msg: ByteArray): NetPosition? =
        decrypt(netKey, groupTopic(netKey), msg)?.let { NetPosition.Parse.fromJson(String(it, Charsets.UTF_8)) }
}

/**
 * Rejects replays and stale/future data. Per stream (sender id + forwarded node) a message is accepted only if
 * it is newer than the last accepted one (timestamp, then sequence number), not older than [maxAgeMs], and not
 * more than [maxFutureMs] ahead of our clock.
 */
class ReplayGuard(private val maxAgeMs: Long = 12 * 3_600_000L, private val maxFutureMs: Long = 5 * 60_000L) {
    enum class Verdict { OK, REPLAY, STALE, FUTURE }
    private val last = HashMap<String, Pair<Long, Long>>()

    @Synchronized
    fun check(p: NetPosition, now: Long = System.currentTimeMillis()): Verdict {
        if (p.ts > now + maxFutureMs) return Verdict.FUTURE
        if (p.ts < now - maxAgeMs) return Verdict.STALE
        val key = p.id + "/" + (p.node ?: "")
        val prev = last[key]
        if (prev != null) {
            val (pts, pseq) = prev
            if (p.ts < pts || (p.ts == pts && p.seq <= pseq)) return Verdict.REPLAY
        }
        last[key] = p.ts to p.seq
        return Verdict.OK
    }
}
