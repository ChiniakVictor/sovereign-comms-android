package com.victor.traillink

import com.victor.traillink.core.Range
import org.junit.Assert.*
import org.junit.Test

class RangeTest {
    private val h2 = 2 * 3_600_000L

    @Test fun startsAtAccuracyAndGrowsAtPace() {
        assertEquals(8.0, Range.radiusM(8.0, 0, 1.34, h2), 1e-9)
        assertEquals(8.0 + 1.34 * 600, Range.radiusM(8.0, 600_000, 1.34, h2), 1e-6)   // 10 min walking ≈ 812 m
        assertEquals(Range.DEFAULT_ACC_M, Range.radiusM(null, 0, 1.34, h2), 1e-9)
        assertEquals(Range.DEFAULT_ACC_M, Range.radiusM(-1.0, -5000, 1.34, h2), 1e-9)  // clock skew: no negative growth
    }

    @Test fun capsAtMaxAge() {
        val atCap = Range.radiusM(10.0, h2, 1.34, h2)
        assertEquals(10.0 + 1.34 * 7200, atCap, 1e-6)
        assertEquals(atCap, Range.radiusM(10.0, 5 * 3_600_000L, 1.34, h2), 1e-9)
        assertTrue(Range.capped(h2, h2)); assertFalse(Range.capped(h2 - 1, h2))
        assertTrue(Range.label(atCap, 3 * 3_600_000L, h2, false).endsWith("last seen 2h+"))
        assertFalse(Range.label(100.0, 60_000, h2, false).contains("last seen"))
    }

    @Test fun reportedSpeedOnlyWhenHigherAndFresh() {
        assertEquals(1.34, Range.speedMps(1.34, 0.5, 1000), 1e-9)
        assertEquals(5.0, Range.speedMps(1.34, 5.0, 60_000), 1e-9)
        assertEquals(1.34, Range.speedMps(1.34, 5.0, 11 * 60_000L), 1e-9)   // stale
        assertEquals(1.34, Range.speedMps(1.34, 500.0, 1000), 1e-9)         // junk
        assertEquals(1.34, Range.speedMps(1.34, null, null), 1e-9)
    }

    @Test fun derivedSpeedFromTrail() {
        val a = Triple(0L, 61.0, -149.0); val b = Triple(100_000L, 61.001, -149.0)   // ~111 m in 100 s
        assertEquals(1.11, Range.derivedSpeed(a, b)!!, 0.01)
        assertNull(Range.derivedSpeed(a, Triple(2000L, 61.001, -149.0)))           // too close in time
        assertNull(Range.derivedSpeed(null, b))
    }

    @Test fun opacityFades() {
        assertTrue(Range.fillOpacity(0, h2) > Range.fillOpacity(h2 / 2, h2))
        assertEquals(0.05f, Range.fillOpacity(h2 * 3, h2), 1e-6f)
        assertTrue(Range.lineOpacity(h2, h2) >= 0.35f - 1e-6f)
    }
}
