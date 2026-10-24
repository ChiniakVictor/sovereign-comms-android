package com.victor.traillink.core

/**
 * When to centre the map on "Me" (pure logic, unit-tested).
 *  - Armed on every cold start and when the app returns from background after more than [bgResetMs].
 *  - Fires as soon as the map is ready and any own position exists: a live fix, or a last-known one < [maxAgeMs].
 *  - A last-known centre keeps it armed so the first live fix centres once more; a live-fix centre disarms it.
 *  - A real user gesture (pan/zoom/rotate by hand) disarms it, so it never fights the user.
 */
class AutoCenter(private val bgResetMs: Long = 5 * 60_000L, private val maxAgeMs: Long = 10 * 60_000L) {
    data class Pos(val lat: Double, val lon: Double, val timeMs: Long, val live: Boolean)

    var armed = true; private set
    private var centredLive = false
    private var lastTarget: Pos? = null
    private var backgroundAt: Long? = null

    fun onUserGesture() { armed = false }
    fun onBackground(now: Long) { backgroundAt = now }
    fun onForeground(now: Long): Boolean {
        val bg = backgroundAt ?: return false
        backgroundAt = null
        if (now - bg > bgResetMs) { armed = true; centredLive = false; lastTarget = null; return true }
        return false
    }

    /** Returns the position to centre on now, or null. Call whenever map readiness or positions change. */
    fun decide(mapReady: Boolean, live: Pos?, lastKnown: Pos?, now: Long): Pos? {
        if (!armed || !mapReady) return null
        val l = live?.takeIf { now - it.timeMs <= maxAgeMs }
        if (l != null) { armed = false; centredLive = true; lastTarget = l; return l }
        val k = lastKnown?.takeIf { now - it.timeMs <= maxAgeMs } ?: return null
        if (lastTarget != null) return null       // already centred on last-known; wait for the live fix
        lastTarget = k
        return k
    }
}
