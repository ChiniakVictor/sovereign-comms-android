package com.victor.traillink.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Push-to-talk voice on the wire (internet only).
 *  voiceKey = HMAC-SHA256(netKey, "com.victor.traillink/voice/v1")   — separate context from positions/messages
 *  live chunk (QoS 0)  : topic <group>/v/<sender>         seq >= 0, ~400 ms of frames
 *  full clip (retained): topic <group>/vc/<sender>/<slot> seq = -1, all frames (≤ 60 s), last 5 clips per sender, 24 h
 *  payload = NetCrypto.encrypt(voiceKey, groupTopic, "TLV1" | u16 headerLen | header JSON | (u16 len | frame)*)
 */
data class VoiceHeader(
    val id: String, val from: String, val name: String, val to: String?, val ts: Long,
    val codec: String, val sr: Int, val seq: Int, val last: Boolean, val durMs: Long, val csd: ByteArray?,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("from", from); put("name", name); to?.let { put("to", it) }; put("ts", ts)
        put("codec", codec); put("sr", sr); put("seq", seq); put("last", last); put("dur", durMs)
        csd?.let { put("csd", java.util.Base64.getEncoder().encodeToString(it)) }
    }.toString()
    override fun equals(other: Any?) = other is VoiceHeader && other.toJson() == toJson()
    override fun hashCode() = toJson().hashCode()
    companion object {
        fun fromJson(s: String): VoiceHeader? = runCatching {
            val o = JSONObject(s)
            VoiceHeader(o.getString("id").take(16), o.getString("from").take(40), o.optString("name").take(40),
                if (o.has("to")) o.getString("to").take(40) else null, o.getLong("ts"), o.getString("codec"), o.getInt("sr"),
                o.getInt("seq"), o.optBoolean("last"), o.optLong("dur"),
                if (o.has("csd")) java.util.Base64.getDecoder().decode(o.getString("csd")) else null)
        }.getOrNull()
    }
}

class VoicePacket(val h: VoiceHeader, val frames: List<ByteArray>)

object VoiceWire {
    const val MAX_CLIP_MS = 60_000L
    const val SLOTS = 5
    private val MAGIC = "TLV1".toByteArray()

    fun deriveVoiceKey(netKey: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(netKey, "HmacSHA256")) }.doFinal("com.victor.traillink/voice/v1".toByteArray())

    fun pack(p: VoicePacket): ByteArray {
        val hb = p.h.toJson().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(hb.size + p.frames.sumOf { it.size + 2 } + 8)
        out.write(MAGIC); out.write(hb.size shr 8); out.write(hb.size and 0xff); out.write(hb)
        p.frames.forEach { f -> out.write(f.size shr 8); out.write(f.size and 0xff); out.write(f) }
        return out.toByteArray()
    }

    fun unpack(b: ByteArray): VoicePacket? = runCatching {
        val bb = ByteBuffer.wrap(b)
        val m = ByteArray(4).also { bb.get(it) }
        if (!m.contentEquals(MAGIC)) return null
        val hl = bb.short.toInt() and 0xffff
        val h = VoiceHeader.fromJson(String(ByteArray(hl).also { bb.get(it) }, Charsets.UTF_8)) ?: return null
        val frames = ArrayList<ByteArray>()
        while (bb.remaining() >= 2) { val l = bb.short.toInt() and 0xffff; if (l > bb.remaining()) return null; frames += ByteArray(l).also { bb.get(it) } }
        VoicePacket(h, frames)
    }.getOrNull()

    fun seal(voiceKey: ByteArray, groupTopic: String, p: VoicePacket) = NetCrypto.encrypt(voiceKey, groupTopic, pack(p))
    fun open(voiceKey: ByteArray, groupTopic: String, b: ByteArray) = NetCrypto.decrypt(voiceKey, groupTopic, b)?.let { unpack(it) }
}
