package com.victor.traillink.core

/**
 * 2.2 Backtrack: follow a recorded track waypoint to waypoint. Pure logic (unit-tested); works offline with GPS only.
 * Points are (lat, lon) pairs.
 */
object Backtrack {
    const val THIN_M = 15.0          // keep a waypoint every ~15 m
    const val ARRIVE_M = 12.0        // a waypoint counts as reached within this distance
    const val LOOKAHEAD = 6          // skip ahead if a later waypoint is closer (corner cut)

    /** My own track recording: keep a fix if it moved >= 15 m, or >= 30 s passed and it moved >= 5 m. Accuracy must be <= 50 m. */
    fun shouldRecord(lastLat: Double?, lastLon: Double?, lastTime: Long, lat: Double, lon: Double, time: Long, accM: Float?): Boolean {
        if (accM != null && accM > 50f) return false
        if (lastLat == null || lastLon == null) return true
        val d = Geo.distanceM(lastLat, lastLon, lat, lon)
        return d >= THIN_M || (time - lastTime >= 30_000 && d >= 5.0)
    }

    fun thin(pts: List<Pair<Double, Double>>, minM: Double = THIN_M): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (p in pts) if (out.isEmpty() || Geo.distanceM(out.last().first, out.last().second, p.first, p.second) >= minM) out += p
        if (pts.isNotEmpty() && out.last() != pts.last()) out += pts.last()
        return out
    }

    /** Reverse route: newest point back to track[startIndex] (the start by default). Track is oldest first. */
    fun reverseRoute(track: List<Pair<Double, Double>>, startIndex: Int = 0): List<Pair<Double, Double>> =
        thin(track.drop(startIndex.coerceIn(0, (track.size - 1).coerceAtLeast(0)))).reversed()

    /** Forward route along a partner's track (oldest first) ending at their newest point, to reach them. */
    fun forwardRoute(track: List<Pair<Double, Double>>): List<Pair<Double, Double>> = thin(track)

    data class Progress(val idx: Int, val toNextM: Double, val remainingM: Double, val bearingDeg: Double) {
        val done get() = toNextM == 0.0 && remainingM == 0.0
    }

    /** Advance past reached waypoints. On the first call (idx 0) joins the route at the nearest waypoint. idx == route.size when done. */
    fun progress(route: List<Pair<Double, Double>>, idxIn: Int, lat: Double, lon: Double): Progress {
        if (route.isEmpty()) return Progress(0, 0.0, 0.0, 0.0)
        fun d(i: Int) = Geo.distanceM(lat, lon, route[i].first, route[i].second)
        var idx = idxIn
        if (idx == 0) idx = route.indices.minBy { d(it) }
        while (idx < route.size && d(idx) <= ARRIVE_M) idx++
        if (idx >= route.size) return Progress(route.size, 0.0, 0.0, 0.0)
        val best = (idx until minOf(route.size, idx + LOOKAHEAD)).minBy { d(it) }
        if (best > idx && d(best) < d(idx)) idx = best
        val toNext = d(idx)
        var rem = toNext
        for (i in idx until route.size - 1) rem += Geo.distanceM(route[i].first, route[i].second, route[i + 1].first, route[i + 1].second)
        return Progress(idx, toNext, rem, Geo.bearingDeg(lat, lon, route[idx].first, route[idx].second))
    }
}
