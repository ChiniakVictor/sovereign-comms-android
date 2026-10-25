package com.victor.traillink.ui

import com.victor.traillink.core.Range
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Geo
import com.victor.traillink.data.Prefs
import com.victor.traillink.map.OfflineMaps
import com.victor.traillink.service.Conn
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val Glass = Color(0xD9161A1F)
private val GlassDark = Color(0xF0121519)

/** Map-first screen: full-bleed map, small floating controls, and a 3-state bottom sheet (side panel when unfolded). */
@Composable
fun MapScreen(vm: AppViewModel, handle: MapHandle, bottomInset: Dp, onGoRadio: () -> Unit, onGoInternet: () -> Unit) {
    val ctx = LocalContext.current
    val partners by vm.partners.collectAsState()
    val phone by vm.phone.collectAsState()
    val heading by vm.heading.collectAsState()
    val focusId by vm.focus.collectAsState()
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val now by vm.now.collectAsState()
    val cam by handle.camera.collectAsState()
    val mbtiles by OfflineMaps.mbtiles.collectAsState()
    var showDownload by remember { mutableStateOf(false) }   // USGS (US topo) download dialog
    var selectArea by remember { mutableStateOf(false) }      // 2.3 worldwide area download
    val worldRegions by com.victor.traillink.map.world.WorldMaps.regions.collectAsState()
    val alaskaData by com.victor.traillink.data.AlaskaData.state.collectAsState()
    LaunchedEffect(Unit) { com.victor.traillink.data.AlaskaData.ensure(ctx) }
    val online by com.victor.traillink.map.world.WorldMaps.online.collectAsState()
    LaunchedEffect(Unit) { com.victor.traillink.map.world.WorldMaps.init(ctx) }
    val usgsRegions by OfflineMaps.regions.collectAsState()
    // 2.4: USGS Topo by default, World map where USGS has nothing to show (decided on a ~0.1 deg grid so panning doesn't thrash).
    val camKey = Pair((cam.lat * 10).roundToInt(), (cam.lon * 10).roundToInt())
    val base = remember(settings.basemap, online, settings.offlineMapsOnly, camKey, cam.real, usgsRegions, worldRegions, mbtiles) {
        if (!cam.real) com.victor.traillink.map.world.WorldStyle.Basemap(settings.basemap.let { if (it == "usgs") "usgs" else "world" }, null)
        else {
            val la = cam.lat; val lo = cam.lon
            val usgsHere = usgsRegions.any { r -> r.complete && r.bounds?.let { la in it.latitudeSouth..it.latitudeNorth && lo in it.longitudeWest..it.longitudeEast } == true }
            val worldHere = worldRegions.any { r -> r.complete && la in r.bbox.south..r.bbox.north && lo in r.bbox.west..r.bbox.east }
            com.victor.traillink.map.world.WorldStyle.effectiveBasemap(settings.basemap, la, lo, online, settings.offlineMapsOnly, usgsHere, worldHere)
        }
    }
    LaunchedEffect(base.effective, base.note) { com.victor.traillink.service.LinkRepo.log("Map: base map ${base.effective}" + (base.note?.let { " ($it)" } ?: "")) }
    val mapMode = com.victor.traillink.map.world.WorldStyle.mode(base.effective, online, settings.offlineMapsOnly, settings.terrainShade)
    var mapReady by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(false) }
    handle.onUserPan = { follow = false }

    val others = partners.filter { !it.node.isSelf && it.node.lat != null }
    val selectable = partners.filter { it.node.lat != null }
    val focused = selectable.firstOrNull { it.node.num == focusId }
        ?: others.maxByOrNull { it.node.lastHeard } ?: selectable.firstOrNull()

    fun fitAll(why: String = "button") { com.victor.traillink.service.LinkRepo.log("Map: fit all ($why)"); follow = false; handle.fit(selectable.map { LatLng(it.node.lat!!, it.node.lon!!) } + listOfNotNull(phone?.let { LatLng(it.lat, it.lon) })) }
    val pins by vm.pins.collectAsState()
    val selPinId by vm.selectedPin.collectAsState()
    val navPinId by vm.nav.collectAsState()
    val selPin = pins.firstOrNull { it.id == selPinId }
    val navPin = pins.firstOrNull { it.id == navPinId }
    val myTrack by vm.myTrack.collectAsState()
    val backtrack by vm.backtrack.collectAsState()
    var showTracks by remember { mutableStateOf(false) }
    val btNext = backtrack?.let { b -> b.points.getOrNull(b.idx) }
    var newPinAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val kodiak by com.victor.traillink.data.KodiakData.state.collectAsState()
    LaunchedEffect(Unit) { com.victor.traillink.data.KodiakData.ensure(ctx) }
    var kSel by remember { mutableStateOf<com.victor.traillink.core.KFeature?>(null) }
    var showLayers by remember { mutableStateOf(false) }
    LaunchedEffect(selPinId) { if (selPinId != null) kSel = null }
    fun navigateTo(f: com.victor.traillink.core.KFeature, tapLat: Double, tapLon: Double) {
        val ph = phone; val t = if (ph != null) f.nearestTo(ph.lat, ph.lon) else doubleArrayOf(tapLat, tapLon)
        val now = System.currentTimeMillis()
        val name = (if (f.k == "row") f.props.optString("route") + " ROW" else f.props.optString("label").ifBlank { f.title }).take(30)
        val pin = com.victor.traillink.data.PinEntity(com.victor.traillink.core.PinMsg.newId(), name, com.victor.traillink.core.PinKind.OTHER.name,
            0xFFFFB300.toInt(), "Nearest point of ${f.title} (Kodiak layer)", t[0], t[1], null, "me", settings.myName.ifBlank { "Me" },
            now, now, now + 24 * 3_600_000L, shared = false, mine = true)
        com.victor.traillink.service.LinkRepo.log("Kodiak: navigate to ${f.title} at %.5f,%.5f".format(t[0], t[1]))
        vm.savePin(pin); vm.startNav(pin.id); kSel = null; follow = true; ph?.let { handle.flyTo(it.lat, it.lon) }
    }
    var kTap by remember { mutableStateOf(doubleArrayOf(0.0, 0.0)) }
    val inKodiakMe = phone?.let { com.victor.traillink.core.KodiakArea.contains(it.lat, it.lon) } == true
    val near = remember(phone?.lat, phone?.lon, kodiak, settings.easementCheck, settings.layerKodiak, inKodiakMe) {
        if (!settings.layerKodiak || !settings.easementCheck || !inKodiakMe || kodiak == null) null
        else phone?.let { com.victor.traillink.core.KGeo.nearestEasement(it.lat, it.lon, kodiak!!.features) }
    }
    var editPin by remember { mutableStateOf<com.victor.traillink.data.PinEntity?>(null) }
    fun dropHere() {
        val p = phone
        if (p == null) android.widget.Toast.makeText(ctx, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show() else newPinAt = p.lat to p.lon
    }
    val sosList by vm.sos.collectAsState()
    val sosPts = remember(sosList) { sosList.mapNotNull { m -> m.lat?.let { it to m.lon!! } } }
    fun focusOn(p: PartnerUi, why: String = "tap") { com.victor.traillink.service.LinkRepo.log("Map: focus ${p.node.longName} ($why)"); follow = false; vm.focus.value = p.node.num; handle.flyTo(p.node.lat!!, p.node.lon!!) }

    val lastKnown by vm.lastKnown.collectAsState()
    val armTick by handle.armTick.collectAsState()
    // Fallback only: no own position at all 8 s after the map is ready -> show the partners (does not disarm auto-centre).
    LaunchedEffect(mapReady, selectable.isNotEmpty(), armTick) {
        if (!mapReady || selectable.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(8000)
        if (handle.autoCenter.armed && !handle.centredOnce && phone == null && lastKnown == null) {
            fitAll("no own position after 8 s")
        }
    }
    LaunchedEffect(follow, phone?.lat, phone?.lon) { if (follow) phone?.let { handle.follow(it.lat, it.lon) } }

    BoxWithConstraints(Modifier.fillMaxSize().then(if (settings.fullscreen) Modifier.navigationBarsPadding() else Modifier)) {
        val wide = false   // Victor prefers map-on-top + bottom sheet on the unfolded screen too
        val density = LocalDensity.current
        val fullH = maxHeight
        val panelW = 0.dp
        val sheetHeights = listOf(64.dp, 250.dp, (fullH - bottomInset) * 0.72f)
        val sheetTarget = if (wide) 0.dp else sheetHeights[settings.sheetState.coerceIn(0, 2)]
        var dragDelta by remember { mutableStateOf(0.dp) }
        val sheetH by animateDpAsState(if (dragDelta != 0.dp) (sheetTarget + dragDelta).coerceIn(56.dp, fullH * 0.9f) else sheetTarget, label = "sheet")

        // keep map camera centred in the visible part (not under the sheet / panel)
        val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 36.dp   // status line + chips
        fun updateInsets() = with(density) {
            val sheet = if (wide) 0.dp else sheetTarget.coerceAtMost(fullH - topPad - 120.dp)
            handle.insets = intArrayOf(0, topPad.roundToPx(), panelW.roundToPx(), (sheet + bottomInset).roundToPx())
        }
        LaunchedEffect(sheetTarget, panelW, bottomInset, mapReady) { updateInsets() }

        // Auto-centre on "Me" at zoom 15: every cold start and after > 5 min in background, as soon as the map is
        // ready and a live fix or a < 10 min last-known position exists. Only real gestures disarm it.
        LaunchedEffect(mapReady, phone?.time, lastKnown?.time, armTick) {
            if (!mapReady || handle.map == null) return@LaunchedEffect
            updateInsets()
            val now = System.currentTimeMillis()
            val t = handle.autoCenter.decide(true,
                phone?.let { com.victor.traillink.core.AutoCenter.Pos(it.lat, it.lon, it.time, true) },
                lastKnown?.let { com.victor.traillink.core.AutoCenter.Pos(it.lat, it.lon, it.time, false) }, now) ?: return@LaunchedEffect
            com.victor.traillink.service.LinkRepo.log("Map: auto-centre on me (%s, age %ds) %.5f,%.5f z15 insets=%s".format(
                if (t.live) "live fix" else "last known", (now - t.timeMs) / 1000, t.lat, t.lon, handle.insets.joinToString(",")))
            handle.centredOnce = true
            handle.flyTo(t.lat, t.lon, 15.0)
        }

        TrailMap(Modifier.fillMaxSize(), handle, partners, phone, focused?.node?.num, settings.dimMap, mbtiles, sos = sosPts,
            pins = pins, selectedPin = selPinId ?: navPinId,
            navLine = btNext?.let { n -> phone?.let { ph -> LatLng(ph.lat, ph.lon) to LatLng(n.first, n.second) } }
                ?: navPin?.let { np -> phone?.let { ph -> LatLng(ph.lat, ph.lon) to LatLng(np.lat, np.lon) } },
            onPinTap = { id -> com.victor.traillink.service.LinkRepo.log("Map: pin tap $id"); vm.selectedPin.value = id; if (settings.sheetState == 0) Prefs.update(ctx) { it.copy(sheetState = 1) } },
            onLongPress = { la, lo -> newPinAt = la to lo },
            kodiak = kodiak, showEsmt = settings.layerKodiak && settings.layerEasements, showRow = settings.layerKodiak && settings.layerRow, showTrails = settings.layerKodiak && settings.layerTrails, showLog = settings.layerKodiak && settings.layerLogRoads,
            alaska = alaskaData.takeIf { settings.layerAlaska }, showAk17b = settings.layerAk17b, showAkRs = settings.layerAkRs2477, showAkDnr = settings.layerAkDnr,
            onKodiakTap = { f, la, lo ->
                com.victor.traillink.service.LinkRepo.log("Map: Kodiak feature tap ${f.title}")
                vm.selectedPin.value = null; kSel = f; kTap = doubleArrayOf(la, lo)
                if (settings.sheetState == 0) Prefs.update(ctx) { it.copy(sheetState = 1) }
            },
            tracksVisible = settings.tracksVisible, hiddenTracks = settings.hiddenTracks, myTrack = myTrack,
            route = backtrack?.points, routeIdx = backtrack?.idx ?: 0, worldRegions = worldRegions, mapMode = mapMode) { mapReady = true }

        // ---------------- top overlays
        Column(Modifier.fillMaxWidth().padding(end = panelW).statusBarsPadding()) {
            LocationBanner(vm, compact = true)
            val sharing = settings.netSharing && settings.netReady && settings.internetAllowed
            val parts = listOfNotNull(
                if (sharing) "🌐 ${link.net.label}" + (if (link.lastPublish > 0) " · sent ${Geo.formatAge(now - link.lastPublish)}" else "") else null,
                if (link.conn != Conn.IDLE || !sharing) "📡 ${link.lookingFor?.let { "Looking for $it…" } ?: link.conn.label}" else null)
            Row(Modifier.padding(start = 8.dp, top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Glass)
                .clickable { if (sharing || settings.netReady) onGoInternet() else onGoRadio() }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(parts.joinToString("   "), fontSize = 12.sp, color = Color(0xFFCFD8DC), maxLines = 1)
            }
            easementCheckText(near, settings.metric)?.let { (t, on) ->
                Row(Modifier.padding(start = 8.dp, top = 4.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xE01B5E20) else Glass)
                    .clickable { kSel = near!!.f; if (settings.sheetState == 0) Prefs.update(ctx) { it.copy(sheetState = 1) } }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text((if (on) "🟢 " else "⚪ ") + t, fontSize = 12.sp, color = Color.White, maxLines = 1)
                }
            }
            if (selectable.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 56.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectable.forEach { p ->
                        val sel = p.node.num == focused?.node?.num
                        Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) Color(0xF0263238) else Glass)
                            .border(if (sel) 1.5.dp else 0.dp, Color(p.color), RoundedCornerShape(14.dp))
                            .clickable { focusOn(p, "chip") }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(p.color, 10.dp); Spacer(Modifier.width(5.dp))
                            Text(if (p.node.isSelf) "My radio" else p.node.shortName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ---------------- floating map buttons: ride on the sheet's top edge; overflow menu when there's no room
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val btnColumnH = 42.dp * 8 + 8.dp * 7
        val bottomOfButtons = (if (wide) 0.dp else sheetH) + bottomInset + 8.dp
        val roomForColumn = fullH - bottomOfButtons - topInset - 8.dp
        fun followTap() {
            val p = phone
            if (p == null) android.widget.Toast.makeText(ctx, "No GPS fix yet", android.widget.Toast.LENGTH_SHORT).show()
            else if (!follow) { follow = true; handle.flyTo(p.lat, p.lon, cam.zoom.coerceAtLeast(14.0)) } else follow = false
        }
        if (roomForColumn >= btnColumnH) {
            Column(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomOfButtons, end = panelW + 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapIconButton(Modifier.rotate(-cam.bearing.toFloat()), "Compass: tap for north up", onClick = { handle.northUp() }) {
                    Text("▲", color = Color(0xFFFF5252), fontSize = 12.sp, modifier = Modifier.offset(y = (-7).dp))
                    Text("N", fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.offset(y = 5.dp))
                }
                MapIconButton(active = follow, desc = "Follow me", target = "gps", onClick = { followTap() }) {
                    Text(if (follow) "◉" else "◎", fontSize = 20.sp, color = if (phone == null) Color(0xFF78909C) else Color(0xFF82B1FF))
                }
                MapIconButton(desc = "Fit all", onClick = { fitAll() }) { Text("⤢", fontSize = 20.sp) }
                MapIconButton(desc = "Drop pin at my location", target = "pins", onClick = { dropHere() }) { Text("📍", fontSize = 17.sp) }
                MapIconButton(active = backtrack != null, desc = "Tracks and Backtrack", target = "tracks", onClick = { showTracks = true }) { Text("👣", fontSize = 17.sp) }
                MapIconButton(desc = "Download map area", onClick = { selectArea = true }) { Text("⬇", fontSize = 18.sp) }
                MapIconButton(desc = "Layers", target = "layers", onClick = { showLayers = true }) { Text("◫", fontSize = 18.sp) }
                MapIconButton(active = settings.fullscreen, desc = "Fullscreen", onClick = { Prefs.update(ctx) { it.copy(fullscreen = !it.fullscreen) } }) {
                    Text(if (settings.fullscreen) "⊡" else "⛶", fontSize = 18.sp)
                }
            }
        } else {
            var menu by remember { mutableStateOf(false) }
            val bottomPad = bottomOfButtons.coerceAtMost(fullH - topInset - 8.dp - 42.dp)
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomPad, end = panelW + 8.dp)) {
                MapIconButton(desc = "Map controls", active = menu, target = "layers", onClick = { menu = true }) { Text("⋯", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("North up") }, { menu = false; handle.northUp() })
                    DropdownMenuItem({ Text(if (follow) "Stop following me" else "Follow me") }, { menu = false; followTap() })
                    DropdownMenuItem({ Text("Fit all") }, { menu = false; fitAll() })
                    DropdownMenuItem({ Text("📍 Drop pin at my location") }, { menu = false; dropHere() })
                    DropdownMenuItem({ Text("👣 Tracks & ↩ Backtrack…") }, { menu = false; showTracks = true })
                    DropdownMenuItem({ Text("⬇ Download map area") }, { menu = false; selectArea = true })
                    DropdownMenuItem({ Text("Layers…") }, { menu = false; showLayers = true })
                    DropdownMenuItem({ Text(if (settings.fullscreen) "Exit fullscreen" else "Fullscreen") }, { menu = false; Prefs.update(ctx) { it.copy(fullscreen = !it.fullscreen) } })
                    DropdownMenuItem({ Text("Tutorial") }, { menu = false; Onboarding.startTour() })
                    DropdownMenuItem({ Text("Privacy") }, { menu = false; Onboarding.openPrivacy() })
                }
            }
        }

        UpdateButton(Modifier.align(Alignment.BottomCenter).padding(bottom = (if (wide) 0.dp else sheetH) + bottomInset + 22.dp))

        // ---------------- scale bar (bottom-left, above the sheet)
        ScaleBar(cam.metersPerDp, settings.metric,
            Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = (if (wide) 0.dp else sheetH) + bottomInset + 8.dp))
        val hasTerrain = worldRegions.any { it.complete && it.terrain }
        base.note?.let { note ->
            Text(note, Modifier.align(Alignment.BottomStart).padding(start = 110.dp, end = 56.dp, bottom = (if (wide) 0.dp else sheetH) + bottomInset + 26.dp)
                .clip(RoundedCornerShape(6.dp)).background(Glass).padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 11.sp, color = Color(0xFFFFD54F), maxLines = 2, lineHeight = 13.sp)
        }
        Text(com.victor.traillink.map.world.WorldStyle.attribution(mapMode, hasTerrain) + if (!online) "  · offline" else "",
            Modifier.align(Alignment.BottomStart).padding(start = 110.dp, end = 56.dp, bottom = (if (wide) 0.dp else sheetH) + bottomInset + 2.dp)
                .background(Color(0x66000000)).padding(horizontal = 3.dp),
            fontSize = 9.sp, color = Color(0xCCFFFFFF), maxLines = 2, lineHeight = 10.sp)

        // ---------------- info: bottom sheet (phone) or side panel (unfolded)
        if (!wide) {
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 840.dp).fillMaxWidth().padding(bottom = bottomInset).height(sheetH).tourTarget("sheet")
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(GlassDark)) {
                val drag = rememberDraggableState { px -> dragDelta -= with(density) { px.toDp() } }
                Box(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical,
                    onDragStopped = {
                        val h = sheetTarget + dragDelta
                        val nearest = sheetHeights.indices.minBy { abs((sheetHeights[it] - h).value) }
                        Prefs.update(ctx) { s -> s.copy(sheetState = nearest) }
                        dragDelta = 0.dp
                    }).clickable { Prefs.update(ctx) { s -> s.copy(sheetState = if (s.sheetState == 0) 1 else 0) } }) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF607D8B)))
                    if (settings.sheetState == 0 && dragDelta == 0.dp || sheetH < 90.dp)
                        Box(Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                            if (backtrack != null) BacktrackPanel(backtrack!!, phone, heading, settings.metric, settings.rangeSpeedMps.toDouble(), compact = true) { vm.stopBacktrack() }
                            else if (navPin != null) NavPanel(navPin, phone, heading, settings.metric, settings.rangeSpeedMps.toDouble(), compact = true) { vm.stopNav() }
                            else if (kSel != null) KodiakStrip(kSel!!, phone, settings.metric)
                            else if (selPin != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${pinEmoji(selPin)} ${selPin.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(phone?.let { Geo.formatDistance(Geo.distanceM(it.lat, it.lon, selPin.lat, selPin.lon), settings.metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
                            }
                            else CollapsedStrip(focused, phone, heading, now, settings.metric, settings.lostMinutes)
                        }
                    else Spacer(Modifier.fillMaxWidth().height(16.dp))
                }
                if (sheetH >= 90.dp) Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    if (backtrack != null) BacktrackPanel(backtrack!!, phone, heading, settings.metric, settings.rangeSpeedMps.toDouble(), compact = false) { vm.stopBacktrack() }
                    else if (navPin != null) NavPanel(navPin, phone, heading, settings.metric, settings.rangeSpeedMps.toDouble(), compact = false) { vm.stopNav() }
                    else if (kSel != null) KodiakDetails(kSel!!, phone, settings.metric, onNavigate = { navigateTo(kSel!!, kTap[0], kTap[1]) }, onClose = { kSel = null })
                    else if (selPin != null) PinDetails(selPin, phone, heading, settings.metric,
                        onNavigate = { vm.startNav(selPin.id); vm.selectedPin.value = null; follow = true; phone?.let { handle.flyTo(it.lat, it.lon) } },
                        onEdit = { editPin = selPin }, onDelete = { vm.deletePin(selPin) }, onClose = { vm.selectedPin.value = null })
                    else Details(focused, selectable, phone, heading, now, settings.metric, settings.lostMinutes, expanded = sheetH > 300.dp,
                        onFocus = { focusOn(it, "sheet list") }, onGoInternet = onGoInternet, onGoRadio = onGoRadio, onClearTrail = { vm.clearTrail(it) },
                        trackHidden = focused?.node?.num?.let { it in settings.hiddenTracks || !settings.tracksVisible } ?: false,
                        onToggleTrack = { num -> if (!settings.tracksVisible) vm.setTracksVisible(true) else vm.setTrackHidden(num, num !in settings.hiddenTracks) },
                        onBacktrack = { num -> vm.backtrackPartner(num)?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
                            ?: run { follow = true; phone?.let { handle.flyTo(it.lat, it.lon) } } })
                }
            }
        }
        if (selectArea) AreaSelectOverlay(handle, bottomPad = bottomInset, topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
            onUsgs = { selectArea = false; showDownload = true }) { selectArea = false }
    }

    if (showDownload) DownloadDialog(handle) { showDownload = false }
    if (showLayers) LayersDialog { showLayers = false }
    if (showTracks) TracksDialog(vm, onBacktrackStarted = { follow = true; phone?.let { handle.flyTo(it.lat, it.lon) } }) { showTracks = false }
    newPinAt?.let { (la, lo) ->
        PinDialog(la, lo, null, null, onSave = { vm.savePin(it); newPinAt = null; vm.selectedPin.value = it.id }, onDismiss = { newPinAt = null })
    }
    editPin?.let { e -> PinDialog(e.lat, e.lon, e.alt, e, onSave = { vm.savePin(it); editPin = null }, onDismiss = { editPin = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapIconButton(modifier: Modifier = Modifier, desc: String, active: Boolean = false, target: String? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box((if (target != null) Modifier.tourTarget(target) else Modifier).size(42.dp).clip(CircleShape).background(if (active) Color(0xF01565C0) else Glass)
        .combinedClickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(modifier, contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun ScaleBar(metersPerDp: Double, metric: Boolean, modifier: Modifier) {
    if (metersPerDp <= 0) return
    val maxDp = 90.0
    val maxM = metersPerDp * maxDp
    val (len, label) = if (metric) {
        val v = nice(maxM); (v / metersPerDp) to (if (v >= 1000) "${fmt(v / 1000)} km" else "${v.roundToInt()} m")
    } else {
        val ft = maxM * 3.28084
        if (ft < 2640) { val v = nice(ft); (v / 3.28084 / metersPerDp) to "${v.roundToInt()} ft" }
        else { val v = nice(maxM / 1609.344); (v * 1609.344 / metersPerDp) to "${fmt(v)} mi" }
    }
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Box(Modifier.width(len.dp).height(6.dp).border(1.5.dp, Color.White).background(Color(0x66000000)))
    }
}
private fun nice(x: Double): Double { val e = 10.0.pow(floor(log10(x))); val f = x / e; return e * when { f >= 5 -> 5.0; f >= 2 -> 2.0; else -> 1.0 } }
private fun fmt(v: Double) = if (v == floor(v)) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun CollapsedStrip(p: PartnerUi?, phone: PhoneFix?, heading: Float?, now: Long, metric: Boolean, lostMin: Int) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p == null) {
            Text("No partners yet", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(phone?.let { "Me ±${it.accuracy?.let { a -> Geo.formatDistance(a.toDouble(), metric) } ?: "?"}" } ?: "Me: no fix", fontSize = 13.sp, color = Color(0xFFB0BEC5))
            return@Row
        }
        val n = p.node
        ColorDot(p.color, 14.dp); Spacer(Modifier.width(8.dp))
        Text(if (n.isSelf) "My radio" else n.longName, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SourceBadge(n.posSource, small = true); Spacer(Modifier.width(8.dp))
        Text(p.distanceM?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(6.dp))
        if (p.bearingDeg != null) BearingArrow((p.bearingDeg - (heading ?: 0f)).toFloat(), Color(p.color), 26.dp)
        Spacer(Modifier.width(6.dp))
        val age = now - n.lastHeard
        Text(if (n.lastHeard == 0L) "never" else shortAge(age), fontSize = 13.sp, color = ageColor(age, lostMin))
        p.range(now, Prefs.settings.value)?.let { (r, a, m) ->
            Text(" ⭕" + Geo.formatDistance(r, metric) + if (Range.capped(a, m)) "+" else "", fontSize = 12.sp, color = Color(p.color))
        }
    }
}

private fun shortAge(ms: Long): String { val s = ms / 1000; return when { s < 60 -> "${s.coerceAtLeast(0)}s"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" } }

@Composable
private fun Details(focused: PartnerUi?, all: List<PartnerUi>, phone: PhoneFix?, heading: Float?, now: Long, metric: Boolean, lostMin: Int, expanded: Boolean,
                    onFocus: (PartnerUi) -> Unit, onGoInternet: () -> Unit, onGoRadio: () -> Unit, onClearTrail: (Long) -> Unit = {},
                    trackHidden: Boolean = false, onToggleTrack: (Long) -> Unit = {}, onBacktrack: (Long) -> Unit = {}) {
    var confirmClear by remember { mutableStateOf(false) }
    if (focused == null) {
        Text("No partner positions yet", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(phone?.let { "My position: %.5f, %.5f (±%s)".format(it.lat, it.lon, it.accuracy?.let { a -> Geo.formatDistance(a.toDouble(), metric) } ?: "?") }
            ?: "My position: no fix yet", fontSize = 13.sp, color = Color(0xFFB0BEC5))
        Text("Partners on your passcode show up here — over the internet or a radio.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onGoInternet, Modifier.weight(1f)) { Text("Internet") }
            OutlinedButton(onGoRadio, Modifier.weight(1f)) { Text("Radio") }
        }
        return
    }
    val n = focused.node
    val age = now - n.lastHeard
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorDot(focused.color, 16.dp); Spacer(Modifier.width(8.dp))
        Text(if (n.isSelf) "My radio · ${n.longName}" else n.longName, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SourceBadge(n.posSource, small = true)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(focused.distanceM?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp)
            Text(focused.bearingDeg?.let { "${Geo.cardinal(it)}  ${it.roundToInt()}°" } ?: if (phone == null) "Waiting for my GPS" else "",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        if (focused.bearingDeg != null) BearingArrow((focused.bearingDeg - (heading ?: 0f)).toFloat(), Color(focused.color), 60.dp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Heard", if (n.lastHeard == 0L) "never" else Geo.formatAge(age), ageColor(age, lostMin))
        Stat("Battery", batteryText(n.battery), if ((n.battery ?: 100) < 20) Color(0xFFFF5252) else Color.Unspecified)
        Stat("Signal", if (n.isSelf) "local" else listOfNotNull(n.snr?.let { "%.1f dB".format(it) }, n.rssi?.let { "$it" }).joinToString(" / ").ifBlank { "—" })
        Stat("Alt", Geo.formatAltitude(n.altM, metric) ?: "—")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (n.lat != null) Text("%.5f, %.5f".format(n.lat, n.lon) + (n.accM?.let { "  ±" + Geo.formatDistance(it.toDouble(), metric) + if (it > 100) " (approx.)" else "" } ?: ""),
            fontSize = 12.sp, color = Color(0xFF90A4AE), modifier = Modifier.weight(1f))
    }
    if (focused.trail.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ onToggleTrack(n.num) }) { Text(if (trackHidden) "👣 Show track" else "👣 Hide track", fontSize = 12.sp) }
        if (!n.isSelf) TextButton({ onBacktrack(n.num) }) { Text("↩ Follow track to them", fontSize = 12.sp) }
        TextButton({ confirmClear = true }) { Text("Clear track…", fontSize = 12.sp) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear track history") },
        text = { Text("Delete ${if (n.isSelf) "my radio" else n.longName}'s recorded track? Partners, chats and pins are kept. This can't be undone.") },
        confirmButton = { TextButton({ onClearTrail(n.num); confirmClear = false }) { Text("Clear", color = Color(0xFFFF5252)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
    focused.range(now, Prefs.settings.value)?.let { (r, a, m) ->
        Text("⭕ " + Range.label(r, a, m, metric).replaceFirstChar { it.uppercase() }, fontSize = 13.sp, color = Color(focused.color))
    }
    if (expanded && all.size > 1) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("All partners", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        all.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onFocus(p) }, verticalAlignment = Alignment.CenterVertically) {
                ColorDot(p.color, 12.dp); Spacer(Modifier.width(8.dp))
                Text(if (p.node.isSelf) "My radio" else p.node.longName, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                SourceBadge(p.node.posSource, small = true); Spacer(Modifier.width(8.dp))
                Text(p.distanceM?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(if (p.node.lastHeard == 0L) "—" else shortAge(now - p.node.lastHeard), fontSize = 12.sp, color = ageColor(now - p.node.lastHeard, lostMin))
            }
        }
    }
    if (expanded) phone?.let {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Me: %.5f, %.5f  ±%s".format(it.lat, it.lon, it.accuracy?.let { a -> Geo.formatDistance(a.toDouble(), metric) } ?: "?"), fontSize = 12.sp, color = Color(0xFFB0BEC5))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF90A4AE))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun DownloadDialog(handle: MapHandle, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val bounds = remember { handle.visibleBounds() }
    val z = handle.zoom().roundToInt()
    var name by remember { mutableStateOf("Area ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date())}") }
    var minZ by remember { mutableFloatStateOf((z - 3).coerceIn(6, 14).toFloat()) }
    var maxZ by remember { mutableFloatStateOf(15f.coerceAtLeast(minZ)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val est = bounds?.let { OfflineMaps.estimate(it, minZ.roundToInt(), maxZ.roundToInt()) } ?: 0
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Save USGS topo for offline (US only)") },
        text = {
            Column {
                if (bounds == null) Text("Map not ready")
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Text("Zoom levels ${minZ.roundToInt()} – ${maxZ.roundToInt()} (16 = most detail)", Modifier.padding(top = 10.dp))
                Text("From"); Slider(minZ, { minZ = it.coerceAtMost(maxZ) }, valueRange = 4f..16f, steps = 11)
                Text("To"); Slider(maxZ, { maxZ = it.coerceAtLeast(minZ) }, valueRange = 4f..16f, steps = 11)
                Text("≈ $est tiles, about ${(est * 18 / 1024).coerceAtLeast(1)} MB", fontWeight = FontWeight.Bold,
                    color = if (est > OfflineMaps.MAX_TILES_PER_REGION) Color(0xFFFF5252) else Color.Unspecified)
                Text("Downloads from the USGS basemap on Wi-Fi only, limit ${OfflineMaps.MAX_TILES_PER_REGION} tiles per area.", fontSize = 14.sp)
                msg?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = bounds != null, onClick = {
                OfflineMaps.download(ctx, name, bounds!!, minZ.roundToInt(), maxZ.roundToInt(), density) { err ->
                    if (err == null) onDismiss() else msg = err
                }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
