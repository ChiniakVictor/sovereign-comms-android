package com.victor.traillink.core

import org.json.JSONObject
import org.meshtastic.proto.MeshProtos

enum class PinKind(val label: String, val emoji: String) {
    MEETUP("Meet-up", "📍"), CAMP("Camp", "⛺"), HAZARD("Hazard", "⚠"), GAME("Game/Animal", "🦌"),
    WATER("Water", "💧"), VEHICLE("Vehicle", "🚙"), CACHE("Cache", "📦"), OTHER("Other", "📌");
    val codepoint: Int get() = emoji.codePointAt(0)
    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER
        fun fromCodepoint(cp: Int) = entries.firstOrNull { it.codepoint == cp }
    }
}

val PIN_COLORS = listOf(0xFFFF5252, 0xFFFFB300, 0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB, 0xFFFFFFFF, 0xFFFF6E40, 0xFF8D6E63).map { it.toInt() }

/** A pin as carried on the wire (internet JSON / LoRa Waypoint). */
data class PinMsg(
    val id: String, val name: String, val kind: String, val color: Int, val note: String,
    val lat: Double, val lon: Double, val alt: Int?, val by: String, val byName: String,
    val created: Long, val updated: Long, val expires: Long, val deleted: Boolean = false, val emoji: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("k", "pin"); put("id", id); put("name", name); put("kind", kind); put("color", color); put("note", note)
        put("lat", lat); put("lon", lon); alt?.let { put("alt", it) }; put("by", by); put("byName", byName)
        put("created", created); put("ts", updated); put("exp", expires); if (deleted) put("del", true); emoji?.let { put("emoji", it) }
    }.toString()

    companion object {
        const val NAME_MAX = 30; const val NOTE_MAX = 100
        fun newId(): String = "%08x".format(kotlin.random.Random.nextInt() and 0x7fffffff or 0x10000)
        fun fromJson(s: String): PinMsg? = runCatching {
            val o = JSONObject(s)
            if (o.optInt("v") != 1 || o.optString("k") != "pin") return null
            PinMsg(o.getString("id").take(8), o.optString("name").take(NAME_MAX), o.optString("kind", "OTHER"), o.optInt("color", PIN_COLORS[0]),
                o.optString("note").take(NOTE_MAX), o.getDouble("lat"), o.getDouble("lon"), if (o.has("alt")) o.getInt("alt") else null,
                o.getString("by").take(40), o.optString("byName").take(40), o.getLong("created"), o.getLong("ts"), o.optLong("exp"),
                o.optBoolean("del"), if (o.has("emoji")) o.getString("emoji").take(4) else null)
                .takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        }.getOrNull()
        fun seal(key: ByteArray, p: PinMsg) = NetCrypto.encrypt(key, NetCrypto.groupTopic(key), p.toJson().toByteArray(Charsets.UTF_8))
        fun open(key: ByteArray, b: ByteArray) = NetCrypto.decrypt(key, NetCrypto.groupTopic(key), b)?.let { fromJson(String(it, Charsets.UTF_8)) }
        fun topic(senderId: String, pinId: String) = "p/$senderId/$pinId"
    }
}

/** Meshtastic WAYPOINT_APP mapping, compatible with stock apps (expire = 1 means delete). */
object WaypointCodec {
    fun encode(p: PinMsg, lockedTo: Long = 0): MeshProtos.Waypoint = MeshProtos.Waypoint.newBuilder()
        .setId(p.id.toLong(16).toInt())
        .setLatitudeI(Math.round(p.lat * 1e7).toInt()).setLongitudeI(Math.round(p.lon * 1e7).toInt())
        .setExpire(when { p.deleted -> 1; p.expires > 0 -> (p.expires / 1000).toInt(); else -> 0 })
        .setLockedTo(lockedTo.toInt())
        .setName(ChatMsg.clip(p.name, PinMsg.NAME_MAX)).setDescription(ChatMsg.clip(p.note, PinMsg.NOTE_MAX))
        .setIcon(p.emoji?.codePointAt(0) ?: PinKind.of(p.kind).codepoint)
        .build()

    /** [from] = sender node number. Colour isn't on the wire: derived from the id so all phones agree. */
    fun decode(w: MeshProtos.Waypoint, from: Long, fromName: String, now: Long): PinMsg {
        val id = "%08x".format(w.id)
        val kind = PinKind.fromCodepoint(w.icon)
        val exp = (w.expire.toLong() and 0xFFFFFFFFL)
        return PinMsg(id, w.name.ifBlank { "Waypoint" }, (kind ?: PinKind.OTHER).name, PIN_COLORS[(w.id and 0x7fffffff) % PIN_COLORS.size],
            w.description, w.latitudeI / 1e7, w.longitudeI / 1e7, null, "l:$from", fromName, now, now,
            if (exp > 1) exp * 1000 else 0, deleted = exp == 1L || (exp > 1 && exp * 1000 < now),
            emoji = if (kind == null && w.icon != 0) String(Character.toChars(w.icon)) else null)
    }
}
