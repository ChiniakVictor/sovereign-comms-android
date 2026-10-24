package com.victor.traillink.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.victor.traillink.core.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import java.io.File

data class RegionInfo(
    val id: Long, val name: String, val minZoom: Int, val maxZoom: Int, val bounds: LatLngBounds?,
    val completed: Long = 0, val required: Long = 0, val bytes: Long = 0, val complete: Boolean = false,
    val active: Boolean = false, val error: String? = null,
)

data class MbtilesInfo(val file: File, val name: String, val format: String, val minZoom: Int?, val maxZoom: Int?, val sizeBytes: Long)

object OfflineMaps {
    const val STYLE_URL = "asset://usgs-style.json"
    const val MAX_ZOOM = 16
    const val MAX_TILES_PER_REGION = 25_000L   // keep downloads from the public USGS server reasonable
    const val MAX_TILES_TOTAL = 100_000L

    private val _regions = MutableStateFlow<List<RegionInfo>>(emptyList())
    val regions: StateFlow<List<RegionInfo>> get() = _regions
    private val _mbtiles = MutableStateFlow<List<MbtilesInfo>>(emptyList())
    val mbtiles: StateFlow<List<MbtilesInfo>> get() = _mbtiles
    private val live = mutableMapOf<Long, OfflineRegion>()

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun estimate(b: LatLngBounds, minZ: Int, maxZ: Int) = Geo.tileCount(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, minZ, maxZ)

    fun refresh(ctx: Context) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                list.forEach { r -> if (!live.containsKey(r.id)) { live[r.id] = r; observe(r) } }
                _regions.value = list.map { r -> info(r, _regions.value.firstOrNull { it.id == r.id }) }
                list.forEach { r -> r.getStatus(statusCb(r)) }
            }
            override fun onError(error: String) {}
        })
        refreshMbtiles(ctx)
    }

    private fun info(r: OfflineRegion, old: RegionInfo?): RegionInfo {
        val def = r.definition as? OfflineTilePyramidRegionDefinition
        val name = runCatching { JSONObject(String(r.metadata)).optString("name", "Area ${r.id}") }.getOrDefault("Area ${r.id}")
        return (old ?: RegionInfo(r.id, name, def?.minZoom?.toInt() ?: 0, def?.maxZoom?.toInt() ?: 0, def?.bounds)).copy(name = name)
    }

    private fun statusCb(r: OfflineRegion) = object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) { status?.let { apply(r.id, it) } }
        override fun onError(error: String?) {}
    }

    private fun apply(id: Long, s: OfflineRegionStatus) = _regions.update { l ->
        l.map { if (it.id == id) it.copy(completed = s.completedResourceCount, required = s.requiredResourceCount,
            bytes = s.completedResourceSize, complete = s.isComplete,
            active = s.downloadState == OfflineRegion.STATE_ACTIVE && !s.isComplete) else it }
    }

    private fun observe(r: OfflineRegion) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                apply(r.id, status)
                if (status.isComplete) r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
            override fun onError(error: OfflineRegionError) {
                _regions.update { l -> l.map { if (it.id == r.id) it.copy(error = "${error.reason}: ${error.message}") else it } }
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _regions.update { l -> l.map { if (it.id == r.id) it.copy(error = "Tile limit $limit reached") else it } }
            }
        })
    }

    fun download(ctx: Context, name: String, bounds: LatLngBounds, minZ: Int, maxZ: Int, pixelRatio: Float, onResult: (String?) -> Unit) {
        val n = estimate(bounds, minZ, maxZ)
        val total = _regions.value.sumOf { it.required }
        if (n > MAX_TILES_PER_REGION) return onResult("That's about $n tiles. Limit is $MAX_TILES_PER_REGION per area — zoom in or lower the max zoom.")
        if (total + n > MAX_TILES_TOTAL) return onResult("Total offline tiles would exceed $MAX_TILES_TOTAL. Delete an old area first.")
        if (!onUnmeteredNetwork(ctx)) return onResult("Connect to Wi-Fi first (downloads only run on unmetered networks).")
        val def = OfflineTilePyramidRegionDefinition(STYLE_URL, bounds, minZ.toDouble(), maxZ.coerceAtMost(MAX_ZOOM).toDouble(), pixelRatio.coerceAtMost(1f))
        val meta = JSONObject().put("name", name).toString().toByteArray()
        OfflineManager.getInstance(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                live[offlineRegion.id] = offlineRegion
                _regions.update { it + RegionInfo(offlineRegion.id, name, minZ, maxZ, bounds, required = n, active = true) }
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                onResult(null)
            }
            override fun onError(error: String) = onResult(error)
        })
    }

    fun pause(id: Long) { live[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE); _regions.update { l -> l.map { if (it.id == id) it.copy(active = false) else it } } }
    fun resume(ctx: Context, id: Long) {
        if (!onUnmeteredNetwork(ctx)) return
        live[id]?.setDownloadState(OfflineRegion.STATE_ACTIVE); _regions.update { l -> l.map { if (it.id == id) it.copy(active = true, error = null) else it } }
    }
    fun delete(id: Long) {
        val r = live.remove(id) ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { _regions.update { l -> l.filterNot { it.id == id } } }
            override fun onError(error: String) {}
        })
    }

    // ---- MBTiles import ------------------------------------------------------------------------
    private fun mbDir(ctx: Context) = File(ctx.filesDir, "mbtiles").apply { mkdirs() }

    fun refreshMbtiles(ctx: Context) {
        _mbtiles.value = mbDir(ctx).listFiles { f -> f.name.endsWith(".mbtiles") }.orEmpty().mapNotNull { readMeta(it) }.sortedBy { it.name }
    }

    private fun readMeta(f: File): MbtilesInfo? = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val m = mutableMapOf<String, String>()
            db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) }
            db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { }
            MbtilesInfo(f, m["name"] ?: f.nameWithoutExtension, m["format"] ?: "png", m["minzoom"]?.toIntOrNull(), m["maxzoom"]?.toIntOrNull(), f.length())
        }
    } catch (e: Exception) { null }

    suspend fun importMbtiles(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "map").substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "map" }
        val dest = File(mbDir(ctx), "$name-${System.currentTimeMillis() / 1000}.mbtiles")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: return@withContext "Couldn't open the file"
            val meta = readMeta(dest)
            if (meta == null) { dest.delete(); return@withContext "Not a valid MBTiles file (needs metadata and tiles tables)" }
            if (meta.format.lowercase() !in setOf("png", "jpg", "jpeg", "webp")) { dest.delete(); return@withContext "Only raster MBTiles (png/jpg/webp) are supported; this one is '${meta.format}'" }
            refreshMbtiles(ctx); null
        } catch (e: Exception) { dest.delete(); e.message ?: e.toString() }
    }

    fun deleteMbtiles(ctx: Context, f: File) { f.delete(); refreshMbtiles(ctx) }
}
