package com.victor.traillink.data

import android.content.Context
import com.victor.traillink.core.Easements
import com.victor.traillink.core.KFeature
import com.victor.traillink.core.KGeo
import com.victor.traillink.core.KodiakArea
import com.victor.traillink.core.Kml
import com.victor.traillink.core.TrailsInfo
import com.victor.traillink.service.LinkRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bundled Kodiak offline layer (17(b) easements + State ROW), with a manual online refresh of the easements. */
object KodiakData {
    const val BLM = "https://gis.blm.gov/akarcgis/rest/services/lands/BLM_AK_Easements_17b/FeatureServer"
    const val DOT = "https://services.arcgis.com/r4A0V7UzH9fcLVvv/ArcGIS/rest/services/AKDOTPF_Route_Data/FeatureServer/20"
    const val BUNDLED_DOT_EDIT = "2026-09-29"

    const val KIB_TRAILS = "https://services1.arcgis.com/R5BNizttyFKxRSMm/arcgis/rest/services/KIB_Trails/FeatureServer/0"
    /** Victor's Google Earth export (Chiniak logging roads), bundled as assets/kodiak/logging_roads.kml (or .kmz). */
    const val LOG_ROADS_ASSET = "logging_roads"
    const val LOG_ROADS_SOURCE = "Victor (kodiakking.com)"
    val LOG_AREA_NAME = Regex("(?i)borough property")

    class Loaded(val esmtJson: String, val rowJson: String, val features: List<KFeature>, val esmtSource: String, val esmtDate: String, val counts: String,
                 val trailsJson: String = EMPTY, val logJson: String? = null, val trailsSummary: String = "")
    private val _s = MutableStateFlow<Loaded?>(null)
    val state: StateFlow<Loaded?> get() = _s

    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    private fun dir(ctx: Context) = File(ctx.filesDir, "kodiak").apply { mkdirs() }
    private fun gunzip(i: java.io.InputStream) = GZIPInputStream(i).bufferedReader().use { it.readText() }
    /** The build un-gzips *.gz assets (stored as kodiak/x.geojson, zip-deflated in the APK); accept either form. */
    private fun asset(ctx: Context, name: String): String =
        runCatching { ctx.assets.open("kodiak/$name").bufferedReader().use { it.readText() } }.getOrNull()
            ?: ctx.assets.open("kodiak/$name.gz").use { gunzip(it) }

    private fun assetBytes(ctx: Context, name: String): ByteArray? = runCatching { ctx.assets.open("kodiak/$name").use { it.readBytes() } }.getOrNull()

    /** Chiniak logging roads from a bundled KML/KMZ, converted on load; null while no file is bundled. */
    private fun logRoads(ctx: Context): String? {
        val b = assetBytes(ctx, "$LOG_ROADS_ASSET.kml") ?: assetBytes(ctx, "$LOG_ROADS_ASSET.kmz") ?: return null
        return Kml.toGeoJson(b, "log", LOG_ROADS_SOURCE, LOG_AREA_NAME).toString()
    }

    suspend fun ensure(ctx: Context) { if (_s.value == null) reload(ctx) }

    suspend fun reload(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val upd = File(dir(ctx), "easements.geojson.gz"); val updMeta = File(dir(ctx), "update.json")
            val meta = JSONObject(ctx.assets.open("kodiak/meta.json").bufferedReader().use { it.readText() })
            val (esmt, src, date) = if (upd.exists() && updMeta.exists()) {
                val m = JSONObject(updMeta.readText())
                Triple(upd.inputStream().use { gunzip(it) }, "Online update", m.optString("updated"))
            } else Triple(asset(ctx, "easements.geojson"), "Bundled", meta.optString("fetched"))
            val row = asset(ctx, "state_row.geojson")
            val trails = runCatching { asset(ctx, "trails.geojson") }.onFailure { LinkRepo.log("Kodiak trails load failed: ${it.message}") }.getOrDefault(EMPTY)
            val log = runCatching { logRoads(ctx) }.onFailure { LinkRepo.log("Logging roads KML failed: ${it.message}") }.getOrNull()
            val tf = KGeo.parse(trails)
            val fs = KGeo.parse(esmt) + KGeo.parse(row) + tf + (log?.let { KGeo.parse(it) } ?: emptyList())
            val n = fs.count { it.k == "esmt" && it.props.optString("geom") != "area" }
            val ts = TrailsInfo.summary(tf)
            _s.value = Loaded(esmt, row, fs, src, date, "$n easements, ${fs.count { it.k == "row" }} ROW segments", trails, log, ts)
            LinkRepo.log("Kodiak layer loaded: $n easements ($src $date), ${fs.count { it.k == "row" }} ROW segments, $ts" + (log?.let { ", logging roads KML" } ?: ""))
        }.onFailure { LinkRepo.log("Kodiak layer load failed: ${it.message}") }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    /** Re-downloads the BLM 17(b) layers for the Kodiak box. Returns a human-readable result. */
    suspend fun update(ctx: Context): String = withContext(Dispatchers.IO) {
        try {
            val geom = URLEncoder.encode("${KodiakArea.W},${KodiakArea.S},${KodiakArea.E},${KodiakArea.N}", "UTF-8")
            val out = JSONArray(); var raw = 0
            for (layer in 0..1) {
                val fc = JSONObject(get("$BLM/$layer/query?where=1%3D1&geometry=$geom&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=*&outSR=4326&f=geojson"))
                if (fc.has("error")) error(fc.getJSONObject("error").optString("message"))
                val arr = fc.getJSONArray("features")
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i); raw++
                    if (!touchesKodiak(f.optJSONObject("geometry"))) continue
                    Easements.normalize(f, layer == 0).forEach { out.put(it) }
                }
            }
            if (raw == 0) error("BLM returned no features - keeping current data")
            // Keep what BLM's GIS does not carry: patent text (by Easement_ID) and patent-only easements not mapped by BLM.
            runCatching {
                val b = JSONObject(asset(ctx, "easements.geojson")).getJSONArray("features")
                val pat = HashMap<String, String>()
                for (i in 0 until b.length()) {
                    val bp = b.getJSONObject(i).getJSONObject("properties")
                    if (bp.optBoolean("unmapped")) out.put(b.getJSONObject(i))
                    else if (bp.has("patent")) pat[bp.optString("id")] = bp.getString("patent")
                }
                for (i in 0 until out.length()) { val op = out.getJSONObject(i).getJSONObject("properties"); pat[op.optString("id")]?.let { if (!op.has("patent")) op.put("patent", it) } }
            }.onFailure { com.victor.traillink.service.LinkRepo.log("Kodiak update: could not merge patent notes: ${it.message}") }
            val text = JSONObject().put("type", "FeatureCollection").put("features", out).toString()
            val tmp = File(dir(ctx), "easements.tmp")
            GZIPOutputStream(tmp.outputStream()).use { it.write(text.toByteArray()) }
            tmp.renameTo(File(dir(ctx), "easements.geojson.gz"))
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            File(dir(ctx), "update.json").writeText(JSONObject().put("updated", now).put("raw", raw).toString())
            val dot = runCatching {
                val ms = JSONObject(get("$DOT?f=json")).getJSONObject("editingInfo").getLong("dataLastEditDate")
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
            }.getOrNull()
            reload(ctx)
            val dotNote = when { dot == null -> ""; dot > BUNDLED_DOT_EDIT -> " DOT&PF centerlines changed $dot; the ROW band stays on the bundled $BUNDLED_DOT_EDIT data until the next app build."; else -> " DOT&PF centerlines unchanged ($dot)." }
            "Updated: $raw BLM records.$dotNote".also { LinkRepo.log("Kodiak update: $it") }
        } catch (e: Exception) {
            "Update failed: ${e.message ?: e.javaClass.simpleName}".also { LinkRepo.log("Kodiak update: $it") }
        }
    }

    private fun touchesKodiak(g: JSONObject?): Boolean {
        g ?: return false; val c = g.getJSONArray("coordinates")
        fun pt(a: JSONArray) = KodiakArea.contains(a.getDouble(1), a.getDouble(0))
        return when (g.getString("type")) {
            "Point" -> pt(c)
            "LineString" -> (0 until c.length()).any { pt(c.getJSONArray(it)) }
            "MultiLineString" -> (0 until c.length()).any { j -> val l = c.getJSONArray(j); (0 until l.length()).any { pt(l.getJSONArray(it)) } }
            else -> true
        }
    }

    fun resetToBundled(ctx: Context) { File(dir(ctx), "easements.geojson.gz").delete(); File(dir(ctx), "update.json").delete() }
}
