package com.victor.traillink.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A chat message on the wire. The id is the 32-bit Meshtastic packet id (8 hex) so the same message sent over
 * LoRa and the internet dedupes on the receiver.
 *  from: "n:<installId>" (phone over internet) or "l:<nodeNum>" (radio, bridged by a gateway)
 *  to:   null = group, otherwise a peer key in the same form
 */
data class ChatMsg(
    val id: String, val from: String, val fromName: String, val to: String?,
    val text: String, val ts: Long, val sos: Boolean = false, val lat: Double? = null, val lon: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("from", from); put("name", fromName); to?.let { put("to", it) }
        put("text", text); put("ts", ts); if (sos) put("sos", true); lat?.let { put("lat", it) }; lon?.let { put("lon", it) }
    }
    companion object {
        const val MAX_BYTES = 200
        fun fromJson(o: JSONObject): ChatMsg? = runCatching {
            ChatMsg(o.getString("id").take(16), o.getString("from").take(40), o.optString("name").take(40), if (o.has("to")) o.getString("to").take(40) else null,
                o.getString("text").let { clip(it) }, o.getLong("ts"), o.optBoolean("sos"),
                if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lon")) o.getDouble("lon") else null)
        }.getOrNull()
        /** Trims to [MAX_BYTES] UTF-8 bytes without splitting a character. */
        fun clip(s: String, max: Int = MAX_BYTES): String {
            if (s.toByteArray(Charsets.UTF_8).size <= max) return s
            var end = s.length
            while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > max) end--
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }
        fun newId(): String = "%08x".format(kotlin.random.Random.nextInt() and 0x7fffffff or 0x10000)
        const val SOS_PREFIX = "🆘 SOS"
    }
}

/** Receipt from a recipient: kind "d" = delivered, "r" = read. */
data class Receipt(val ref: String, val by: String, val kind: String, val ts: Long) {
    fun toJson(): JSONObject = JSONObject().put("ref", ref).put("by", by).put("k", kind).put("ts", ts)
    companion object { fun fromJson(o: JSONObject) = runCatching { Receipt(o.getString("ref"), o.getString("by"), o.getString("k"), o.getLong("ts")) }.getOrNull() }
}

/**
 * Each phone keeps ONE retained, encrypted "outbox" topic with its recent messages (<= 24 h, <= 30) and
 * receipts. Live delivery and offline catch-up use the same mechanism; receivers dedupe by id.
 */
data class Outbox(val from: String, val msgs: List<ChatMsg>, val receipts: List<Receipt>, val ts: Long) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("k", "outbox"); put("from", from); put("ts", ts)
        put("msgs", JSONArray().apply { msgs.forEach { put(it.toJson()) } })
        put("rc", JSONArray().apply { receipts.forEach { put(it.toJson()) } })
    }.toString()
    fun pruned(now: Long, maxAgeMs: Long = 24 * 3_600_000L, maxMsgs: Int = 30, maxRc: Int = 60) = copy(
        msgs = msgs.filter { now - it.ts <= maxAgeMs }.sortedBy { it.ts }.takeLast(maxMsgs),
        receipts = receipts.filter { now - it.ts <= maxAgeMs }.sortedBy { it.ts }.takeLast(maxRc))
    companion object {
        fun fromJson(s: String): Outbox? = runCatching {
            val o = JSONObject(s)
            if (o.optInt("v") != 1 || o.optString("k") != "outbox") return null
            val m = o.optJSONArray("msgs") ?: JSONArray(); val r = o.optJSONArray("rc") ?: JSONArray()
            Outbox(o.getString("from"), (0 until m.length()).mapNotNull { ChatMsg.fromJson(m.getJSONObject(it)) },
                (0 until r.length()).mapNotNull { Receipt.fromJson(r.getJSONObject(it)) }, o.getLong("ts"))
        }.getOrNull()
        fun seal(key: ByteArray, o: Outbox) = NetCrypto.encrypt(key, NetCrypto.groupTopic(key), o.toJson().toByteArray(Charsets.UTF_8))
        fun open(key: ByteArray, b: ByteArray) = NetCrypto.decrypt(key, NetCrypto.groupTopic(key), b)?.let { fromJson(String(it, Charsets.UTF_8)) }
        fun topic(key: ByteArray, senderId: String) = NetCrypto.groupTopic(key) + "/o/" + senderId
        fun filter(key: ByteArray) = NetCrypto.groupTopic(key) + "/o/+"
    }
}

/** Dedupe of message ids across transports, bounded. Messages older than [maxAgeMs] or from the future are rejected. */
class MsgDedupe(private val cap: Int = 2000, private val maxAgeMs: Long = 24 * 3_600_000L, private val maxFutureMs: Long = 5 * 60_000L) {
    private val seen = LinkedHashSet<String>()
    @Synchronized fun firstTime(id: String, ts: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (ts < now - maxAgeMs || ts > now + maxFutureMs) return false
        if (!seen.add(id)) return false
        if (seen.size > cap) seen.iterator().let { it.next(); it.remove() }
        return true
    }
    @Synchronized fun markSeen(id: String) { seen.add(id) }
}
