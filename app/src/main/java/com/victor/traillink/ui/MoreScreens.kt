package com.victor.traillink.ui

import kotlin.math.roundToInt
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.BuildConfig
import com.victor.traillink.data.Prefs
import com.victor.traillink.map.OfflineMaps
import com.victor.traillink.service.LinkService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MapsScreen() {
    val ctx = LocalContext.current
    val regions by OfflineMaps.regions.collectAsState()
    val mb by OfflineMaps.mbtiles.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { msg = OfflineMaps.importMbtiles(ctx, uri) ?: "Imported ✓ (shown on top of the USGS map)" }
    }
    LaunchedEffect(Unit) { OfflineMaps.refresh(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline maps", style = MaterialTheme.typography.titleLarge)
        WorldAreasSection()
        HorizontalDivider()
        Text("USGS topo areas (US only)", style = MaterialTheme.typography.titleMedium)
        Text("Used when the base map is set to USGS topo (Map → ◫ Layers). To save one: ⬇ on the map, then “USGS topo (US)…”. Wi-Fi only.", fontSize = 14.sp)
        if (regions.isEmpty()) Text("No USGS areas.", color = Color(0xFF90A4AE))
        regions.forEach { r ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Zoom ${r.minZoom}–${r.maxZoom} · ${r.completed}/${r.required} tiles · ${r.bytes / 1_048_576} MB")
                    if (r.required > 0 && !r.complete) LinearProgressIndicator(progress = { (r.completed.toFloat() / r.required).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text(when { r.complete -> "Ready offline ✓"; r.active -> "Downloading…"; else -> "Paused" },
                        color = if (r.complete) Color(0xFF69F0AE) else Color(0xFFFFD740))
                    r.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!r.complete) TextButton({ if (r.active) OfflineMaps.pause(r.id) else OfflineMaps.resume(ctx, r.id) }) { Text(if (r.active) "Pause" else "Resume") }
                        TextButton({ OfflineMaps.delete(r.id) }) { Text("Delete") }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("MBTiles", style = MaterialTheme.typography.titleLarge)
        Text("Import a raster .mbtiles file (for example USGS topo exported from QGIS or MOBAC). It's copied into the app and drawn over the base map.", fontSize = 16.sp)
        BigButton("Import MBTiles…", Modifier.fillMaxWidth()) { picker.launch(arrayOf("*/*")) }
        msg?.let { Text(it) }
        mb.forEach { m ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("${m.format} · zoom ${m.minZoom ?: "?"}–${m.maxZoom ?: "?"} · ${m.sizeBytes / 1_048_576} MB", fontSize = 14.sp)
                    }
                    TextButton({ OfflineMaps.deleteMbtiles(ctx, m.file) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun MoreScreen(vm: AppViewModel, onAbout: () -> Unit, onMaps: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) scope.launch { msg = vm.exportGpx(ctx, uri) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        SettingSwitch("Lost-contact alarm", "Alarm when a partner isn't heard for ${s.lostMinutes} min (per partner; mute one on the Partners tab)", s.lostAlarm) { v -> Prefs.update(ctx) { it.copy(lostAlarm = v) } }
        Text("Alarm after ${s.lostMinutes} min of silence")
        Slider(s.lostMinutes.toFloat(), { v -> Prefs.update(ctx) { it.copy(lostMinutes = v.toInt()) } }, valueRange = 5f..120f)
        // ---- possible-range circle
        Text("Possible-range circle", fontWeight = FontWeight.Bold)
        Text("Each partner's circle starts at their GPS accuracy and grows at this pace since their last fix (faster if they were recently moving faster).", fontSize = 13.sp, color = Color(0xFF90A4AE))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.victor.traillink.core.Range.PRESETS.forEach { pr ->
                FilterChip(kotlin.math.abs(s.rangeSpeedMps - pr.mps) < 0.01, { Prefs.update(ctx) { it.copy(rangeSpeedMps = pr.mps.toFloat()) } }, { Text(pr.label) })
            }
        }
        val isPreset = com.victor.traillink.core.Range.PRESETS.any { kotlin.math.abs(s.rangeSpeedMps - it.mps) < 0.01 }
        val unitMps = if (s.metric) 1 / 3.6 else 0.44704
        var custom by remember(s.rangeSpeedMps, s.metric) { mutableStateOf("%.1f".format(s.rangeSpeedMps / unitMps)) }
        OutlinedTextField(custom, { v -> custom = v; v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..200.0 }?.let { x -> Prefs.update(ctx) { it.copy(rangeSpeedMps = (x * unitMps).toFloat()) } } },
            label = { Text("Custom pace (${if (s.metric) "km/h" else "mph"})" + if (isPreset) "" else " ✓") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Stop growing after ${if (s.rangeMaxMin >= 60) "%.1f h".format(s.rangeMaxMin / 60.0) else "${s.rangeMaxMin} min"}")
        Slider(s.rangeMaxMin.toFloat(), { v -> Prefs.update(ctx) { it.copy(rangeMaxMin = (v / 15).roundToInt() * 15) } }, valueRange = 15f..720f)
        Text("Show trails from the last ${s.trailHours} h")
        Slider(s.trailHours.toFloat(), { v -> Prefs.update(ctx) { it.copy(trailHours = v.toInt()) } }, valueRange = 1f..168f)
        SettingSwitch("Metric units", "km and m instead of miles and feet", s.metric) { v -> Prefs.update(ctx) { it.copy(metric = v) } }
        SettingSwitch("Dim map", "Darker topo map for night use", s.dimMap) { v -> Prefs.update(ctx) { it.copy(dimMap = v) } }
        SettingSwitch("Keep screen on", "While Sovereign Comms is open", s.keepScreenOn) { v -> Prefs.update(ctx) { it.copy(keepScreenOn = v) } }
        SettingSwitch("Auto-play voice", "Play push-to-talk as it arrives (off = notify only; replay from Chat)", s.voiceAutoPlay) { v -> Prefs.update(ctx) { it.copy(voiceAutoPlay = v) } }
        SettingSwitch("Volume-down = push-to-talk", "Hold the volume-down key to talk while Sovereign Comms is open (to the open chat, else the group)", s.volumeKeyPtt) { v -> Prefs.update(ctx) { it.copy(volumeKeyPtt = v) } }
        SettingSwitch("Auto-reconnect", "Reconnect to the last radio if the link drops", s.autoReconnect) { v -> Prefs.update(ctx) { it.copy(autoReconnect = v) } }
        HorizontalDivider()
        CallSettings()
        HorizontalDivider()
        PartnerCleanupSection(vm)
        HorizontalDivider()
        BigButton("Export trails as GPX", Modifier.fillMaxWidth()) {
            gpx.launch("sovereign-comms-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.gpx")
        }
        msg?.let { Text(it) }
        BigButton("Clear real trails", Modifier.fillMaxWidth(), primary = false) { confirmClear = true }
        BigButton("Offline maps (saved areas, MBTiles)", Modifier.fillMaxWidth(), primary = false) { onMaps() }
        BigButton("Tutorial", Modifier.fillMaxWidth(), primary = false) { Onboarding.startTour() }
        BigButton("Privacy", Modifier.fillMaxWidth(), primary = false) { Onboarding.openPrivacy() }
        UpdateCheckRow()
        BigButton("About & licenses", Modifier.fillMaxWidth(), primary = false) { onAbout() }
        Text("Sovereign Comms ${BuildConfig.VERSION_NAME}", color = Color(0xFF90A4AE))
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete all recorded trails?") },
        text = { Text("Partners stay; their breadcrumb history is removed. Export GPX first if you want to keep it.") },
        confirmButton = { TextButton({ vm.clearTrails(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun SettingSwitch(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 14.sp, color = Color(0xFF90A4AE)) }
        Switch(v, on)
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sovereign Comms ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
        Text("Position sharing over Meshtastic LoRa radios off-grid, and over the internet where there's service. Made for Victor.")
        Text("License", fontWeight = FontWeight.Bold)
        Text("Sovereign Comms is free software under the GNU General Public License v3.0 (GPL-3.0). It uses the Meshtastic protobuf definitions (meshtastic/protobufs v2.8.1, © Meshtastic LLC, GPL-3.0). If you share this app, share its source under GPL-3.0 too. Source: /workspace/trail-link.")
        Text("Meshtastic® is a registered trademark of Meshtastic LLC. Sovereign Comms is not affiliated with or endorsed by Meshtastic.", fontSize = 14.sp)
        Text("Map data", fontWeight = FontWeight.Bold)
        Text("World map (2.3): map data © OpenStreetMap contributors, available under the Open Database License (openstreetmap.org/copyright). Online tiles: OpenFreeMap (openfreemap.org) © OpenMapTiles. Downloaded areas: Protomaps basemap (protomaps.com), served from Source Cooperative. Terrain shading: Mapterhorn (mapterhorn.com/attribution; Copernicus, USGS 3DEP and national elevation agencies). Fonts: Noto Sans (SIL Open Font License).")
        Text("Topographic base map (US): USGS The National Map — USGSTopo basemap (3DEP elevation, National Hydrography Dataset, Geographic Names Information System, National Boundaries/Transportation/Structures datasets, U.S. Census TIGER/Line, USFS roads, Natural Earth). U.S. Government public domain. Courtesy of the U.S. Geological Survey.")
        Text("Imported MBTiles keep their own licenses (e.g. OpenTopoMap: © OpenStreetMap contributors, SRTM | style © OpenTopoMap, CC-BY-SA).", fontSize = 15.sp)
        Text("Open-source components", fontWeight = FontWeight.Bold)
        Text("MapLibre Native Android (BSD-2-Clause), Protocol Buffers lite (BSD-3-Clause), AndroidX / Jetpack Compose / Room (Apache-2.0), Kotlin coroutines (Apache-2.0), OkHttp (Apache-2.0), WebRTC (BSD-3-Clause, © The WebRTC project authors) via stream-webrtc-android (Apache-2.0) for calls.", fontSize = 15.sp)
        Text("Radio", fontWeight = FontWeight.Bold)
        Text("Internet sharing (1.1): MQTT over TLS (default broker.hivemq.com public broker, no uptime guarantee; configurable). Positions are end-to-end encrypted with AES-256-GCM using a key derived from your passcode; the broker only sees an anonymous topic and message timing. Eclipse Paho MQTT client (EPL-2.0/EDL-1.0).", fontSize = 15.sp)
        Text("Calls (2.0): WebRTC audio (Opus) encrypted end to end with DTLS-SRTP between the two phones. Call setup (offer/answer/ICE) is AES-256-GCM encrypted with your group key and sent over the same MQTT broker, not retained. STUN default: stun.l.google.com (only sees your public address); optional TURN relay is your own and only forwards encrypted audio.", fontSize = 15.sp)
        Text("Uses the 902–928 MHz ISM band under FCC Part 15 — no license needed. Ham mode is not used (it disables encryption).", fontSize = 15.sp)
        BigButton("Back", Modifier.fillMaxWidth(), primary = false) { onBack() }
    }
}

/** More → Calls (2.0). */
@Composable
private fun CallSettings() {
    val ctx = LocalContext.current
    val s by Prefs.settings.collectAsState()
    Text("Calls", style = MaterialTheme.typography.titleLarge)
    SettingSwitch("App-to-app calls", "Live calls with partners over Wi-Fi or cell data. Open a partner's chat and tap 📞 Call. Both phones need Internet sharing on.", s.callsEnabled) { v -> Prefs.update(ctx) { it.copy(callsEnabled = v) } }
    if (!s.callsEnabled) return
    var fsi by remember { mutableStateOf(com.victor.traillink.call.CallManager.canRingFullScreen(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { fsi = com.victor.traillink.call.CallManager.canRingFullScreen(ctx); onPauseOrDispose { } }
    if (!fsi) {
        Text("⚠ Calls can't show on the lock screen yet. Allow \"full screen notifications\" so a call wakes the phone with Answer / Decline.", fontSize = 14.sp, color = Color(0xFFFFB74D))
        BigButton("Allow calls on the lock screen", Modifier.fillMaxWidth(), primary = false) { runCatching { ctx.startActivity(com.victor.traillink.call.CallManager.fullScreenSettingsIntent(ctx)) } }
    } else Text("✓ Calls ring and show on the lock screen.", fontSize = 14.sp, color = Color(0xFF69F0AE))
    Text("Relay server (TURN) — optional", fontWeight = FontWeight.Bold)
    Text("Calls try to connect the two phones directly. Some networks block that, especially Starlink and many cell carriers (they share one internet address between many customers, called CGNAT). If calls ring but never connect, add a relay (TURN) server here, on both phones. You can run your own for about \$5 a month on a small VPS with the free program coturn. The relay only passes along locked audio; it can't listen in. Leave blank to not use one.",
        fontSize = 14.sp, color = Color(0xFF90A4AE))
    var url by remember { mutableStateOf(s.turnUrl) }
    var user by remember { mutableStateOf(s.turnUser) }
    var pass by remember { mutableStateOf(s.turnPass) }
    var stun by remember { mutableStateOf(s.stunUrl) }
    OutlinedTextField(url, { url = it.trim() }, label = { Text("TURN server, e.g. turn:my-vps.com:3478") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it.trim() }, label = { Text("TURN username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pass, { pass = it }, label = { Text("TURN password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    OutlinedTextField(stun, { stun = it.trim() }, label = { Text("STUN server (finds your public address)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val dirty = url != s.turnUrl || user != s.turnUser || pass != s.turnPass || stun != s.stunUrl
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ Prefs.update(ctx) { it.copy(turnUrl = url, turnUser = user, turnPass = pass, stunUrl = stun.ifBlank { com.victor.traillink.data.Settings.DEFAULT_STUN }) }
            android.widget.Toast.makeText(ctx, "Call settings saved", android.widget.Toast.LENGTH_SHORT).show() }, enabled = dirty) { Text("Save") }
        TextButton({ stun = com.victor.traillink.data.Settings.DEFAULT_STUN }) { Text("Default STUN") }
    }
    if (s.turnUrl.isNotBlank())
        SettingSwitch("Always use the relay", "Every call goes through your TURN server. Hides your internet address from the other phone; uses the relay's data.", s.turnOnly) { v -> Prefs.update(ctx) { it.copy(turnOnly = v) } }
}

/** 2.3 worldwide downloaded areas: size, progress, pause/resume, delete. */
@Composable
fun WorldAreasSection() {
    val ctx = LocalContext.current
    val list by com.victor.traillink.map.world.WorldMaps.regions.collectAsState()
    val unmetered by com.victor.traillink.map.world.WorldMaps.unmetered.collectAsState()
    val online by com.victor.traillink.map.world.WorldMaps.online.collectAsState()
    val s by com.victor.traillink.data.Prefs.settings.collectAsState()
    LaunchedEffect(Unit) { com.victor.traillink.map.world.WorldMaps.init(ctx) }
    var del by remember { mutableStateOf<com.victor.traillink.map.world.WorldRegion?>(null) }
    val W = com.victor.traillink.map.world.WorldRegion
    Text("World map areas", style = MaterialTheme.typography.titleMedium)
    Text("Street map (OpenStreetMap) for anywhere in the world. Online the map loads by itself; download the areas you need without signal: on the Map tab tap ⬇, set the box and detail, then Download. Downloaded areas are used automatically when you're offline.", fontSize = 14.sp)
    Row(Modifier.fillMaxWidth().clickable { com.victor.traillink.data.Prefs.update(ctx) { it.copy(mapsMobileData = !it.mapsMobileData) } }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Download on mobile data", fontSize = 15.sp)
            Text(if (s.mapsMobileData) "Downloads also run on mobile data" else "Downloads wait for Wi-Fi", fontSize = 12.sp, color = Color(0xFF90A4AE))
        }
        Switch(s.mapsMobileData, { v -> com.victor.traillink.data.Prefs.update(ctx) { it.copy(mapsMobileData = v) } })
    }
    if (!online) Text("No internet now: downloads continue when you're back online.", color = Color(0xFFFFD740), fontSize = 13.sp)
    else if (!unmetered && !s.mapsMobileData) Text("On mobile data: downloads wait for Wi-Fi.", color = Color(0xFFFFD740), fontSize = 13.sp)
    val total = list.sumOf { com.victor.traillink.map.world.WorldMaps.diskBytes(ctx, it.id) }
    Text("World overview (built in, zoom 0–4) · ${list.size} area${if (list.size == 1) "" else "s"} · ${fmtBytes(total)} used · ${fmtBytes(com.victor.traillink.map.world.WorldMaps.dir(ctx).usableSpace)} free",
        fontSize = 13.sp, color = Color(0xFF90A4AE))
    list.forEach { r ->
        Card(shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(r.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                val det = com.victor.traillink.map.world.Detail.entries.firstOrNull { it.maxZoom == r.maxZoom }?.label ?: "zoom ${r.maxZoom}"
                Text("$det detail" + (if (r.terrain) " + terrain" else "") + " · " +
                    (if (r.complete) fmtBytes(com.victor.traillink.map.world.WorldMaps.diskBytes(ctx, r.id)) else "${fmtBytes(r.bytesDone)} of ${fmtBytes(r.bytesTotal)}"), fontSize = 14.sp)
                Text("%.3f, %.3f to %.3f, %.3f".format(r.bbox.south, r.bbox.west, r.bbox.north, r.bbox.east), fontSize = 11.sp, color = Color(0xFF78909C))
                if (!r.complete && r.bytesTotal > 0) LinearProgressIndicator(progress = { (r.bytesDone.toFloat() / r.bytesTotal).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text(when (r.state) { W.DONE -> "Ready offline ✓"; W.DOWNLOADING -> "Downloading…"; W.QUEUED -> "Queued"; W.WAITING -> "Waiting for Wi-Fi"; W.PAUSED -> "Paused"; else -> "Stopped" },
                    color = if (r.complete) Color(0xFF69F0AE) else if (r.state == W.ERROR) Color(0xFFFF8A80) else Color(0xFFFFD740), fontSize = 14.sp)
                r.error?.takeIf { r.state == W.ERROR }?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.state == W.DOWNLOADING || r.state == W.QUEUED || r.state == W.WAITING) TextButton({ com.victor.traillink.map.world.WorldMaps.pause(r.id) }) { Text("Pause") }
                    if (r.state == W.PAUSED || r.state == W.ERROR) TextButton({ com.victor.traillink.map.world.WorldMaps.resume(ctx, r.id) }) { Text(if (r.state == W.ERROR) "Retry" else "Resume") }
                    TextButton({ del = r }) { Text("Delete") }
                }
            }
        }
    }
    del?.let { r ->
        AlertDialog(onDismissRequest = { del = null }, title = { Text("Delete ${r.name}?") },
            text = { Text("Frees ${fmtBytes(com.victor.traillink.map.world.WorldMaps.diskBytes(ctx, r.id))}. You can download it again later.") },
            confirmButton = { TextButton({ com.victor.traillink.map.world.WorldMaps.delete(ctx, r.id); del = null }) { Text("Delete", color = Color(0xFFFF5252)) } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } })
    }
}
