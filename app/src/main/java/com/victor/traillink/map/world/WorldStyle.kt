package com.victor.traillink.map.world

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the map style at runtime:
 *   background, world overview (bundled z0-4), each downloaded area (least to most detail, each a full Protomaps layer stack
 *   with its own hillshade), USGS topo raster, OpenFreeMap (online), dim veil. The app's own layers go on top afterwards.
 * Switching online/offline/basemap only flips layer visibility (see [visibility]); the style is rebuilt when the set of
 * finished areas changes.
 */
object WorldStyle {
    const val USGS_TILES = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    private var pmLayers: JSONArray? = null
    private var ofm: JSONObject? = null

    data class Mode(val ofm: Boolean, val pm: Boolean, val usgs: Boolean, val shade: Boolean)
    fun mode(basemap: String, online: Boolean, offlineOnly: Boolean, shade: Boolean): Mode {
        val usgs = basemap == "usgs"
        val o = !usgs && online && !offlineOnly
        return Mode(ofm = o, pm = !o, usgs = usgs, shade = shade)
    }

    /** Rough boxes for where the USGS Topo basemap has map content (US states and territories). */
    private val USGS_BOXES = listOf(
        doubleArrayOf(24.3, -125.0, 49.5, -66.8),    // lower 48
        doubleArrayOf(51.0, -180.0, 71.6, -129.9),   // Alaska (incl. Aleutians east of 180)
        doubleArrayOf(51.0, 172.0, 53.2, 180.0),     // western Aleutians
        doubleArrayOf(18.8, -160.4, 22.4, -154.7),   // Hawaii
        doubleArrayOf(17.6, -67.4, 18.6, -64.5),     // Puerto Rico, USVI
        doubleArrayOf(13.2, 144.5, 20.7, 146.2),     // Guam, Northern Marianas
        doubleArrayOf(-14.6, -171.2, -11.0, -168.1), // American Samoa
    )
    fun inUsgsCoverage(lat: Double, lon: Double) = USGS_BOXES.any { lat >= it[0] && lat <= it[2] && lon >= it[1] && lon <= it[3] }

    data class Basemap(val effective: String, val note: String?)
    /**
     * 2.4: USGS Topo is the default, but it only covers the US and needs the internet unless a USGS area was saved.
     * Falls back to the World map (with a note) outside USGS coverage, or offline when there is no saved USGS topo here
     * but a downloaded World area covers the view.
     */
    fun effectiveBasemap(chosen: String, lat: Double, lon: Double, online: Boolean, offlineOnly: Boolean,
                         usgsSavedHere: Boolean, worldSavedHere: Boolean): Basemap {
        if (chosen != "usgs") return Basemap("world", null)
        if (!inUsgsCoverage(lat, lon)) return Basemap("world", "USGS topo covers the US only. Showing the World map here.")
        val netOk = online && !offlineOnly
        if (!netOk && !usgsSavedHere && worldSavedHere) return Basemap("world", "No USGS topo saved here. Showing your downloaded World map.")
        return Basemap("usgs", null)
    }

    fun attribution(m: Mode, hasTerrain: Boolean): String = buildList {
        if (m.usgs) add("USGS The National Map")
        if (m.ofm) add(WorldSources.ATTR_ONLINE) else add(WorldSources.ATTR_OFFLINE)
        if (m.shade && (m.ofm || hasTerrain)) add("Terrain: Mapterhorn")
    }.joinToString(" · ")

    private fun load(ctx: Context) {
        if (pmLayers == null) pmLayers = JSONArray(ctx.assets.open("world/pm-layers.json").bufferedReader().readText())
        if (ofm == null) ofm = JSONObject(ctx.assets.open("world/ofm-layers.json").bufferedReader().readText())
    }

    private fun vis(o: JSONObject, on: Boolean) = o.put("layout", (o.optJSONObject("layout") ?: JSONObject()).put("visibility", if (on) "visible" else "none"))
    private fun copy(o: JSONObject) = JSONObject(o.toString())

    /** Region stacks are drawn in this order (least detail first so the detailed one wins where they overlap). */
    fun drawOrder(regions: List<WorldRegion>) = regions.filter { it.complete }.sortedWith(compareBy({ it.maxZoom }, { it.created }))

    fun build(ctx: Context, regions: List<WorldRegion>, m: Mode): String {
        load(ctx)
        val sources = JSONObject(); val layers = JSONArray()
        val pm = pmLayers!!
        // --- offline vector: bundled world overview + downloaded areas
        sources.put("pmw", JSONObject().put("type", "vector").put("url", "pmtiles://file://" + WorldMaps.worldFile(ctx).absolutePath)
            .put("attribution", WorldSources.ATTR_OSM))
        val bg = (0 until pm.length()).map { pm.getJSONObject(it) }.first { it.getString("type") == "background" }
        layers.put(vis(copy(bg).put("id", "pm-bg"), true))   // always on: also the colour shown while tiles load
        fun stack(prefix: String, src: String, hsSrc: String?) {
            var hsDone = false
            for (i in 0 until pm.length()) {
                val l = pm.getJSONObject(i)
                if (l.getString("type") == "background") continue
                val id = l.getString("id")
                if (hsSrc != null && !hsDone && id.startsWith("roads_tunnels")) {
                    layers.put(vis(hillshade("$prefix-hs", hsSrc), m.pm && m.shade)); hsDone = true
                }
                layers.put(vis(copy(l).put("id", "$prefix-$id").put("source", src), m.pm))
            }
        }
        stack("pmw", "pmw", null)
        for (r in drawOrder(regions)) {
            val f = WorldMaps.baseFile(ctx, r.id); if (!f.exists()) continue
            val src = "pr-${r.id}"
            sources.put(src, JSONObject().put("type", "vector").put("url", "pmtiles://file://" + f.absolutePath))
            val tf = WorldMaps.terrainFile(ctx, r.id)
            val hs = if (r.terrain && tf.exists()) "tr-${r.id}".also { sources.put(it, JSONObject().put("type", "raster-dem")
                .put("url", "pmtiles://file://" + tf.absolutePath).put("encoding", "terrarium").put("tileSize", 512)) } else null
            stack(src, src, hs)
        }
        // --- USGS topo (US only, online + the old offline areas)
        sources.put("usgs-topo", JSONObject().put("type", "raster").put("tiles", JSONArray().put(USGS_TILES)).put("tileSize", 256)
            .put("minzoom", 0).put("maxzoom", 16).put("attribution", "USGS The National Map: 3DEP, NHD, GNIS, NBD, NTD and partners"))
        layers.put(vis(JSONObject().put("id", "usgs-topo").put("type", "raster").put("source", "usgs-topo").put("paint", JSONObject().put("raster-fade-duration", 0)), m.usgs))
        // --- online: OpenFreeMap Liberty
        val o = ofm!!
        val os = o.getJSONObject("sources")
        sources.put("ofm", os.getJSONObject("openmaptiles"))
        sources.put("ofm-ne2", os.getJSONObject("ne2_shaded"))
        sources.put("hs-online", JSONObject().put("type", "raster-dem").put("tiles", JSONArray().put(WorldSources.TERRAIN_TILES))
            .put("encoding", "terrarium").put("tileSize", 512).put("minzoom", 0).put("maxzoom", 12))
        val ol = o.getJSONArray("layers"); var hsDone = false
        for (i in 0 until ol.length()) {
            val l = copy(ol.getJSONObject(i)); val id = l.getString("id")
            if (!hsDone && (id.startsWith("tunnel_") || id.startsWith("road_"))) { layers.put(vis(hillshade("hs-online", "hs-online"), m.ofm && m.shade)); hsDone = true }
            l.put("id", "ofm-$id")
            when (l.optString("source")) { "openmaptiles" -> l.put("source", "ofm"); "ne2_shaded" -> l.put("source", "ofm-ne2") }
            layers.put(vis(l, m.ofm))
        }
        // --- dim veil (replaces the old USGS raster brightness trick; works for every basemap)
        sources.put("dim", JSONObject().put("type", "geojson").put("data", JSONObject("""{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-85.1],[180,-85.1],[180,85.1],[-180,85.1],[-180,-85.1]]]}}""")))
        layers.put(JSONObject().put("id", "dim").put("type", "fill").put("source", "dim").put("paint", JSONObject().put("fill-color", "#000000").put("fill-opacity", 0.22).put("fill-antialias", false)))
        return JSONObject().put("version", 8).put("name", "Sovereign Comms world")
            .put("glyphs", "asset://world/fonts/{fontstack}/{range}.pbf")
            .put("sprite", o.getString("sprite"))
            .put("sources", sources).put("layers", layers).toString()
    }

    private fun hillshade(id: String, src: String) = JSONObject().put("id", id).put("type", "hillshade").put("source", src)
        .put("paint", JSONObject().put("hillshade-exaggeration", 0.35).put("hillshade-shadow-color", "#5a5040").put("hillshade-highlight-color", "#ffffff")
            .put("hillshade-illumination-anchor", "map"))

    /** Layer ids by group so TrailMap can flip visibility without rebuilding. */
    fun applyVisibility(st: org.maplibre.android.maps.Style, m: Mode) {
        val v = { on: Boolean -> org.maplibre.android.style.layers.PropertyFactory.visibility(if (on) "visible" else "none") }
        for (l in st.layers) {
            val id = l.id
            val on = when {
                id == "pm-bg" || id == "dim" -> continue
                id == "usgs-topo" -> m.usgs
                id == "hs-online" -> m.ofm && m.shade
                id.startsWith("ofm-") -> m.ofm
                id.endsWith("-hs") && (id.startsWith("pr-")) -> m.pm && m.shade
                id.startsWith("pmw-") || id.startsWith("pr-") -> m.pm
                else -> continue
            }
            if (l.visibility.value != (if (on) "visible" else "none")) l.setProperties(v(on))
        }
    }
}
