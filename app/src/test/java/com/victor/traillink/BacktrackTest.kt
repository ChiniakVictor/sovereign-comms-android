package com.victor.traillink

import com.victor.traillink.core.Backtrack
import org.junit.Assert.*
import org.junit.Test

class BacktrackTest {
    private val track = (0 until 100).map { 57.6 + it * 0.0001 to -152.3 }   // ~11 m apart, heading north

    @Test fun reverseRouteEndsAtStart() {
        val r = Backtrack.reverseRoute(track)
        assertEquals(track.last(), r.first()); assertEquals(track.first(), r.last())
        assertTrue(r.size in 40..60)
    }

    @Test fun progressFromNewestPoint() {
        val r = Backtrack.reverseRoute(track)
        val p = Backtrack.progress(r, 0, track.last().first, track.last().second)
        assertTrue(p.remainingM in 1000.0..1150.0)
        assertEquals(180.0, p.bearingDeg, 1.0)
    }

    @Test fun arriveAtStart() {
        val r = Backtrack.reverseRoute(track)
        val p = Backtrack.progress(r, r.size - 1, track.first().first, track.first().second)
        assertEquals(r.size, p.idx)
    }

    @Test fun pickedPoint() {
        val r = Backtrack.reverseRoute(track, 50)
        assertEquals(track[50], r.last())
    }

    @Test fun recordingThinning() {
        assertTrue(Backtrack.shouldRecord(null, null, 0, 57.6, -152.3, 0, 5f))
        assertFalse(Backtrack.shouldRecord(57.6, -152.3, 0, 57.60005, -152.3, 10_000, 5f))   // 5.6 m after 10 s
        assertTrue(Backtrack.shouldRecord(57.6, -152.3, 0, 57.60005, -152.3, 31_000, 5f))    // 5.6 m after 31 s
        assertTrue(Backtrack.shouldRecord(57.6, -152.3, 0, 57.6002, -152.3, 5_000, 5f))      // 22 m
        assertFalse(Backtrack.shouldRecord(57.6, -152.3, 0, 57.61, -152.3, 60_000, 80f))     // poor accuracy
    }
}
