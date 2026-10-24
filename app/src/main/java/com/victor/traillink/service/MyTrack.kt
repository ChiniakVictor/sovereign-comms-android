package com.victor.traillink.service

import android.content.Context
import com.victor.traillink.core.Backtrack
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.TrackPoint
import com.victor.traillink.data.TrailDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 2.2: records this phone's own track (for Backtrack), thinned to a point every ~15 m (or 30 s and 5 m).
 * Stored in the points table with nodeNum = -1, so it never shows up as a partner and isn't shared.
 * Recording does not depend on whether tracks are shown on the map.
 */
object MyTrack {
    const val NUM = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null
    @Volatile private var lastTime = 0L
    @Volatile private var loaded = false

    @Synchronized
    fun onFix(ctx: Context, lat: Double, lon: Double, accM: Float?, altM: Int?, time: Long = System.currentTimeMillis()) {
        if (!Prefs.settings.value.recordMyTrack) return
        if (!loaded) {   // continue from the last stored point after a restart
            loaded = true
            scope.launch {
                TrailDb.get(ctx).dao().lastPoint(NUM)?.let { synchronized(this@MyTrack) { if (lastTime == 0L) { lastLat = it.lat; lastLon = it.lon; lastTime = it.time } } }
            }
        }
        if (!Backtrack.shouldRecord(lastLat, lastLon, lastTime, lat, lon, time, accM)) return
        lastLat = lat; lastLon = lon; lastTime = time
        val app = ctx.applicationContext
        scope.launch { TrailDb.get(app).dao().insert(TrackPoint(nodeNum = NUM, time = time, lat = lat, lon = lon, altM = altM)) }
    }

    fun forget() { synchronized(this) { lastLat = null; lastLon = null; lastTime = 0L } }
}

/** 2.2: active backtrack (route newest-first for my track, or along a partner's track toward them). */
data class BacktrackRoute(val title: String, val points: List<Pair<Double, Double>>, val idx: Int = 0, val partner: Long? = null)

object BacktrackState {
    val route = MutableStateFlow<BacktrackRoute?>(null)
    fun start(r: BacktrackRoute) { route.value = r; LinkRepo.log("Backtrack: ${r.title}, ${r.points.size} waypoints") }
    fun stop() { if (route.value != null) LinkRepo.log("Backtrack: stopped"); route.value = null }
}
