package com.victor.traillink.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 2.5: out-of-service track backfill.
 *
 * While internet sharing is on, the phone keeps a GPS fix every [OutageQueue.INTERVAL_MS] (45 s) in a small file-backed
 * queue. Points are dropped as soon as a live position sent after them is confirmed by the broker (PUBACK) while the
 * link was healthy, so in normal service the queue holds at most a point or two. When the link is down or sends fail
 * ("out of service") the points are kept; after the first confirmed live position on reconnect they are sent to the
 * partners as encrypted "backfill" chunks and removed only when each chunk is confirmed.
 */
data class BfPoint(val ts: Long, val lat: Double, val lon: Double, val acc: Float? = null, val spd: Float? = null, val alt: Int? = null) {
    fun line() = "$ts,$lat,$lon,${acc ?: ""},${spd ?: ""},${alt ?: ""}"
    companion object {
        fun parse(l: String): BfPoint? = runCatching {
            val f = l.split(',')
            if (f.size < 3) return null
            BfPoint(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f.getOrNull(3)?.toFloatOrNull(), f.getOrNull(4)?.toFloatOrNull(), f.getOrNull(5)?.toIntOrNull())
                .takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        }.getOrNull()
    }
}

class OutageQueue(
    private val dir: File,
    val intervalMs: Long = INTERVAL_MS,
    val cap: Int = CAP,
    val maxAgeMs: Long = MAX_AGE_MS,
) {
    companion object {
        const val INTERVAL_MS = 45_000L
        /** fixes arrive every ~5 s; accept one this much early so the average spacing stays ~45 s, not 45 + 5 */
        const val JITTER_MS = 3_000L
        const val CAP = 4000                 // 8 chunks x 500; ~50 h at 45 s (older half is thinned, not cut, when full)
        const val MAX_AGE_MS = 48 * 3_600_000L
        const val MAX_ACC_M = 100f           // coarse cell/Wi-Fi fixes aren't trail points on the receiver either
        const val ACK_TIMEOUT_MS = 45_000L   // a live position not confirmed within this = out of service
    }

    private val qf = File(dir, "backfill-queue.txt")
    private val sf = File(dir, "backfill-state.properties")
    private val pts = ArrayList<BfPoint>()
    /** a send failed / link down / ack overdue since the last confirmed live position */
    var troubled = false; private set
    /** when trouble was first noticed (a confirmation for a position taken before this doesn't end the outage) */
    var troubleAt = 0L; private set
    /** points with ts <= pendingUpTo are a finished outage waiting to be sent */
    var pendingUpTo = 0L; private set
    var lastAckTs = 0L; private set
    var group: String = ""; private set
    var slot = 0; private set

    init { load() }

    @Synchronized fun size() = pts.size
    @Synchronized fun points(): List<BfPoint> = ArrayList(pts)
    @Synchronized fun pendingCount() = pts.count { it.ts <= pendingUpTo }

    /** Binds to a sharing group; a different group (new passcode) discards the old group's points. */
    @Synchronized fun bind(groupId: String) {
        if (group == groupId) return
        if (group.isNotEmpty()) { pts.clear(); writeAll() }
        group = groupId; troubled = false; pendingUpTo = 0; saveState()
    }

    /** A fix from the location stream. Returns true if it was recorded (>= ~45 s after the previous one). */
    @Synchronized fun offer(p: BfPoint, now: Long = p.ts): Boolean {
        if (p.acc != null && p.acc > MAX_ACC_M) return false
        val last = pts.lastOrNull()
        if (last != null && p.ts - last.ts < intervalMs - JITTER_MS) return false
        if (last != null && p.ts <= last.ts) return false
        pts += p
        val trimmed = trim(now)
        if (trimmed) writeAll() else runCatching { dir.mkdirs(); qf.appendText(p.line() + "\n") }
        return true
    }

    @Synchronized fun markTrouble(now: Long = System.currentTimeMillis()) { if (!troubled) { troubled = true; troubleAt = now; saveState() } }

    /**
     * The broker confirmed a live position taken at [ts]. Healthy link: points up to [ts] were covered by live sharing
     * and are dropped. After trouble: everything up to [ts] becomes a pending backfill.
     */
    @Synchronized fun onLiveAck(ts: Long) {
        if (ts <= lastAckTs) return
        lastAckTs = ts
        if (troubled && ts >= troubleAt) { troubled = false; pendingUpTo = maxOf(pendingUpTo, ts) }
        else {   // healthy link, or a late confirmation of a position sent before the trouble started
            val before = pts.size
            pts.removeAll { it.ts in (pendingUpTo + 1)..ts }
            if (pts.size != before) writeAll()
        }
        saveState()
    }

    /** Oldest pending points (at most [max]) for the next backfill chunk. */
    @Synchronized fun nextBatch(max: Int = BackfillWire.MAX_POINTS): List<BfPoint> = pts.filter { it.ts <= pendingUpTo }.take(max)

    /** A backfill chunk was confirmed by the broker: remove exactly those points, advance the retained slot. */
    @Synchronized fun confirmSent(batch: List<BfPoint>) {
        val ts = batch.map { it.ts }.toHashSet()
        pts.removeAll { it.ts in ts }
        slot = (slot + 1) % BackfillWire.SLOTS
        writeAll(); saveState()
    }

    /** Sharing turned off: nothing recorded is ever sent. */
    @Synchronized fun clear() { pts.clear(); troubled = false; pendingUpTo = 0; writeAll(); saveState() }

    private fun trim(now: Long): Boolean {
        var changed = false
        if (pts.isNotEmpty() && pts[0].ts < now - maxAgeMs) { pts.removeAll { it.ts < now - maxAgeMs }; changed = true }
        while (pts.size > cap) {
            // thin the older half to every other point, so a long outage keeps its whole shape at 90 s spacing
            val half = pts.size / 2
            val keep = ArrayList<BfPoint>(pts.size)
            for (i in pts.indices) if (i >= half || i % 2 == 0) keep += pts[i]
            pts.clear(); pts.addAll(keep); changed = true
        }
        return changed
    }

    private fun load() {
        runCatching {
            if (sf.exists()) Properties().apply { sf.inputStream().use { load(it) } }.let { p ->
                troubled = p.getProperty("troubled") == "true"
                troubleAt = p.getProperty("troubleAt")?.toLongOrNull() ?: 0
                pendingUpTo = p.getProperty("pendingUpTo")?.toLongOrNull() ?: 0
                lastAckTs = p.getProperty("lastAckTs")?.toLongOrNull() ?: 0
                group = p.getProperty("group") ?: ""
                slot = (p.getProperty("slot")?.toIntOrNull() ?: 0).mod(BackfillWire.SLOTS)
            }
        }
        runCatching {
            if (qf.exists()) qf.readLines().mapNotNull { BfPoint.parse(it.trim()) }.sortedBy { it.ts }.forEach { p ->
                if (pts.isEmpty() || p.ts > pts.last().ts) pts += p
            }
        }
        if (trim(System.currentTimeMillis())) writeAll()
    }

    private fun writeAll() = runCatching {
        dir.mkdirs()
        val tmp = File(dir, qf.name + ".tmp")
        tmp.writeText(pts.joinToString("") { it.line() + "\n" })
        if (!tmp.renameTo(qf)) { qf.delete(); tmp.renameTo(qf) }
    }

    private fun saveState() = runCatching {
        dir.mkdirs()
        Properties().apply {
            setProperty("troubled", troubled.toString()); setProperty("troubleAt", troubleAt.toString()); setProperty("pendingUpTo", pendingUpTo.toString())
            setProperty("lastAckTs", lastAckTs.toString()); setProperty("group", group); setProperty("slot", slot.toString())
        }.let { p -> sf.outputStream().use { p.store(it, "Sovereign Comms backfill state") } }
    }
}

/** A backfill chunk: the sender's recorded out-of-service track. */
data class Backfill(val id: String, val name: String, val short: String, val seq: Long, val points: List<BfPoint>)

/**
 * Wire format (topic `<group>/b/<senderId>/<slot 0..7>`, QoS 1, retained so a partner who connects later still gets it):
 *   "TL1" | nonce | AES-256-GCM( deflate(json), aad = group topic + "/b" )
 *   json = {"v":1,"k":"bf","id","name","short","seq","t0":ms,"p":[[dtSec, latE6, lonE6, acc|null, spd*10|null, alt|null], ...]}
 * The topic has two levels below "b/", so 2.4-and-older phones and Windows 1.2 (subscribed to `<group>/+`, `o/+`,
 * `vc/+/+`, `v/+`, `p/+/+`, `c/<me>/+`) never receive it. The different AAD means a backfill can never be read as a position.
 */
object BackfillWire {
    const val VERSION = 1
    const val MAX_POINTS = 500
    const val SLOTS = 8
    const val MAX_INFLATED = 256 * 1024

    fun sub(senderId: String, slot: Int) = "b/$senderId/$slot"
    fun aad(groupTopic: String) = "$groupTopic/b"

    fun toJson(b: Backfill): String {
        val t0 = b.points.firstOrNull()?.ts ?: 0L
        return JSONObject().apply {
            put("v", VERSION); put("k", "bf"); put("id", b.id); put("name", b.name); put("short", b.short); put("seq", b.seq); put("t0", t0)
            put("p", JSONArray().apply {
                b.points.forEach { p ->
                    put(JSONArray().apply {
                        put((p.ts - t0 + 500) / 1000); put(Math.round(p.lat * 1e6)); put(Math.round(p.lon * 1e6))
                        put(p.acc?.let { Math.round(it) } ?: JSONObject.NULL); put(p.spd?.let { Math.round(it * 10) } ?: JSONObject.NULL)
                        put(p.alt ?: JSONObject.NULL)
                    })
                }
            })
        }.toString()
    }

    fun fromJson(s: String): Backfill? = runCatching {
        val o = JSONObject(s)
        if (o.optInt("v") != VERSION || o.optString("k") != "bf") return null
        val t0 = o.getLong("t0"); val a = o.getJSONArray("p")
        val pts = (0 until minOf(a.length(), MAX_POINTS * 2)).mapNotNull { i ->
            runCatching {
                val r = a.getJSONArray(i)
                BfPoint(t0 + r.getLong(0) * 1000, r.getLong(1) / 1e6, r.getLong(2) / 1e6,
                    if (r.isNull(3)) null else r.getDouble(3).toFloat(), if (r.isNull(4)) null else (r.getDouble(4) / 10).toFloat(),
                    if (r.isNull(5)) null else r.getInt(5)).takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            }.getOrNull()
        }
        Backfill(o.getString("id").take(32), o.optString("name").take(40), o.optString("short").take(6), o.optLong("seq"), pts)
    }.getOrNull()

    fun seal(netKey: ByteArray, b: Backfill): ByteArray =
        NetCrypto.encrypt(netKey, aad(NetCrypto.groupTopic(netKey)), deflate(toJson(b).toByteArray(Charsets.UTF_8)))

    fun open(netKey: ByteArray, msg: ByteArray): Backfill? =
        NetCrypto.decrypt(netKey, aad(NetCrypto.groupTopic(netKey)), msg)?.let { inflate(it) }?.let { fromJson(String(it, Charsets.UTF_8)) }

    fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    /** Null if corrupt or larger than [MAX_INFLATED] (zip-bomb guard). */
    fun inflate(b: ByteArray): ByteArray? = runCatching {
        val i = Inflater(true); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > MAX_INFLATED) { i.end(); return null }
        }
        i.end(); out.toByteArray()
    }.getOrNull()
}

/** Receiver side: which backfilled points to add to a partner's trail. Pure, so it is unit-tested. */
object BackfillMerge {
    const val DEDUPE_MS = 20_000L
    const val MAX_AGE_MS = 48 * 3_600_000L
    const val MAX_FUTURE_MS = 5 * 60_000L

    /**
     * [existing] = times (ms) of the trail points already stored for that partner. Returns the new points, sorted and
     * deduped: not within [DEDUPE_MS] of a stored point or of each other, not older than [MAX_AGE_MS], not from the
     * future, not coarse (> 100 m), and not an implausible jump from the previous accepted point.
     */
    fun select(existing: List<Long>, incoming: List<BfPoint>, now: Long, maxSpeedMps: Double = 83.0): List<BfPoint> {
        val have = existing.sorted()
        val out = ArrayList<BfPoint>()
        for (p in incoming.sortedBy { it.ts }) {
            if (p.ts < now - MAX_AGE_MS || p.ts > now + MAX_FUTURE_MS) continue
            if (p.acc != null && p.acc > OutageQueue.MAX_ACC_M) continue
            if (near(have, p.ts)) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                if (p.ts - prev.ts < DEDUPE_MS) continue
                val d = Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon)
                if (d > 200 && d / ((p.ts - prev.ts) / 1000.0) > maxSpeedMps) continue   // > ~300 km/h
            }
            out += p
        }
        return out
    }

    private fun near(sorted: List<Long>, t: Long): Boolean {
        var i = sorted.binarySearch(t); if (i >= 0) return true
        i = -i - 1
        return (i < sorted.size && sorted[i] - t < DEDUPE_MS) || (i > 0 && t - sorted[i - 1] < DEDUPE_MS)
    }
}
