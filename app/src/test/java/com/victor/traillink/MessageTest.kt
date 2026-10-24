package com.victor.traillink

import com.victor.traillink.core.*
import org.junit.Assert.*
import org.junit.Test

class MessageTest {
    private val key = NetCrypto.deriveNetKey("glacier-moose-42", iterations = 1000)
    private val now = 1_791_000_000_000L
    private fun msg(id: String = ChatMsg.newId(), ts: Long = now, to: String? = null) = ChatMsg(id, "n:a1b2c3d4", "Victor", to, "On my way 🥾", ts)

    @Test fun outboxRoundTripEncrypted() {
        val ob = Outbox("n:a1b2c3d4", listOf(msg(), msg(to = "n:ffff0000"), msg().copy(sos = true, lat = 61.1, lon = -149.7)),
            listOf(Receipt("0abc1234", "n:a1b2c3d4", "r", now)), now)
        val wire = Outbox.seal(key, ob)
        assertFalse(String(wire, Charsets.ISO_8859_1).contains("On my way"))
        assertEquals(ob, Outbox.open(key, wire))
        assertNull(Outbox.open(NetCrypto.deriveNetKey("other-passcode", iterations = 1000), wire))
        val t = wire.copyOf(); t[t.size - 3] = (t[t.size - 3].toInt() xor 4).toByte()
        assertNull(Outbox.open(key, t))
        assertTrue(Outbox.topic(key, "a1b2c3d4").matches(Regex("traillink/v1/[0-9a-f]{16}/o/a1b2c3d4")))
    }

    @Test fun outboxPrunes() {
        val old = msg(ts = now - 25 * 3_600_000L)
        val many = (1..40).map { msg(ts = now - it * 1000L) }
        val p = Outbox("n:x", many + old, emptyList(), now).pruned(now)
        assertEquals(30, p.msgs.size)
        assertFalse(p.msgs.contains(old))
        assertEquals(now - 1000L, p.msgs.last().ts)
    }

    @Test fun dedupeAcrossTransports() {
        val d = MsgDedupe()
        val id = "0abc1234"
        assertTrue(d.firstTime(id, now, now))          // via LoRa
        assertFalse(d.firstTime(id, now, now))         // same id via internet
        assertFalse(d.firstTime("11111111", now - 25 * 3_600_000L, now))   // too old
        assertFalse(d.firstTime("22222222", now + 10 * 60_000L, now))      // future
        val small = MsgDedupe(cap = 3)
        listOf("a", "b", "c", "d").forEach { assertTrue(small.firstTime(it, now, now)) }
        assertTrue("evicted ids are accepted again", small.firstTime("a", now, now))
    }

    @Test fun clipUtf8() {
        val s = "é".repeat(150)                  // 300 bytes
        val c = ChatMsg.clip(s)
        assertTrue(c.toByteArray().size <= 200); assertEquals(100, c.length)
        val e = "🥾".repeat(60)                  // 4 bytes each, surrogate pairs
        val ce = ChatMsg.clip(e)
        assertTrue(ce.toByteArray().size <= 200); assertEquals(0, ce.length % 2)
        assertEquals("ok", ChatMsg.clip("ok"))
    }

    @Test fun idsAre8Hex() { repeat(100) { assertTrue(ChatMsg.newId().matches(Regex("[0-9a-f]{8}"))) } }
}
