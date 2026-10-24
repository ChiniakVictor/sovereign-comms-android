package com.victor.traillink

import com.victor.traillink.core.AutoCenter
import com.victor.traillink.core.AutoCenter.Pos
import org.junit.Assert.*
import org.junit.Test

class AutoCenterTest {
    private val t0 = 1_791_000_000_000L
    private val live = Pos(61.2, -149.9, t0, true)
    private val lk = Pos(61.1, -149.8, t0 - 3 * 60_000L, false)

    @Test fun waitsForMapReady() {
        val a = AutoCenter()
        assertNull(a.decide(false, live, lk, t0))
        assertEquals(live, a.decide(true, live, lk, t0))
        assertNull("only once", a.decide(true, live.copy(timeMs = t0 + 1000), lk, t0 + 1000))
    }

    @Test fun lastKnownThenLiveFix() {
        val a = AutoCenter()
        assertNull(a.decide(true, null, null, t0))                   // nothing yet
        assertEquals(lk, a.decide(true, null, lk, t0))               // recent last-known
        assertNull(a.decide(true, null, lk, t0 + 500))               // don't repeat
        assertEquals(live, a.decide(true, live, lk, t0 + 2000))      // live fix: centre once more
        assertNull(a.decide(true, live, lk, t0 + 3000))
    }

    @Test fun staleLastKnownIgnored() {
        val a = AutoCenter()
        assertNull(a.decide(true, null, lk.copy(timeMs = t0 - 11 * 60_000L), t0))
        assertTrue(a.armed)
    }

    @Test fun userGestureDisarms() {
        val a = AutoCenter()
        assertEquals(lk, a.decide(true, null, lk, t0))
        a.onUserGesture()
        assertNull(a.decide(true, live, lk, t0 + 1000))
    }

    @Test fun backgroundOverFiveMinutesRearms() {
        val a = AutoCenter()
        a.decide(true, live, null, t0)
        a.onBackground(t0); assertFalse(a.onForeground(t0 + 4 * 60_000L))
        assertNull(a.decide(true, live, null, t0 + 4 * 60_000L))
        a.onBackground(t0 + 5 * 60_000L); assertTrue(a.onForeground(t0 + 11 * 60_000L))
        val p = live.copy(timeMs = t0 + 11 * 60_000L)
        assertEquals(p, a.decide(true, p, null, t0 + 11 * 60_000L))
        a.onUserGesture(); a.onBackground(t0 + 12 * 60_000L); a.onForeground(t0 + 20 * 60_000L)
        assertTrue("re-armed even after an earlier gesture", a.armed)
    }
}
