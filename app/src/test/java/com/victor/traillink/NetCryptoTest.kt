package com.victor.traillink

import com.victor.traillink.core.NetCrypto
import com.victor.traillink.core.NetPosition
import com.victor.traillink.core.Psk
import com.victor.traillink.core.ReplayGuard
import org.junit.Assert.*
import org.junit.Test

class NetCryptoTest {
    private val pass = "glacier-moose-42"
    private val key = NetCrypto.deriveNetKey(pass)
    private val now = 1_791_000_000_000L
    private fun pos(ts: Long = now, seq: Long = 1, id: String = "a1b2c3d4", node: Long? = null) =
        NetPosition(id, "Victor", "VIC", 61.0868, -149.6607, alt = 1070, acc = 4.5f, speed = 1.2f, heading = 135f, battery = 80, ts = ts, seq = seq,
            src = if (node != null) NetPosition.SRC_LORA else NetPosition.SRC_PHONE, node = node)

    @Test fun keyIsSeparateFromLoraPskButSamePasscode() {
        assertEquals(32, key.size)
        assertFalse(key.contentEquals(Psk.derive(pass)))
        assertArrayEquals(key, NetCrypto.deriveNetKey(" $pass "))
        assertEquals("e38301e31e6c800193ade21123fec1d09c8a37a2a46e452faab78dd010d6ef26", key.joinToString("") { "%02x".format(it) })
    }

    @Test fun topicIsHashedAndStable() {
        val t = NetCrypto.groupTopic(key)
        assertTrue(t, Regex("^traillink/v1/[0-9a-f]{16}$").matches(t))
        assertEquals(NetCrypto.groupTopic(key), NetCrypto.groupTopic(NetCrypto.deriveNetKey(pass)))
        assertNotEquals(t, NetCrypto.groupTopic(NetCrypto.deriveNetKey("other-passcode")))
        assertFalse("topic must not contain the channel name", t.contains(Psk.channelName(pass).lowercase()))
        assertEquals("$t/+", NetCrypto.subscribeFilter(key))
    }

    @Test fun sealOpenRoundTrip() {
        val p = pos(node = 0xA1B2C3D4L)
        val wire = NetCrypto.seal(key, p)
        assertEquals('T'.code.toByte(), wire[0])
        assertFalse("plaintext must not leak", String(wire, Charsets.ISO_8859_1).contains("Victor"))
        assertEquals(p, NetCrypto.open(key, wire))
        // two seals of the same message differ (random nonce)
        assertFalse(wire.contentEquals(NetCrypto.seal(key, p)))
    }

    @Test fun wrongKeyTamperAndGarbageRejected() {
        val wire = NetCrypto.seal(key, pos())
        assertNull(NetCrypto.open(NetCrypto.deriveNetKey("wrong-passcode"), wire))
        val t = wire.copyOf(); t[t.size - 5] = (t[t.size - 5].toInt() xor 1).toByte()
        assertNull(NetCrypto.open(key, t))
        val n = wire.copyOf(); n[5] = (n[5].toInt() xor 1).toByte()
        assertNull(NetCrypto.open(key, n))
        assertNull(NetCrypto.open(key, ByteArray(0)))
        assertNull(NetCrypto.open(key, "hello world, not ours at all".toByteArray()))
        // ciphertext moved to another group topic fails (AAD binds the topic)
        assertNull(NetCrypto.decrypt(key, "traillink/v1/0000000000000000", wire))
    }

    @Test fun replayGuard() {
        val g = ReplayGuard()
        assertEquals(ReplayGuard.Verdict.OK, g.check(pos(now, 1), now))
        assertEquals(ReplayGuard.Verdict.REPLAY, g.check(pos(now, 1), now))          // exact replay
        assertEquals(ReplayGuard.Verdict.REPLAY, g.check(pos(now - 1000, 9), now))   // older timestamp
        assertEquals(ReplayGuard.Verdict.OK, g.check(pos(now, 2), now))              // same ts, higher seq
        assertEquals(ReplayGuard.Verdict.OK, g.check(pos(now + 30_000, 3), now + 30_000))
        assertEquals(ReplayGuard.Verdict.STALE, g.check(pos(now - 13 * 3_600_000L, 1, id = "other"), now))
        assertEquals(ReplayGuard.Verdict.FUTURE, g.check(pos(now + 10 * 60_000L, 1, id = "other"), now))
        // streams are independent: a gateway's forwarded node vs the gateway's own phone
        assertEquals(ReplayGuard.Verdict.OK, g.check(pos(now, 1, node = 0x1234), now))
    }

    @Test fun replayedWireMessageRejectedEndToEnd() {
        val g = ReplayGuard()
        val wire = NetCrypto.seal(key, pos(now, 7))
        assertEquals(ReplayGuard.Verdict.OK, g.check(NetCrypto.open(key, wire)!!, now))
        assertEquals(ReplayGuard.Verdict.REPLAY, g.check(NetCrypto.open(key, wire)!!, now + 60_000))
    }
}
