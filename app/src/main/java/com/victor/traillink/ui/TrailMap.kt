package com.victor.traillink.ui

import android.graphics.*
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.victor.traillink.map.MbtilesInfo
import com.victor.traillink.map.OfflineMaps
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class CamState(val bearing: Double = 0.0, val zoom: Double = 11.0, val lat: Double = 61.1, val metersPerDp: Double = 0.0, val lon: Double = -150.0, val real: Boolean = false)

class MapHandle {
    var map: MapLibreMap? = null
    var style: Style? = null
    var didInitialFit = false
    var didPhoneFit = false
    /** Once per launch: centred on a fresh own fix / on a recent last-known position; user panned since launch. */
    val autoCenter = com.victor.traillink.core.AutoCenter()
    /** Bumped when the auto-centre re-arms (return from background), to re-run the centring effect. */
    val armTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    var centredOnce = false
    var lastGestureLog = 0L
    /** Last camera, restored when the map view is recreated (e.g. coming back to the Map tab). */
    var savedCamera: org.maplibre.android.camera.CameraPosition? = null
    fun rearm(now: Long) { if (autoCenter.onForeground(now)) { centredOnce = false; armTick.value++; com.victor.traillink.service.LinkRepo.log("Map: back after > 5 min, auto-centre re-armed") } }
    var density = 2.6f
    val camera = kotlinx.coroutines.flow.MutableStateFlow(CamState())
    /** Set when the user pans the map by hand (cancels follow-me). */
    var onUserPan: (() -> Unit)? = null
    /** Screen area (px) covered by our overlays: left, top, right, bottom. */
    var insets = intArrayOf(0, 0, 0, 0)
    fun visibleBounds(): LatLngBounds? = map?.projection?.visibleRegion?.latLngBounds
    fun zoom(): Double = map?.cameraPosition?.zoom ?: 10.0
    /** A flyTo requested while the map view isn't composed (other tab): applied once the map is ready. */
    var pendingFly: Triple<Double, Double, Double?>? = null
    fun flyTo(lat: Double, lon: Double, zoom: Double? = null, ms: Int = 700) {
        val m = map ?: run { pendingFly = Triple(lat, lon, zoom); autoCenter.onUserGesture(); return }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(zoom ?: m.cameraPosition.zoom.coerceAtLeast(13.0)).padding(pad()).build()), ms)
    }
    fun follow(lat: Double, lon: Double) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lon)).padding(pad()).build()), 500)
    }
    fun northUp() {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }
    private fun pad() = doubleArrayOf(insets[0].toDouble(), insets[1].toDouble(), insets[2].toDouble(), insets[3].toDouble())
    fun applyPadding() {
        val m = map ?: return
        // padding is passed with each camera move instead (a moveCamera here would cancel a running fit/fly animation)
    }
    fun fit(points: List<LatLng>) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) { flyTo(points[0].latitude, points[0].longitude, 14.0); return }
        val b = LatLngBounds.Builder().includes(points).build()
        val pad = (60 * density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, insets[0] + pad, insets[1] + pad, insets[2] + pad, insets[3] + pad), 700)
    }
}

@Composable
fun TrailMap(
    modifier: Modifier,
    handle: MapHandle,
    partners: List<PartnerUi>,
    phone: PhoneFix?,
    focus: Long?,
    dim: Boolean,
    mbtiles: List<MbtilesInfo>,
    sos: List<Pair<Double, Double>> = emptyList(),
    pins: List<com.victor.traillink.data.PinEntity> = emptyList(),
    selectedPin: String? = null,
    navLine: Pair<LatLng, LatLng>? = null,
    onPinTap: (String) -> Unit = {},
    onLongPress: (Double, Double) -> Unit = { _, _ -> },
    kodiak: com.victor.traillink.data.KodiakData.Loaded? = null,
    showEsmt: Boolean = true,
    showRow: Boolean = true,
    showTrails: Boolean = true,
    showLog: Boolean = true,
    onKodiakTap: (com.victor.traillink.core.KFeature, Double, Double) -> Unit = { _, _, _ -> },
    alaska: com.victor.traillink.data.AlaskaData.Ready? = null,
    showAk17b: Boolean = true,
    showAkRs: Boolean = true,
    showAkDnr: Boolean = true,
    tracksVisible: Boolean = true,
    hiddenTracks: Set<Long> = emptySet(),
    myTrack: List<com.victor.traillink.data.TrackPoint> = emptyList(),
    route: List<Pair<Double, Double>>? = null,
    routeIdx: Int = 0,
    worldRegions: List<com.victor.traillink.map.world.WorldRegion> = emptyList(),
    mapMode: com.victor.traillink.map.world.WorldStyle.Mode = com.victor.traillink.map.world.WorldStyle.Mode(ofm = true, pm = false, usgs = false, shade = true),
    onMapReady: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).also { it.onCreate(Bundle()) } }
    var styleGen by remember { mutableIntStateOf(0) }   // bumps on every (re)loaded style so the effects re-apply
    var mapLoaded by remember { mutableStateOf(false) }
    val pinTap by rememberUpdatedState(onPinTap)
    val longPress by rememberUpdatedState(onLongPress)
    val kodiakTap by rememberUpdatedState(onKodiakTap)
    val kodiakNow by rememberUpdatedState(kodiak)
    val tapScope = rememberCoroutineScope()
    var inKodiak by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            handle.map = null; handle.style = null
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            handle.map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false   // our own compass button
            handle.density = ctx.resources.displayMetrics.density
            fun pushCam() {
                val c = m.cameraPosition
                val lat = c.target?.latitude ?: 61.1
                handle.camera.value = CamState(c.bearing, c.zoom, lat, m.projection.getMetersPerPixelAtLatitude(lat) * handle.density, c.target?.longitude ?: -150.0, c.target != null)
            }
            m.addOnCameraMoveListener { pushCam() }
            fun checkKodiak() {
                val b = m.projection.visibleRegion.latLngBounds
                val k = com.victor.traillink.core.KodiakArea.overlaps(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                if (k != inKodiak) { inKodiak = k; com.victor.traillink.service.LinkRepo.log("Map: Kodiak layer ${if (k) "in view" else "out of view"}") }
            }
            m.addOnCameraIdleListener { pushCam(); handle.savedCamera = m.cameraPosition; checkKodiak(); saveCamera(ctx, m.cameraPosition) }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    val t = System.currentTimeMillis()
                    if (t - handle.lastGestureLog > 30_000) { handle.lastGestureLog = t; com.victor.traillink.service.LinkRepo.log("Map: user gesture (pan/zoom)${if (handle.autoCenter.armed) ", auto-centre off" else ""}") }
                    handle.autoCenter.onUserGesture(); handle.onUserPan?.invoke()
                }
            }
            m.addOnMapLongClickListener { ll -> longPress(ll.latitude, ll.longitude); true }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val box = android.graphics.RectF(pt.x - 45, pt.y - 140, pt.x + 45, pt.y + 15)
                runCatching { m.queryRenderedFeatures(box, "pin-sym") }.getOrNull()?.firstOrNull()?.getStringProperty("id")?.let { pinTap(it); return@addOnMapClickListener true }
                val kbox = android.graphics.RectF(pt.x - 28, pt.y - 28, pt.x + 28, pt.y + 28)
                val kf = runCatching { m.queryRenderedFeatures(kbox, "kd-esmt-pt", "kd-esmt-line", "kd-esmt-area", "kd-row-fill", *KD_TRAIL_TAP, *KD_LOG_TAP) }.getOrNull()
                if (!kf.isNullOrEmpty()) {
                    // prefer easements over the ROW band, points over lines
                    val pick = kf.minBy { when (it.getStringProperty("k")) { "row" -> 3; "trail", "logroad", "logarea" -> 2; "logpin" -> 0; else -> if (it.getStringProperty("geom") == "pt") 0 else 1 } }
                    val pk = pick.getStringProperty("k")
                    val all = kodiakNow?.features.orEmpty()
                    val match = all.firstOrNull { kfe ->
                        if (pk == "trail" || pk?.startsWith("log") == true) kfe.k == pk && kfe.props.optString("id") == pick.getStringProperty("id")
                        else if (pk == "row") kfe.k == "row" && kfe.props.optString("route_id") == pick.getStringProperty("route_id") && kfe.props.optString("mp") == pick.getStringProperty("mp")
                        else kfe.k == "esmt" && kfe.props.optString("id") == (pick.getStringProperty("id") ?: "") && kfe.props.optString("geom") == (pick.getStringProperty("geom") ?: "") &&
                            kfe.props.optString("ein") == (pick.getStringProperty("ein") ?: "")
                    }
                    if (match != null) { kodiakTap(match, ll.latitude, ll.longitude); return@addOnMapClickListener true }
                }
                // 2.3 statewide Alaska easements: look the full feature up by uid (off the main thread)
                val af = runCatching { m.queryRenderedFeatures(kbox, *AK_TAP) }.getOrNull()
                if (!af.isNullOrEmpty()) {
                    val pick = af.minBy { when (it.getStringProperty("geom")) { "pt" -> 0; "area" -> 2; else -> 1 } }
                    val uid = pick.getStringProperty("uid")
                    if (uid != null) {
                        val la = ll.latitude; val lo = ll.longitude; val g = pick.getStringProperty("geom")
                        tapScope.launch { com.victor.traillink.data.AlaskaData.find(uid, g)?.let { kodiakTap(it, la, lo) } }
                        return@addOnMapClickListener true
                    }
                }
                val f = runCatching { m.queryRenderedFeatures(pt, "partner-acc-fill") }.getOrNull()?.firstOrNull()
                if (f != null) { android.widget.Toast.makeText(ctx, "${f.getStringProperty("name")} ${f.getStringProperty("label")}", android.widget.Toast.LENGTH_LONG).show(); return@addOnMapClickListener false }
                false
            }
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(1.0); m.setMaxZoomPreference(18.0)
            // 2.3 start view: last view on this phone, else last known GPS position, else the whole world (auto-centre flies to Me)
            m.cameraPosition = handle.savedCamera ?: loadCamera(ctx) ?: lastKnownCamera(ctx)
                ?: org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(30.0, -100.0)).zoom(1.5).build()
            mapLoaded = true
        }
    }

    // Style: rebuilt when the set of finished download areas changes (or the map first loads); online/offline only flips visibility.
    val regionsKey = worldRegions.filter { it.complete }.joinToString { it.id + ":" + it.terrain }
    val modeNow by rememberUpdatedState(mapMode)
    LaunchedEffect(mapLoaded, regionsKey) {
        val m = handle.map ?: return@LaunchedEffect
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.victor.traillink.map.world.WorldMaps.init(ctx)
            com.victor.traillink.map.world.WorldStyle.build(ctx, worldRegions, modeNow)
        }
        handle.style = null   // the old Style object is invalid once setStyle starts
        m.setStyle(Style.Builder().fromJson(json)) { st ->
            try { setupLayers(st) } catch (e: Exception) { android.util.Log.e("TrailLink", "map layers: $e") }
            handle.style = st
            styleGen++
            com.victor.traillink.service.LinkRepo.log("Map: style loaded (${worldRegions.count { it.complete }} offline areas)")
            handle.pendingFly?.let { (la, lo, z) -> handle.pendingFly = null; handle.flyTo(la, lo, z) }
            onMapReady()
        }
    }
    LaunchedEffect(styleGen, mapMode) { handle.style?.let { com.victor.traillink.map.world.WorldStyle.applyVisibility(it, mapMode) } }

    // MBTiles overlays (raster), placed below our trails.
    LaunchedEffect(styleGen, mbtiles) {
        val st = handle.style ?: return@LaunchedEffect
        st.layers.filter { it.id.startsWith("mb-") }.forEach { st.removeLayer(it) }
        st.sources.filter { it.id.startsWith("mb-") }.forEach { st.removeSource(it) }
        mbtiles.forEachIndexed { i, mb ->
            val id = "mb-$i"
            st.addSource(RasterSource(id, "mbtiles://" + mb.file.absolutePath, 256))
            st.addLayerBelow(RasterLayer(id, id), "kd-row-fill")
        }
    }

    // Kodiak offline layer: 17(b) easements + State ROW, only drawn while the view overlaps the archipelago.
    LaunchedEffect(styleGen, kodiak) {
        val st = handle.style ?: return@LaunchedEffect
        val k = kodiak ?: return@LaunchedEffect
        k.features.mapNotNull { it.props.optString("label").ifBlank { null } }.distinct().forEach { lbl ->
            val id = "ein-$lbl"; if (st.getImage(id) == null) st.addImage(id, labelBitmap(lbl, 0xFFFFE0B2.toInt(), false))
        }
        if (st.getImage("site-icon") == null) st.addImage("site-icon", labelBitmap("P ⛺", 0xFF2979FF.toInt(), false))
        if (st.getImage("misc-icon") == null) st.addImage("misc-icon", labelBitmap("17b", 0xFF90A4AE.toInt(), false))
        st.getSourceAs<GeoJsonSource>("kd-esmt")?.setGeoJson(k.esmtJson)
        st.getSourceAs<GeoJsonSource>("kd-row")?.setGeoJson(k.rowJson)
        // Trails + (future) logging roads: name pills as bitmaps (no glyph downloads offline).
        k.features.filter { it.k == "trail" || it.k.startsWith("log") }.mapNotNull { it.props.optString("label").ifBlank { null }?.let { l -> it.k to l } }.distinct().forEach { (kk, lbl) ->
            val trail = kk == "trail"; val id = (if (trail) "tr-" else "lg-") + lbl
            if (st.getImage(id) == null) st.addImage(id, labelBitmap(lbl, if (trail) 0xFF84FFFF.toInt() else 0xFFFFF59D.toInt(), false))
        }
        st.getSourceAs<GeoJsonSource>("kd-trails")?.setGeoJson(k.trailsJson)
        st.getSourceAs<GeoJsonSource>("kd-log")?.setGeoJson(k.logJson ?: com.victor.traillink.data.KodiakData.EMPTY)
    }
    // 2.3 statewide Alaska easements (file:// GeoJSON, parsed natively by MapLibre)
    LaunchedEffect(styleGen, alaska?.uri) {
        val st = handle.style ?: return@LaunchedEffect
        val a = alaska ?: return@LaunchedEffect
        if (st.getImage("site-icon") == null) st.addImage("site-icon", labelBitmap("P ⛺", 0xFF2979FF.toInt(), false))
        if (st.getImage("misc-icon") == null) st.addImage("misc-icon", labelBitmap("17b", 0xFF90A4AE.toInt(), false))
        st.getSourceAs<GeoJsonSource>("ak-esmt")?.setUri(a.uri)
    }
    LaunchedEffect(styleGen, alaska != null, showAk17b, showAkRs, showAkDnr) {
        val st = handle.style ?: return@LaunchedEffect
        fun v(on: Boolean) = visibility(if (on && alaska != null) Property.VISIBLE else Property.NONE)
        AK_17B_LAYERS.forEach { st.getLayer(it)?.setProperties(v(showAk17b)) }
        AK_RS_LAYERS.forEach { st.getLayer(it)?.setProperties(v(showAkRs)) }
        AK_DNR_LAYERS.forEach { st.getLayer(it)?.setProperties(v(showAkDnr)) }
    }
    // 2.3: the Places layers (Food, Travel, Schools, Military history, Community) live in the Kodiak Maps app now.
    LaunchedEffect(styleGen, inKodiak, showTrails, showLog, kodiak?.logJson != null) {
        val st = handle.style ?: return@LaunchedEffect
        val t = if (inKodiak && showTrails) Property.VISIBLE else Property.NONE
        val l = if (inKodiak && showLog && kodiak?.logJson != null) Property.VISIBLE else Property.NONE
        KD_TRAIL_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(t)) }
        KD_LOG_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(l)) }
    }
    LaunchedEffect(styleGen, inKodiak, showEsmt, showRow) {
        val st = handle.style ?: return@LaunchedEffect
        val e = if (inKodiak && showEsmt) Property.VISIBLE else Property.NONE
        val r = if (inKodiak && showRow) Property.VISIBLE else Property.NONE
        KD_ESMT_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(e)) }
        KD_ROW_LAYERS.forEach { st.getLayer(it)?.setProperties(visibility(r)) }
    }

    LaunchedEffect(styleGen, dim) {
        handle.style?.getLayer("dim")?.setProperties(visibility(if (dim) Property.VISIBLE else Property.NONE))
    }

    LaunchedEffect(styleGen, pins, selectedPin) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(FeatureCollection.fromFeatures(pins.map { p ->
            val emoji = p.emoji ?: com.victor.traillink.core.PinKind.of(p.kind).emoji
            val sel = p.id == selectedPin
            val icon = "pin-${p.id}-${p.color}-${p.name.hashCode()}-$emoji-$sel"
            if (st.getImage(icon) == null) st.addImage(icon, pinBitmap(emoji, p.name, p.color, sel))
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("icon", icon); addStringProperty("id", p.id) }
        }))
    }
    LaunchedEffect(styleGen, navLine) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("nav")?.setGeoJson(navLine?.let { (a, b) ->
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.longitude, a.latitude), Point.fromLngLat(b.longitude, b.latitude)))))
        } ?: FeatureCollection.fromFeatures(emptyList()))
    }
    LaunchedEffect(styleGen, sos) {
        val st = handle.style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("sos")?.setGeoJson(FeatureCollection.fromFeatures(sos.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) }))
    }
    // Possible-range circles: accuracy + pace × time since the fix, redrawn every 1.5 s (one GeoJSON source update).
    LaunchedEffect(styleGen, partners) {
        val st = handle.style ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis(); val s = com.victor.traillink.data.Prefs.settings.value
            val feats = partners.mapNotNull { p ->
                val (r, age, maxAge) = p.range(now, s) ?: return@mapNotNull null
                Feature.fromGeometry(accuracyCircle(p.node.lat!!, p.node.lon!!, r.coerceAtMost(500_000.0))).apply {
                    addStringProperty("color", hex(p.color))
                    addNumberProperty("fo", com.victor.traillink.core.Range.fillOpacity(age, maxAge))
                    addNumberProperty("lo", com.victor.traillink.core.Range.lineOpacity(age, maxAge))
                    addStringProperty("name", p.node.longName)
                    addStringProperty("label", com.victor.traillink.core.Range.label(r, age, maxAge, s.metric) + " (fix ${com.victor.traillink.core.Geo.formatAge(age)} ago)")
                }
            }
            runCatching { st.getSourceAs<GeoJsonSource>("partner-acc")?.setGeoJson(FeatureCollection.fromFeatures(feats)) }
            kotlinx.coroutines.delay(1500)
        }
    }
    // 2.2: my own track (display only) and the active backtrack route
    LaunchedEffect(styleGen, myTrack, tracksVisible) {
        val st = handle.style ?: return@LaunchedEffect
        val segs = if (tracksVisible && myTrack.size >= 2) trailSegments(myTrack) else emptyList()
        st.getSourceAs<GeoJsonSource>("mytrack")?.setGeoJson(FeatureCollection.fromFeatures(segs.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) })) }))
    }
    LaunchedEffect(styleGen, route, routeIdx) {
        val st = handle.style ?: return@LaunchedEffect
        val r = route
        st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(if (r == null || r.size < 2) FeatureCollection.fromFeatures(emptyList()) else
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(r.drop(routeIdx.coerceAtMost(r.size - 2)).map { Point.fromLngLat(it.second, it.first) }))))
        st.getSourceAs<GeoJsonSource>("route-pts")?.setGeoJson(if (r == null || r.isEmpty()) FeatureCollection.fromFeatures(emptyList()) else
            FeatureCollection.fromFeatures(listOfNotNull(
                r.getOrNull(routeIdx)?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("k", "next") } },
                r.last().let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)).apply { addStringProperty("k", "end") } })))
    }
    LaunchedEffect(styleGen, partners, phone, focus, tracksVisible, hiddenTracks) {
        val st = handle.style ?: return@LaunchedEffect
        val trails = partners.filter { tracksVisible && it.node.num !in hiddenTracks && it.trail.size >= 2 }.flatMap { p ->
            trailSegments(p.trail).map { seg ->
                Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addStringProperty("color", hex(p.color)); addBooleanProperty("focus", p.node.num == focus)
                }
            }
        }
        st.getSourceAs<GeoJsonSource>("trails")?.setGeoJson(FeatureCollection.fromFeatures(trails))
        val pts = partners.filter { it.node.lat != null }.map { p ->
            val label = p.node.shortName.ifBlank { p.node.longName.take(4) } + sourceEmoji(p.node.posSource).let { if (it.isEmpty()) "" else " $it" }
            val icon = "label-${p.node.num}-${p.color}-$label-${p.node.num == focus}"
            if (st.getImage(icon) == null) st.addImage(icon, labelBitmap(label, p.color, p.node.num == focus))
            Feature.fromGeometry(Point.fromLngLat(p.node.lon!!, p.node.lat!!)).apply {
                addStringProperty("color", hex(p.color)); addStringProperty("icon", icon)
                addBooleanProperty("focus", p.node.num == focus); addBooleanProperty("self", p.node.isSelf)
            }
        }
        st.getSourceAs<GeoJsonSource>("partners")?.setGeoJson(FeatureCollection.fromFeatures(pts))
        st.getSourceAs<GeoJsonSource>("phone")?.setGeoJson(
            if (phone == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(phone.lon, phone.lat))))
        st.getSourceAs<GeoJsonSource>("phone-acc")?.setGeoJson(
            if (phone?.accuracy == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(accuracyCircle(phone.lat, phone.lon, phone.accuracy.toDouble().coerceAtLeast(3.0)))))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Splits a trail where there is a gap > 10 min or an implausible jump (> 300 km/h); drops 1-point pieces. */
fun trailSegments(pts: List<com.victor.traillink.data.TrackPoint>): List<List<com.victor.traillink.data.TrackPoint>> {
    val out = mutableListOf<MutableList<com.victor.traillink.data.TrackPoint>>()
    var cur = mutableListOf<com.victor.traillink.data.TrackPoint>()
    for (p in pts) {
        val q = cur.lastOrNull()
        if (q != null) {
            val dt = p.time - q.time
            val d = com.victor.traillink.core.Geo.distanceM(q.lat, q.lon, p.lat, p.lon)
            val jump = d > 200 && d / (dt.coerceAtLeast(1) / 1000.0) > com.victor.traillink.service.NodeStore.MAX_SPEED_MPS
            if (dt > com.victor.traillink.service.NodeStore.MAX_GAP_MS || jump) { if (cur.size >= 2) out += cur; cur = mutableListOf() }
        }
        cur += p
    }
    if (cur.size >= 2) out += cur
    return out
}

/** Polygon approximating a circle of [radiusM] metres (accuracy ring). */
private fun accuracyCircle(lat: Double, lon: Double, radiusM: Double): org.maplibre.geojson.Polygon {
    val pts = (0..48).map { i ->
        val a = Math.toRadians(i * 7.5)
        val dLat = radiusM * Math.cos(a) / 111_320.0
        val dLon = radiusM * Math.sin(a) / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        Point.fromLngLat(lon + dLon, lat + dLat)
    }
    return org.maplibre.geojson.Polygon.fromLngLats(listOf(pts))
}

private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

private fun setupLayers(st: Style) {
    st.addSource(GeoJsonSource("trails"))
    st.addSource(GeoJsonSource("partners"))
    st.addSource(GeoJsonSource("phone"))
    st.addSource(GeoJsonSource("phone-acc"))
    st.addSource(GeoJsonSource("partner-acc"))
    st.addSource(GeoJsonSource("sos"))
    st.addSource(GeoJsonSource("pins"))
    st.addSource(GeoJsonSource("nav"))
    st.addSource(GeoJsonSource("mytrack"))
    st.addSource(GeoJsonSource("route"))
    st.addSource(GeoJsonSource("route-pts"))
    st.addSource(GeoJsonSource("kd-esmt"))
    st.addSource(GeoJsonSource("ak-esmt", org.maplibre.android.style.sources.GeoJsonOptions().withMaxZoom(14).withTolerance(0.4f)))
    st.addSource(GeoJsonSource("kd-row"))
    st.addSource(GeoJsonSource("kd-trails"))
    st.addSource(GeoJsonSource("kd-log"))
    setupAlaskaLayers(st)   // below the Kodiak layers
    setupKodiakLayers(st)
    st.addLayer(LineLayer("trails-casing", "trails").withProperties(
        lineColor("#000000"), lineOpacity(0.55f), lineWidth(switchCase(get("focus"), literal(10f), literal(7f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("trails", "trails").withProperties(
        lineColor(toColor(get("color"))), lineWidth(switchCase(get("focus"), literal(6f), literal(4f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("mytrack-casing", "mytrack").withProperties(lineColor("#000000"), lineOpacity(0.5f), lineWidth(6f),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("mytrack", "mytrack").withProperties(lineColor("#82B1FF"), lineWidth(3f), lineDasharray(arrayOf(1.5f, 1f)),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("route-casing", "route").withProperties(lineColor("#000000"), lineOpacity(0.6f), lineWidth(9f),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("route", "route").withProperties(lineColor("#FFD740"), lineWidth(5f),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(CircleLayer("route-pts", "route-pts").withProperties(circleRadius(8f),
        circleColor(switchCase(eq(get("k"), literal("end")), literal("#00E676"), literal("#FFD740"))), circleStrokeColor("#000000"), circleStrokeWidth(2f)))
    st.addLayer(FillLayer("partner-acc-fill", "partner-acc").withProperties(fillColor(toColor(get("color"))), fillOpacity(get("fo"))))
    st.addLayer(LineLayer("partner-acc-line", "partner-acc").withProperties(lineColor(toColor(get("color"))), lineWidth(1.5f), lineOpacity(get("lo")), lineDasharray(arrayOf(3f, 2f))))
    st.addLayer(FillLayer("phone-acc-fill", "phone-acc").withProperties(fillColor("#2979FF"), fillOpacity(0.18f)))
    st.addLayer(LineLayer("phone-acc-line", "phone-acc").withProperties(lineColor("#2979FF"), lineWidth(1.5f), lineOpacity(0.8f)))
    st.addLayer(CircleLayer("phone-halo", "phone").withProperties(circleRadius(18f), circleColor("#2979FF"), circleOpacity(0.25f)))
    st.addLayer(CircleLayer("phone-dot", "phone").withProperties(circleRadius(10f), circleColor("#2979FF"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    st.addImage("me-label", labelBitmap("Me", PHONE_COLOR, false))
    st.addLayer(SymbolLayer("phone-label", "phone").withProperties(iconImage("me-label"), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f))))
    st.addLayer(LineLayer("nav-line", "nav").withProperties(lineColor("#FFD740"), lineWidth(4f), lineOpacity(0.9f), lineDasharray(arrayOf(2f, 1.5f))))
    st.addLayer(SymbolLayer("pin-sym", "pins").withProperties(iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
    st.addLayer(CircleLayer("sos-halo", "sos").withProperties(circleRadius(26f), circleColor("#FF1744"), circleOpacity(0.3f)))
    st.addLayer(CircleLayer("sos-dot", "sos").withProperties(circleRadius(9f), circleColor("#FF1744"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    st.addLayer(CircleLayer("partner-dot", "partners").withProperties(
        circleRadius(switchCase(get("focus"), literal(14f), literal(11f))),
        circleColor(toColor(get("color"))), circleStrokeColor("#000000"),
        circleStrokeWidth(switchCase(get("focus"), literal(4f), literal(3f)))))
    st.addLayer(SymbolLayer("partner-label", "partners").withProperties(
        iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconOffset(arrayOf(0f, -16f))))
}

/** Pin marker: coloured teardrop with the emoji, name pill below the point (bitmap: works offline, no glyphs). */
private fun pinBitmap(emoji: String, name: String, color: Int, sel: Boolean): Bitmap {
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; typeface = Typeface.DEFAULT_BOLD; this.color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    val label = name.take(18)
    val lw = tp.measureText(label) + 24
    val r = if (sel) 34f else 28f
    val labelH = 40f
    val w = maxOf(lw, r * 2 + 8).toInt()
    val headTop = labelH + 4f
    val tipY = headTop + r * 2 + r * 0.9f
    val b = Bitmap.createBitmap(w, (tipY + 2).toInt(), Bitmap.Config.ARGB_8888); val c = Canvas(b)
    val cx = w / 2f; val cy = headTop + r
    c.drawRoundRect(RectF((w - lw) / 2, 0f, (w + lw) / 2, labelH - 2), 19f, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6101214.toInt() })
    c.drawText(label, cx, (labelH - 2) / 2 - (tp.descent() + tp.ascent()) / 2, tp)
    val path = android.graphics.Path().apply {
        addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        moveTo(cx - r * 0.62f, cy + r * 0.75f); lineTo(cx, tipY); lineTo(cx + r * 0.62f, cy + r * 0.75f); close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = if (sel) 6f else 4f; this.color = 0xFF101214.toInt() })
    val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r * 1.05f; textAlign = Paint.Align.CENTER }
    c.drawText(emoji, cx, cy - (ep.descent() + ep.ascent()) / 2, ep)
    return b
}

/** Name pill drawn as a bitmap so the map needs no font/glyph downloads offline. */
private fun labelBitmap(text: String, color: Int, focus: Boolean): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = if (focus) 44f else 36f; typeface = Typeface.DEFAULT_BOLD }
    val w = (p.measureText(text) + 36).toInt(); val h = (p.textSize * 1.45f).toInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6101214.toInt() }
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = if (focus) 6f else 4f; this.color = color }
    c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), h / 2f, h / 2f, stroke)
    p.color = color
    c.drawText(text, 18f, h / 2f - (p.descent() + p.ascent()) / 2, p)
    return b
}

private val KD_ROW_LAYERS = listOf("kd-row-fill", "kd-row-line", "kd-row-cl")
private val KD_ESMT_LAYERS = listOf("kd-esmt-area", "kd-esmt-area-line", "kd-esmt-line-casing", "kd-esmt-line", "kd-esmt-pt", "kd-esmt-label", "kd-esmt-label-pt")

/** Layers for the Kodiak offline data; inserted under the trails so partners and pins stay on top. Start hidden. */
private fun setupKodiakLayers(st: Style) {
    val kindColor = toColor(match(get("kind"), literal("#BDBDBD"),
        stop("trail25", "#FF9100"), stop("trail50", "#FF3D00"), stop("road", "#D500F9"), stop("site", "#2979FF"),
        stop("utility", "#9E9E9E"), stop("air", "#78909C"), stop("airstrip", "#00B8D4")))
    val hidden = visibility(Property.NONE)
    st.addLayer(FillLayer("kd-row-fill", "kd-row").withFilter(eq(get("k"), literal("row"))).withProperties(hidden,
        fillColor("#FFC107"), fillOpacity(match(get("basis"), literal(0.16f), stop("measured", 0.28f)))))
    st.addLayer(LineLayer("kd-row-line", "kd-row").withFilter(eq(get("k"), literal("row"))).withProperties(hidden,
        lineColor("#FFA000"), lineWidth(1.2f), lineOpacity(0.8f), lineDasharray(arrayOf(4f, 2f))))
    st.addLayer(LineLayer("kd-row-cl", "kd-row").withFilter(eq(get("k"), literal("rowcl"))).withProperties(hidden,
        lineColor("#FFE082"), lineWidth(1f), lineOpacity(0.6f)).also { it.minZoom = 13f })
    setupTrailLayers(st)
    st.addLayer(FillLayer("kd-esmt-area", "kd-esmt").withFilter(eq(get("geom"), literal("area"))).withProperties(hidden,
        fillColor(kindColor), fillOpacity(0.25f)))
    st.addLayer(LineLayer("kd-esmt-area-line", "kd-esmt").withFilter(eq(get("geom"), literal("area"))).withProperties(hidden,
        lineColor(kindColor), lineWidth(1.5f), lineDasharray(arrayOf(2f, 1.5f))))
    st.addLayer(LineLayer("kd-esmt-line-casing", "kd-esmt").withFilter(not(has("geom"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.35f), lineWidth(interpolate(linear(), zoom(), stop(8, 2.5f), stop(14, 5f), stop(17, 8f)))))
    st.addLayer(LineLayer("kd-esmt-line", "kd-esmt").withFilter(not(has("geom"))).withProperties(hidden,
        lineColor(kindColor), lineWidth(interpolate(linear(), zoom(), stop(8, 1.5f), stop(14, 3f), stop(17, 5f))),
        lineDasharray(arrayOf(2.2f, 1.4f))))
    st.addLayer(SymbolLayer("kd-esmt-pt", "kd-esmt").withFilter(eq(get("geom"), literal("pt"))).withProperties(hidden,
        iconImage(match(get("kind"), literal("misc-icon"), stop("site", "site-icon"))), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconSize(interpolate(linear(), zoom(), stop(8, 0.55f), stop(13, 0.8f), stop(16, 1f)))))
    st.addLayer(SymbolLayer("kd-esmt-label", "kd-esmt").withFilter(all(has("label"), not(has("geom")))).withProperties(hidden,
        iconImage(concat(literal("ein-"), get("label"))), iconSize(0.75f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f)),
        symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
    st.addLayer(SymbolLayer("kd-esmt-label-pt", "kd-esmt").withFilter(all(has("label"), eq(get("geom"), literal("pt")))).withProperties(hidden,
        iconImage(concat(literal("ein-"), get("label"))), iconSize(0.75f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 18f)),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
}

private val KD_TRAIL_TAP = arrayOf("kd-trail-solid", "kd-trail-single", "kd-trail-route")
private val KD_TRAIL_LAYERS = listOf("kd-trail-casing", *KD_TRAIL_TAP, "kd-trail-label")
private val KD_LOG_TAP = arrayOf("kd-log-line", "kd-log-area", "kd-log-pin")
private val KD_LOG_LAYERS = listOf("kd-log-casing", "kd-log-line", "kd-log-area-casing", "kd-log-area", "kd-log-pin", "kd-log-road-label", "kd-log-label")

/** Kodiak Trails (KIB): cyan; solid = double track / shared-use path, dashed = single track, dotted = route. Above the ROW band, below easements.
 *  Chiniak logging roads (Victor's Google Earth KML): drawn in the KML's own colours, borough property as a dashed white outline, pins. */
private fun setupTrailLayers(st: Style) {
    val hidden = visibility(Property.NONE)
    val w = interpolate(linear(), zoom(), stop(9, 1.2f), stop(13, 2.4f), stop(16, 4f), stop(18, 6f))
    val trailColor = com.victor.traillink.core.TrailsInfo.COLOR
    st.addLayer(LineLayer("kd-trail-casing", "kd-trails").withFilter(neq(get("cls"), literal("route"))).withProperties(hidden,
        lineColor("#00363D"), lineOpacity(0.55f), lineWidth(interpolate(linear(), zoom(), stop(9, 2.6f), stop(13, 4.6f), stop(16, 7f), stop(18, 10f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-solid", "kd-trails").withFilter(any(eq(get("cls"), literal("double")), eq(get("cls"), literal("shared")))).withProperties(hidden,
        lineColor(trailColor), lineWidth(w), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-single", "kd-trails").withFilter(eq(get("cls"), literal("single"))).withProperties(hidden,
        lineColor(trailColor), lineWidth(w), lineDasharray(arrayOf(3f, 1.6f)), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-trail-route", "kd-trails").withFilter(eq(get("cls"), literal("route"))).withProperties(hidden,
        lineColor(trailColor), lineWidth(interpolate(linear(), zoom(), stop(9, 2f), stop(13, 3.4f), stop(16, 5f), stop(18, 7f))),
        lineDasharray(arrayOf(0.05f, 2f)), lineCap(Property.LINE_CAP_ROUND)))
    st.addLayer(SymbolLayer("kd-trail-label", "kd-trails").withFilter(has("label")).withProperties(hidden,
        iconImage(concat(literal("tr-"), get("label"))), iconSize(0.72f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(450f),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconAllowOverlap(false), iconPadding(4f)).also { it.minZoom = 13.5f })

    val logColor = toColor(coalesce(get("color"), literal("#FFEB3B")))
    st.addLayer(LineLayer("kd-log-casing", "kd-log").withFilter(eq(get("k"), literal("logroad"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.45f), lineWidth(interpolate(linear(), zoom(), stop(9, 2.6f), stop(14, 5f), stop(17, 8f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-log-line", "kd-log").withFilter(eq(get("k"), literal("logroad"))).withProperties(hidden,
        lineColor(logColor), lineWidth(interpolate(linear(), zoom(), stop(9, 1.4f), stop(14, 3f), stop(17, 5f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("kd-log-area-casing", "kd-log").withFilter(eq(get("k"), literal("logarea"))).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.5f), lineWidth(4.5f)))
    st.addLayer(LineLayer("kd-log-area", "kd-log").withFilter(eq(get("k"), literal("logarea"))).withProperties(hidden,
        lineColor(toColor(coalesce(get("color"), literal("#FFFFFF")))), lineWidth(2.2f), lineOpacity(0.9f), lineDasharray(arrayOf(3f, 1.5f))))
    st.addLayer(CircleLayer("kd-log-pin", "kd-log").withFilter(eq(get("k"), literal("logpin"))).withProperties(hidden,
        circleColor(toColor(coalesce(get("color"), literal("#2979FF")))), circleRadius(interpolate(linear(), zoom(), stop(9, 4f), stop(15, 7f))),
        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
    st.addLayer(SymbolLayer("kd-log-road-label", "kd-log").withFilter(all(has("label"), eq(get("k"), literal("logroad")))).withProperties(hidden,
        iconImage(concat(literal("lg-"), get("label"))), iconSize(0.66f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconAllowOverlap(false)).also { it.minZoom = 14f })
    st.addLayer(SymbolLayer("kd-log-label", "kd-log").withFilter(all(has("label"), eq(get("k"), literal("logpin")))).withProperties(hidden,
        iconImage(concat(literal("lg-"), get("label"))), iconSize(0.72f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 14f)),
        iconAllowOverlap(false)).also { it.minZoom = 12.5f })
}

private fun saveCamera(ctx: android.content.Context, c: org.maplibre.android.camera.CameraPosition) {
    val t = c.target ?: return
    ctx.getSharedPreferences("map_cam", android.content.Context.MODE_PRIVATE).edit()
        .putString("cam", "%.6f,%.6f,%.2f".format(java.util.Locale.US, t.latitude, t.longitude, c.zoom)).apply()
}
private fun loadCamera(ctx: android.content.Context): org.maplibre.android.camera.CameraPosition? = runCatching {
    val v = ctx.getSharedPreferences("map_cam", android.content.Context.MODE_PRIVATE).getString("cam", null)!!.split(',').map { it.toDouble() }
    org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(v[0], v[1])).zoom(v[2]).build()
}.getOrNull()
@android.annotation.SuppressLint("MissingPermission")
private fun lastKnownCamera(ctx: android.content.Context): org.maplibre.android.camera.CameraPosition? = runCatching {
    val lm = ctx.getSystemService(android.location.LocationManager::class.java)
    val l = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }!!
    org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(l.latitude, l.longitude)).zoom(12.0).build()
}.getOrNull()

private val AK_17B_LAYERS = listOf("ak-17b-area", "ak-17b-casing", "ak-17b-line", "ak-17b-pt", "ak-17b-label")
private val AK_RS_LAYERS = listOf("ak-rs-casing", "ak-rs-line", "ak-rs-label")
private val AK_DNR_LAYERS = listOf("ak-dnr-area", "ak-dnr-area-line", "ak-dnr-line", "ak-dnr-pt", "ak-dnr-label")
private val AK_TAP = arrayOf("ak-17b-pt", "ak-17b-line", "ak-17b-area", "ak-rs-line", "ak-dnr-pt", "ak-dnr-line", "ak-dnr-area")
const val AK_RS_COLOR = "#C6FF00"
const val AK_DNR_COLOR = "#FF4081"

/** Statewide Alaska easements (2.3). Lines from z7, points/areas from z9, labels from z12 (keeps low zooms fast). Start hidden. */
private fun setupAlaskaLayers(st: Style) {
    val hidden = visibility(Property.NONE)
    val kindColor = toColor(match(get("kind"), literal("#BDBDBD"),
        stop("trail25", "#FF9100"), stop("trail50", "#FF3D00"), stop("road", "#D500F9"), stop("site", "#2979FF"),
        stop("utility", "#9E9E9E"), stop("air", "#78909C"), stop("airstrip", "#00B8D4")))
    val isLine = not(has("geom"))
    val w = interpolate(linear(), zoom(), stop(7, 1f), stop(10, 1.6f), stop(14, 3f), stop(17, 5f))
    val wc = interpolate(linear(), zoom(), stop(7, 2f), stop(10, 2.8f), stop(14, 5f), stop(17, 8f))
    fun k(v: String) = eq(get("k"), literal(v))
    fun label(id: String, filter: org.maplibre.android.style.expressions.Expression, color: String, field: org.maplibre.android.style.expressions.Expression) = SymbolLayer(id, "ak-esmt").withFilter(filter).withProperties(hidden,
        textField(field), textFont(arrayOf("noto-regular")), textSize(11f), textColor(color), textHaloColor("#000000"), textHaloWidth(1.4f),
        symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), textMaxAngle(30f)).also { it.minZoom = 12f }
    // DNR public access (pink): areas first (bottom), then lines, points
    st.addLayer(FillLayer("ak-dnr-area", "ak-esmt").withFilter(all(k("dnr"), eq(get("geom"), literal("area")))).withProperties(hidden,
        fillColor(AK_DNR_COLOR), fillOpacity(0.18f)).also { it.minZoom = 9f })
    st.addLayer(LineLayer("ak-dnr-area-line", "ak-esmt").withFilter(all(k("dnr"), eq(get("geom"), literal("area")))).withProperties(hidden,
        lineColor(AK_DNR_COLOR), lineWidth(1.2f), lineOpacity(0.85f)).also { it.minZoom = 9f })
    st.addLayer(LineLayer("ak-dnr-line", "ak-esmt").withFilter(all(k("dnr"), isLine)).withProperties(hidden,
        lineColor(AK_DNR_COLOR), lineWidth(w), lineOpacity(0.9f), lineDasharray(arrayOf(3f, 1.5f))).also { it.minZoom = 7f })
    st.addLayer(CircleLayer("ak-dnr-pt", "ak-esmt").withFilter(all(k("dnr"), eq(get("geom"), literal("pt")))).withProperties(hidden,
        circleColor(AK_DNR_COLOR), circleRadius(5f), circleStrokeColor("#000000"), circleStrokeWidth(1f)).also { it.minZoom = 9f })
    st.addLayer(label("ak-dnr-label", all(k("dnr"), isLine), "#FF80AB", get("label")))
    // RS 2477 (lime)
    st.addLayer(LineLayer("ak-rs-casing", "ak-esmt").withFilter(k("rs2477")).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.4f), lineWidth(wc)).also { it.minZoom = 7f })
    st.addLayer(LineLayer("ak-rs-line", "ak-esmt").withFilter(k("rs2477")).withProperties(hidden,
        lineColor(AK_RS_COLOR), lineWidth(w), lineDasharray(arrayOf(1f, 1.2f))).also { it.minZoom = 7f })
    st.addLayer(label("ak-rs-label", k("rs2477"), AK_RS_COLOR, coalesce(get("name"), get("label"), literal(""))))
    // BLM 17(b) statewide (same symbology as the Kodiak 17(b) layer)
    st.addLayer(FillLayer("ak-17b-area", "ak-esmt").withFilter(all(k("esmt"), eq(get("geom"), literal("area")))).withProperties(hidden,
        fillColor(kindColor), fillOpacity(0.25f)).also { it.minZoom = 12f })
    st.addLayer(LineLayer("ak-17b-casing", "ak-esmt").withFilter(all(k("esmt"), isLine)).withProperties(hidden,
        lineColor("#000000"), lineOpacity(0.35f), lineWidth(wc)).also { it.minZoom = 7f })
    st.addLayer(LineLayer("ak-17b-line", "ak-esmt").withFilter(all(k("esmt"), isLine)).withProperties(hidden,
        lineColor(kindColor), lineWidth(w), lineDasharray(arrayOf(2.2f, 1.4f))).also { it.minZoom = 7f })
    st.addLayer(SymbolLayer("ak-17b-pt", "ak-esmt").withFilter(all(k("esmt"), eq(get("geom"), literal("pt")))).withProperties(hidden,
        iconImage(match(get("kind"), literal("misc-icon"), stop("site", "site-icon"))), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconSize(interpolate(linear(), zoom(), stop(9, 0.5f), stop(13, 0.8f), stop(16, 1f)))).also { it.minZoom = 9f })
    st.addLayer(label("ak-17b-label", all(k("esmt"), isLine), "#FFE0B2", get("label")))
}
