package com.victor.traillink

import com.victor.traillink.core.*
import com.victor.traillink.data.TrackPoint
import com.victor.traillink.ui.backfillRuns
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackfillTest {
    private val t0 = 1_791_000_000_000L
    private fun dir(): File = Files.createTempDirectory("bf").toFile()
    private fun pt(ts: Long, i: Int = 0, acc: Float? = 5f) = BfPoint(ts, 57.6 + i * 1e-4, -152.2 - i * 1e-4, acc, 1.3f, 30)
    private val key = NetCrypto.deriveNetKey("glacier-moose-42", 1000)

    /** fixes every 5 s for 15 min -> one recorded every ~45 s */
    @Test fun recordsEvery45Seconds() {
        val q = OutageQueue(dir()); q.bind("g1"); q.markTrouble(t0)
        var i = 0
        for (s in 0..900 step 5) q.offer(pt(t0 + s * 1000L, i++))
        val ts = q.points().map { it.ts }
        assertEquals(21, ts.size)                      // 0, 45, 90 ... 900 s
        ts.zipWithNext { a, b -> assertEquals(45_000L, b - a) }
        // jittery 1-s fixes: never closer than 42 s, never more than 46 s apart
        val q2 = OutageQueue(dir()); q2.bind("g")
        for (s in 0..3600) q2.offer(pt(t0 + s * 1000L + (s % 3) * 137))
        q2.points().map { it.ts }.zipWithNext { a, b -> assertTrue("${b - a}", b - a in 42_000L..46_000L) }
    }

    @Test fun skipsCoarseFixes() {
        val q = OutageQueue(dir()); q.bind("g")
        assertFalse(q.offer(pt(t0, acc = 800f)))
        assertTrue(q.offer(pt(t0 + 1000, acc = 30f)))
        assertTrue(q.offer(pt(t0 + 50_000, acc = null)))
    }

    @Test fun survivesRestart() {
        val d = dir()
        val q = OutageQueue(d); q.bind("g1"); q.markTrouble(t0)
        for (k in 0 until 10) q.offer(pt(t0 + k * 45_000L, k), now = t0)
        val r = OutageQueue(d, maxAgeMs = Long.MAX_VALUE / 4)
        assertEquals(q.points(), r.points())
        assertTrue(r.troubled); assertEquals(t0, r.troubleAt); assertEquals("g1", r.group)
        // a torn last line (process killed mid-write) is skipped, not fatal
        File(d, "backfill-queue.txt").appendText("1791000999000,57.6")
        assertEquals(10, OutageQueue(d, maxAgeMs = Long.MAX_VALUE / 4).size())
    }

    @Test fun capThinsOldestHalfAndDropsOld() {
        val q = OutageQueue(dir(), cap = 100, maxAgeMs = Long.MAX_VALUE / 4); q.bind("g")
        for (k in 0 until 1000) q.offer(pt(t0 + k * 45_000L, k % 50))
        assertTrue(q.size() <= 100); assertTrue(q.size() >= 50)
        assertEquals(t0 + 999 * 45_000L, q.points().last().ts)   // newest always kept
        assertTrue(q.points().first().ts < t0 + 500 * 45_000L)  // the start of a long outage is thinned, not cut
        val q2 = OutageQueue(dir()); q2.bind("g")
        q2.offer(pt(t0)); q2.offer(pt(t0 + 49 * 3_600_000L))
        assertEquals(1, q2.size())                              // > 48 h old dropped
        assertEquals(4000, OutageQueue.CAP)
    }

    @Test fun healthyAckDropsPointsTroubleKeepsThemUntilBackfillConfirmed() {
        val q = OutageQueue(dir()); q.bind("g")
        q.offer(pt(t0)); q.onLiveAck(t0 + 1000)
        assertEquals(0, q.size())                               // in service: nothing piles up
        // out of service for 10 min
        q.offer(pt(t0 + 45_000)); q.markTrouble(t0 + 60_000)
        for (k in 2..14) q.offer(pt(t0 + k * 45_000L, k))
        assertEquals(14, q.size()); assertEquals(0, q.pendingCount())
        q.onLiveAck(t0 + 30_000)                                // late PUBACK for a position from before the trouble: still out
        assertTrue(q.troubled); assertEquals(14, q.size())
        q.onLiveAck(t0 + 15 * 45_000L)                          // first confirmed live position after reconnect
        assertFalse(q.troubled); assertEquals(14, q.pendingCount())
        q.offer(pt(t0 + 16 * 45_000L, 16))                      // keeps recording meanwhile
        val b1 = q.nextBatch(max = 10); assertEquals(10, b1.size); assertEquals(t0 + 45_000, b1.first().ts)
        assertEquals(15, q.size())                              // nothing removed before confirmation
        q.confirmSent(b1); assertEquals(4, q.pendingCount()); assertEquals(1, q.slot)
        q.onLiveAck(t0 + 17 * 45_000L)                          // healthy ack must not eat the unsent backfill
        assertEquals(4, q.pendingCount()); assertEquals(4, q.size())
        q.confirmSent(q.nextBatch()); assertEquals(0, q.size())
    }

    @Test fun clearAndNewGroupDiscard() {
        val q = OutageQueue(dir()); q.bind("g1"); q.markTrouble(t0)
        q.offer(pt(t0)); q.bind("g1"); assertEquals(1, q.size())
        q.bind("g2"); assertEquals(0, q.size()); assertFalse(q.troubled)
        q.offer(pt(t0)); q.clear(); assertEquals(0, q.size())
    }

    @Test fun wireRoundTripCompressedEncrypted() {
        val pts = (0 until 500).map { BfPoint(t0 + it * 45_000L, 57.612345 + it * 1e-5, -152.234567 - it * 2e-5, 4f + it % 7, 1.4f, 20 + it % 9) }
        val b = Backfill("a1b2c3d4", "Victor", "VIC", 42, pts)
        val wire = BackfillWire.seal(key, b)
        assertFalse(String(wire, Charsets.ISO_8859_1).contains("Victor"))
        assertTrue("500 pts should be small: ${wire.size}", wire.size < 12_000)
        val back = BackfillWire.open(key, wire)!!
        assertEquals(b.id, back.id); assertEquals(b.name, back.name); assertEquals(42, back.seq); assertEquals(500, back.points.size)
        back.points.zip(pts).forEach { (x, y) ->
            assertEquals(y.ts, x.ts); assertEquals(y.lat, x.lat, 1e-6); assertEquals(y.lon, x.lon, 1e-6)
            assertEquals(y.acc!!, x.acc!!, 0.5f); assertEquals(y.spd!!, x.spd!!, 0.05f); assertEquals(y.alt, x.alt)
        }
        assertNull(BackfillWire.open(NetCrypto.deriveNetKey("other-pass", 1000), wire))     // other group
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 1).toByte()
        assertNull(BackfillWire.open(key, wire))                                             // tampered
        assertEquals("b/a1b2c3d4/3", BackfillWire.sub("a1b2c3d4", 3))
    }

    @Test fun nullFieldsAndJunkTolerated() {
        val b = Backfill("x", "", "", 1, listOf(BfPoint(t0, 1.0, 2.0)))
        assertNull(BackfillWire.open(key, BackfillWire.seal(key, b))!!.points[0].acc)
        assertNull(BackfillWire.fromJson("""{"v":2,"k":"bf"}"""))   // future version ignored
        assertNull(BackfillWire.fromJson("not json"))
        assertNull(BackfillWire.inflate(ByteArray(40) { 7 }))
        // zip bomb guard
        assertNull(BackfillWire.inflate(BackfillWire.deflate(ByteArray(1_000_000))))
    }

    /** Old app versions must not misread or crash on a backfill. */
    @Test fun oldVersionTolerance() {
        val wire = BackfillWire.seal(key, Backfill("a1b2c3d4", "V", "V", 1, listOf(pt(t0))))
        assertNull(NetCrypto.open(key, wire))                          // 2.4 position parser: different AAD -> null, no throw
        assertNull(Outbox.open(key, wire))                             // 2.4 message outbox parser
        assertNull(BackfillWire.open(key, NetCrypto.seal(key, NetPosition("a", "n", "s", 1.0, 2.0, ts = t0, seq = 1))))
        // 2.4 / Windows 1.2 subscriptions never match the backfill topic (MQTT '+' is exactly one level)
        val g = NetCrypto.groupTopic(key)
        val topic = "$g/" + BackfillWire.sub("a1b2c3d4", 0)
        val old = listOf("$g/+", "$g/o/+", "$g/vc/+/+", "$g/v/+", "$g/p/+/+", "$g/c/a1b2c3d4/+", "$g/c/ffff0000/+")
        old.forEach { assertFalse(it, mqttMatch(it, topic)) }
        assertTrue(mqttMatch("$g/b/+/+", topic))
    }

    private fun mqttMatch(filter: String, topic: String): Boolean {
        val f = filter.split('/'); val t = topic.split('/')
        if (f.size != t.size) return false
        return f.indices.all { f[it] == "+" || f[it] == t[it] }
    }

    @Test fun mergeOrdersDedupesAndFilters() {
        val now = t0 + 3_600_000L
        val existing = listOf(t0, t0 + 1_200_000L)                       // live points before and after a 20 min outage
        val incoming = (0..27).map { pt(t0 + it * 45_000L, it) }.shuffled(java.util.Random(1)) +
            pt(t0 + 90_000 + 5_000, 2) +                                 // near-duplicate
            pt(t0 + 300_000, acc = 900f) +                               // coarse
            pt(now - 49 * 3_600_000L) + pt(now + 3_600_000L)              // too old / future
        val out = BackfillMerge.select(existing, incoming, now)
        assertEquals(out.sortedBy { it.ts }, out)
        out.zipWithNext { a, b -> assertTrue(b.ts - a.ts >= BackfillMerge.DEDUPE_MS) }
        assertTrue(out.none { it.ts == t0 || kotlin.math.abs(it.ts - (t0 + 1_200_000L)) < 20_000 })
        assertEquals(26, out.size)  // 28 minus t0 and 1215 s (15 s from the 20-min live point)
        // receiving the same chunk again (retained copy) adds nothing
        assertTrue(BackfillMerge.select(existing + out.map { it.ts }, incoming, now).isEmpty())
        // implausible jump dropped
        val jump = listOf(pt(t0), BfPoint(t0 + 45_000, 58.6, -152.2, 5f))
        assertEquals(1, BackfillMerge.select(emptyList(), jump, now).size)
    }

    @Test fun backfilledStretchesAreSeparateRuns() {
        fun tp(t: Long, bf: Boolean) = TrackPoint(nodeNum = 1, time = t, lat = 57.0 + t * 1e-6, lon = -152.0, bf = bf)
        val seg = listOf(tp(0, false), tp(30, false), tp(75, true), tp(120, true), tp(165, false), tp(195, false))
        val runs = backfillRuns(seg)
        assertEquals(listOf(false, true, false), runs.map { it.first })
        assertEquals(listOf(0L, 30L), runs[0].second.map { it.time })
        assertEquals(listOf(30L, 75L, 120L, 165L), runs[1].second.map { it.time })   // joins both live ends
        assertEquals(listOf(165L, 195L), runs[2].second.map { it.time })
        assertEquals(1, backfillRuns(seg.map { it.copy(bf = false) }).size)
    }

    /** Same bytes as Windows 1.3 (sovcomms/wire.py seal_backfill): Python-sealed fixture opens here, and the
     *  Android-sealed blob is written for tests/test_backfill.py::test_android_fixture. */
    @Test fun crossCompatibleWithWindows() {
        val k = NetCrypto.deriveNetKey("test-passcode-123")
        val hex = "544c3199a4b6e491fded92143b377e5b4e4bfd4e07ef1005fc030049fb8093b8e85343499319b4c6a89b669b86bc04b0e8278e6db8a51a3542859f7696b57f25e64cfa7f8df68b8b30f1df4ce2448835e28f9e8a78bfcc421d4e10e89f982306afeee93f923e3f348b4ab8dadfaba5ffb1ea82498a37e79599845e63effc9ea3918a8a469fa73a4909c93761375527147d1a538920257a35425beb08f29640b13cf9a77ad24ad035"
        val blob = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val b = BackfillWire.open(k, blob)!!
        assertEquals("pc0fa11e", b.id); assertEquals("Home base", b.name); assertEquals(7L, b.seq); assertEquals(3, b.points.size)
        assertEquals(1800000045000L, b.points[1].ts); assertEquals(57.612345, b.points[0].lat, 1e-9); assertEquals(-152.303, b.points[2].lon, 1e-9)
        assertEquals(8f, b.points[0].acc); assertEquals(1.5f, b.points[0].spd!!, 1e-4f); assertEquals(31, b.points[0].alt); assertNull(b.points[1].acc)
        assertEquals(-2, b.points[2].alt)
        val mine = Backfill("ab12cd34", "Phone", "PH", 4, listOf(BfPoint(1800000000000, 57.6, -152.3, 5f, 2.5f, 12), BfPoint(1800000046000, 57.601, -152.299)))
        File("build").mkdirs(); File("build/backfill-android-fixture.hex").writeText(BackfillWire.seal(k, mine).joinToString("") { "%02x".format(it) })
    }
}
