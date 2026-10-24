package com.victor.traillink.data

import android.content.Context
import com.victor.traillink.BuildConfig
import com.victor.traillink.core.KFeature
import com.victor.traillink.core.KGeo
import com.victor.traillink.service.LinkRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 2.3 statewide "Alaska easements" layer (bundled, ~2.8 MB gz / 16 MB GeoJSON):
 *  - BLM Alaska ANCSA 17(b) easements outside the Kodiak archipelago (Kodiak's own 17(b) layer draws those, so nothing is drawn twice)
 *  - Alaska DNR RS 2477 trails
 *  - Alaska DNR public access easements (issued / transferred / interim)
 * The GeoJSON (one feature per line) is unpacked once to filesDir and handed to MapLibre as a file:// source, so the
 * app never holds the whole thing in Java memory. A tapped feature is looked up by its "uid" with a line scan.
 */
object AlaskaData {
    class Ready(val file: File, val meta: JSONObject) {
        val uri: String get() = "file://" + file.absolutePath
        fun count(k: String) = meta.optJSONObject("counts")?.optInt(k) ?: 0
        val summary: String get() = "${count("blm")} BLM 17(b) · ${count("rs2477")} RS 2477 · ${count("dnr_line") + count("dnr_poly") + count("dnr_pt")} DNR access"
    }
    private val _s = MutableStateFlow<Ready?>(null)
    val state: StateFlow<Ready?> get() = _s

    private fun dir(ctx: Context) = File(ctx.filesDir, "alaska").apply { mkdirs() }

    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        if (_s.value != null) return@withContext
        runCatching {
            val f = File(dir(ctx), "easements-${BuildConfig.VERSION_CODE}.geojson")
            if (!f.exists() || f.length() < 1000) {
                dir(ctx).listFiles()?.filter { it.name.startsWith("easements-") }?.forEach { it.delete() }
                val tmp = File(dir(ctx), "easements.tmp")
                val inp = runCatching { ctx.assets.open("alaska/easements.geojson.gz").let { GZIPInputStream(it) } }.getOrNull()
                    ?: ctx.assets.open("alaska/easements.geojson")   // in case the packager stored it un-gzipped
                inp.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                tmp.renameTo(f)
            }
            val meta = JSONObject(ctx.assets.open("alaska/meta.json").bufferedReader().use { it.readText() })
            _s.value = Ready(f, meta)
            LinkRepo.log("Alaska easements ready: ${_s.value?.summary} (${f.length() / 1024} KB)")
        }.onFailure { LinkRepo.log("Alaska easements load failed: ${it.message}") }
    }

    /** Full feature (complete geometry) for a tapped uid, or null. ~16 MB line scan, run off the main thread. */
    suspend fun find(uid: String, geom: String?): KFeature? = withContext(Dispatchers.IO) {
        val r = _s.value ?: return@withContext null
        val key = "\"uid\":\"$uid\""
        val geomKey = geom?.let { "\"geom\":\"$it\"" }
        runCatching {
            r.file.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.contains(key) && (geomKey == null || it.contains(geomKey)) && it.startsWith("{\"type\":\"Feature\"") }
            }?.trimEnd(',')?.let { KGeo.parse("{\"type\":\"FeatureCollection\",\"features\":[$it]}").firstOrNull() }
        }.getOrNull()
    }

    const val SOURCES = "BLM Alaska ANCSA 17(b) easements (gis.blm.gov/akarcgis, BLM_AK_Easements_17b), Alaska DNR RS 2477 trails and Alaska DNR easements (arcgis.dnr.alaska.gov OpenData). Public data, no warranty."
    const val RS_DISCLAIMER = "For reference only. RS 2477 routes are State assertions; validity, width and location can be disputed, especially on federal and private land. Check the DNR case file."
    const val DNR_DISCLAIMER = "For reference only. Shown from DNR status plats; the case file sets the exact location, width and allowed uses."
}
