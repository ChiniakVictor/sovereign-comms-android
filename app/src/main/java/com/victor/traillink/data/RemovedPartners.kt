package com.victor.traillink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Partners removed from this phone (2.1). Local only — nothing is sent to anyone.
 *
 * A removed partner stays hidden from data that is as old as, or older than, the removal (the radio's node list,
 * retained internet positions / messages / pins / voice on the broker). They come back on their own as soon as
 * something NEW arrives from them (a position, message, pin or call made after the removal).
 * A blocked partner stays hidden until unblocked in More → Partners.
 *
 * Keyed by node number: radios by their Meshtastic number, internet phones by NodeStore.netNum(installId).
 */
object RemovedPartners {
    data class Entry(val num: Long, val name: String, val at: Long, val blocked: Boolean)

    private const val FILE = "removed_partners"
    private val _list = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    val list: StateFlow<Map<Long, Entry>> get() = _list
    @Volatile private var ctx: Context? = null

    @Synchronized fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _list.value = p.all.mapNotNull { (k, v) ->
            runCatching { val o = JSONObject(v as String); Entry(k.toLong(), o.optString("name"), o.getLong("at"), o.optBoolean("blocked")) }.getOrNull()
        }.associateBy { it.num }
    }

    /** Node number for a chat/pin key ("n:<installId>" or "l:<nodeNum>"); null for "group", "me" etc. */
    fun numForKey(key: String?): Long? = when {
        key == null -> null
        key.startsWith("n:") -> com.victor.traillink.service.NodeStore.netNum(key.removePrefix("n:"))
        key.startsWith("l:") -> key.removePrefix("l:").toLongOrNull()
        else -> null
    }

    fun isBlocked(num: Long?) = num != null && _list.value[num]?.blocked == true

    /** True when data from [num] stamped [ts] should be ignored. */
    fun hides(num: Long?, ts: Long): Boolean {
        val e = num?.let { _list.value[it] } ?: return false
        return e.blocked || ts <= e.at
    }
    fun hidesKey(key: String?, ts: Long) = hides(numForKey(key), ts)

    /** Something new arrived from [num]: an un-blocked removed partner comes back. Returns false if blocked. */
    fun active(num: Long?, ts: Long = System.currentTimeMillis()): Boolean {
        val e = num?.let { _list.value[it] } ?: return true
        if (e.blocked) return false
        if (ts > e.at) drop(e.num)
        return ts > e.at
    }

    @Synchronized fun add(num: Long, name: String, blocked: Boolean, at: Long = System.currentTimeMillis()) {
        val e = Entry(num, name, at, blocked)
        _list.value = _list.value + (num to e)
        ctx?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putString(num.toString(), JSONObject().put("name", name).put("at", at).put("blocked", blocked).toString())?.apply()
    }

    /** Unblock: they show again the next time something new arrives from them. */
    @Synchronized fun unblock(num: Long) { _list.value[num]?.let { add(num, it.name, blocked = false, at = System.currentTimeMillis()) } }

    @Synchronized private fun drop(num: Long) {
        _list.value = _list.value - num
        ctx?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.remove(num.toString())?.apply()
    }
}
