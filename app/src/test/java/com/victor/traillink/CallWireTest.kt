package com.victor.traillink

import com.victor.traillink.core.CallSignal
import com.victor.traillink.core.CallWire
import org.junit.Assert.*
import org.junit.Test

class CallWireTest {
    private val key = ByteArray(32) { it.toByte() }
    @Test fun roundTrip() {
        val s = CallSignal(CallSignal.ICE, "0123456789abcdef", "a1b2c3d4", "Victor", System.currentTimeMillis(), mid = "0", idx = 0, cand = "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host")
        val o = CallWire.open(key, CallWire.seal(key, s))
        assertEquals(s, o)
    }
    @Test fun wrongKeyAndStale() {
        val s = CallSignal(CallSignal.RING, "0123456789abcdef", "a1b2c3d4", "V", System.currentTimeMillis())
        assertNull(CallWire.open(ByteArray(32), CallWire.seal(key, s)))
        val old = s.copy(ts = System.currentTimeMillis() - 10 * 60_000)
        assertNull(CallWire.open(key, CallWire.seal(key, old)))
    }
}
