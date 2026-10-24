package com.victor.traillink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.data.Prefs
import com.victor.traillink.map.world.*
import kotlin.math.roundToInt

fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 10_000_000 -> "${b / 1_000_000} MB"
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "${(b / 1000).coerceAtLeast(1)} KB"
}

/**
 * 2.3 "Download map area": a box fixed on the screen (drag the corners to resize) while the map pans and zooms under it.
 * Shows the exact download size (worked out from the map archive's index) before anything is downloaded.
 */
@Composable
fun AreaSelectOverlay(handle: MapHandle, bottomPad: androidx.compose.ui.unit.Dp, topPad: androidx.compose.ui.unit.Dp, onUsgs: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val cam by handle.camera.collectAsState()
    val online by WorldMaps.online.collectAsState()
    val unmetered by WorldMaps.unmetered.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // box in px, relative to the map view
    var box by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var whole by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf(Detail.STREETS) }
    var terrain by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var est by remember { mutableStateOf<WorldMaps.Estimate?>(null) }
    var estErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmBig by remember { mutableStateOf(false) }
    val topPx = with(density) { (topPad + 40.dp).toPx() }
    val botPx = with(density) { (bottomPad + 300.dp).toPx() }

    LaunchedEffect(size) {
        if (size.width > 0 && box == null) {
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val availH = (h - topPx - botPx).coerceAtLeast(h * 0.25f)
            val side = minOf(w * 0.8f, availH * 0.9f)
            box = androidx.compose.ui.geometry.Rect(Offset((w - side) / 2, topPx + (availH - side) / 2), Size(side, side))
        }
    }
    fun bbox(): BBox? {
        val m = handle.map ?: return null
        val r = if (whole) androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) else box ?: return null
        val pts = listOf(Offset(r.left, r.top), Offset(r.right, r.top), Offset(r.left, r.bottom), Offset(r.right, r.bottom))
            .map { m.projection.fromScreenLocation(android.graphics.PointF(it.x, it.y)) }
        val w = pts.minOf { it.longitude }; val e = pts.maxOf { it.longitude }
        val s = pts.minOf { it.latitude }.coerceAtLeast(-85.0); val n = pts.maxOf { it.latitude }.coerceAtMost(85.0)
        return BBox(w.coerceIn(-180.0, 180.0), s, e.coerceIn(-180.0, 180.0), n)
    }
    val b = remember(cam, box, whole, size) { bbox() }
    val crossesDateLine = b != null && (b.east - b.west > 180 || b.west >= b.east)
    val rough = b?.let { WorldMaps.roughEstimate(it, detail, terrain) }
    LaunchedEffect(b, detail, terrain, online) {
        est = null; estErr = null
        if (b == null || crossesDateLine) return@LaunchedEffect
        if (!online) { estErr = "No internet: connect to see the size and download."; return@LaunchedEffect }
        kotlinx.coroutines.delay(900)
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { WorldMaps.estimate(b, detail, terrain) }
        }
        r.onSuccess { est = it }.onFailure { e -> estErr = if (e is TooBigException) "Too big at this detail. Make the box smaller or pick less detail." else "Couldn't reach the map server (${e.message})" }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        val r = box
        if (!whole && r != null) {
            Canvas(Modifier.fillMaxSize()) {
                val shade = Color(0x66000000)
                drawRect(shade, Offset.Zero, Size(this.size.width, r.top))
                drawRect(shade, Offset(0f, r.bottom), Size(this.size.width, this.size.height - r.bottom))
                drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
                drawRect(shade, Offset(r.right, r.top), Size(this.size.width - r.right, r.height))
                drawRect(Color(0xFFFFD740), r.topLeft, r.size, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f))))
            }
            val hs = 36.dp; val hsPx = with(density) { hs.toPx() }
            listOf(0, 1, 2, 3).forEach { corner ->
                val cx = if (corner % 2 == 0) r.left else r.right; val cy = if (corner < 2) r.top else r.bottom
                Box(Modifier.offset { IntOffset((cx - hsPx / 2).roundToInt(), (cy - hsPx / 2).roundToInt()) }.size(hs)
                    .pointerInput(corner) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            val cur = box ?: return@detectDragGestures
                            val min = 80.dp.toPx()
                            var (l, t, rr, bb) = listOf(cur.left, cur.top, cur.right, cur.bottom)
                            if (corner % 2 == 0) l = (l + d.x).coerceIn(0f, rr - min) else rr = (rr + d.x).coerceIn(l + min, size.width.toFloat())
                            if (corner < 2) t = (t + d.y).coerceIn(0f, bb - min) else bb = (bb + d.y).coerceIn(t + min, size.height.toFloat())
                            box = androidx.compose.ui.geometry.Rect(l, t, rr, bb)
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFFFD740)))
                }
            }
        }
        Text("Pan and zoom the map under the box. Drag the corners to resize.",
            Modifier.align(Alignment.TopCenter).padding(top = topPad).clip(RoundedCornerShape(10.dp)).background(Color(0xE0101418)).padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp, color = Color.White)

        // ---- bottom card
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad).widthIn(max = 560.dp).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Color(0xF5121519)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download map area", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(Modifier.clickable { whole = !whole }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(whole, { whole = it }); Text("Whole screen", fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail.entries.forEach { d ->
                    FilterChip(d == detail, { detail = d }, label = { Text(d.label, fontSize = 13.sp) })
                }
            }
            Text(detail.hint + " · detail to zoom ${detail.maxZoom}", fontSize = 12.sp, color = Color(0xFF90A4AE))
            Row(Modifier.clickable { terrain = !terrain }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(terrain, { terrain = it }); Text("Terrain shading (hills and mountains)", fontSize = 13.sp)
            }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val sizeText = when {
                b == null -> "Map not ready"
                crossesDateLine -> "The box crosses the date line: download each side separately."
                est != null -> "Download size: ${fmtBytes(est!!.total)}" + (if (est!!.terrainBytes > 0) " (map ${fmtBytes(est!!.baseBytes)} + terrain ${fmtBytes(est!!.terrainBytes)})" else "")
                estErr != null -> estErr!!
                else -> "About ${fmtBytes(rough ?: 0)} (working out the exact size…)"
            }
            val tooBig = (est?.total ?: 0) > WorldMaps.MAX_REGION_BYTES
            Text(sizeText, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                color = if (tooBig || estErr != null || crossesDateLine) Color(0xFFFF8A80) else if ((est?.total ?: 0) > WorldMaps.WARN_BYTES) Color(0xFFFFB74D) else Color.White)
            if (tooBig) Text("Limit is ${fmtBytes(WorldMaps.MAX_REGION_BYTES)} per area.", fontSize = 12.sp, color = Color(0xFFFF8A80))
            val free = remember { WorldMaps.dir(ctx).usableSpace }
            Text("Free space on phone: ${fmtBytes(free)}" + if (!unmetered && !settings.mapsMobileData) " · downloads wait for Wi-Fi (change in More → Maps)" else "",
                fontSize = 12.sp, color = Color(0xFF90A4AE))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onUsgs) { Text("USGS topo (US)…", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onDone) { Text("Cancel") }
                Button(enabled = est != null && !tooBig && !busy && b != null && !crossesDateLine && est!!.total < free, onClick = {
                    if (est!!.total > WorldMaps.WARN_BYTES && !confirmBig) { confirmBig = true; return@Button }
                    busy = true
                    val nm = name.ifBlank { defaultAreaName(b!!) }
                    WorldMaps.add(ctx, nm, b!!, detail, terrain, est!!.total)
                    android.widget.Toast.makeText(ctx, "Downloading '$nm' (${fmtBytes(est!!.total)}). Progress is in More → Maps.", android.widget.Toast.LENGTH_LONG).show()
                    onDone()
                }) { Text("Download") }
            }
        }
    }
    if (confirmBig) AlertDialog(onDismissRequest = { confirmBig = false }, title = { Text("Large download") },
        text = { Text("This area is ${fmtBytes(est?.total ?: 0)}. That takes a while and uses that much storage. Continue?") },
        confirmButton = { TextButton({
            val bb = b ?: return@TextButton; val e = est ?: return@TextButton
            val nm = name.ifBlank { defaultAreaName(bb) }
            WorldMaps.add(ctx, nm, bb, detail, terrain, e.total); confirmBig = false; onDone()
        }) { Text("Download") } },
        dismissButton = { TextButton({ confirmBig = false }) { Text("Cancel") } })
}

fun defaultAreaName(b: BBox) = "Area %.2f, %.2f".format(java.util.Locale.US, (b.south + b.north) / 2, (b.west + b.east) / 2)
