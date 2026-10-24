package com.victor.traillink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Backtrack
import com.victor.traillink.core.Geo
import com.victor.traillink.service.BacktrackRoute
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/** 2.2 Backtrack panel: arrow to the next waypoint, distance to it, distance remaining. */
@Composable
fun BacktrackPanel(b: BacktrackRoute, phone: PhoneFix?, heading: Float?, metric: Boolean, paceMps: Double, compact: Boolean, onStop: () -> Unit) {
    val pr = phone?.let { Backtrack.progress(b.points, b.idx, it.lat, it.lon) }
    val done = pr != null && pr.idx >= b.points.size
    val eta = pr?.let { (it.remainingM / paceMps.coerceAtLeast(0.1)).toLong() }
    fun etaText() = eta?.let { s -> if (s < 3600) "${(s / 60).coerceAtLeast(1)} min" else "%d h %02d min".format(s / 3600, (s % 3600) / 60) } ?: "—"
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pr != null && !done) BearingArrow((pr.bearingDeg - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 30.dp)
            Spacer(Modifier.width(8.dp))
            Text("↩ " + if (done) "Arrived" else "Next ${pr?.let { Geo.formatDistance(it.toNextM, metric) } ?: "—"}", fontSize = 14.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (done) "" else pr?.let { Geo.formatDistance(it.remainingM, metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
            TextButton(onStop) { Text("Stop") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("↩ Backtrack: ${b.title}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E1B1B))) { Text("Stop") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pr != null && !done) BearingArrow((pr.bearingDeg - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 120.dp) else Box(Modifier.size(120.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            when {
                pr == null -> Text("No GPS fix", fontSize = 30.sp, fontWeight = FontWeight.Black)
                done -> Text("You're there", fontSize = 30.sp, fontWeight = FontWeight.Black)
                else -> {
                    Text(Geo.formatDistance(pr.remainingM, metric) + " left", fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("Next waypoint ${pr.idx + 1} of ${b.points.size}: ${Geo.formatDistance(pr.toNextM, metric)} ${Geo.cardinal(pr.bearingDeg)} ${pr.bearingDeg.roundToInt()}°",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("ETA ${etaText()} at ${Geo.formatDistance(paceMps * 3600, metric)}/h", fontSize = 13.sp, color = Color(0xFFB0BEC5))
                }
            }
            if (heading == null) Text("Compass not available: arrow points relative to north", fontSize = 11.sp, color = Color(0xFFFFB74D))
        }
    }
    Text("Follows the recorded track waypoint by waypoint. Works offline with GPS only.", fontSize = 12.sp, color = Color(0xFF90A4AE))
}

/** 2.2 Tracks & Backtrack: show/hide all or per partner (display only), clear history (with confirm), record my track, start Backtrack. */
@Composable
fun TracksDialog(vm: AppViewModel, onBacktrackStarted: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val partners by vm.partners.collectAsState()
    val mine by vm.myTrack.collectAsState()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pick by remember { mutableStateOf(false) }
    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("👣 Tracks & Backtrack") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("↩ Backtrack", fontWeight = FontWeight.Bold)
                Text("Leads you back along your own track with the arrow, waypoint by waypoint. Offline, GPS only.", fontSize = 13.sp, color = Color(0xFFB0BEC5))
                val len = mine.zipWithNext { a, b -> Geo.distanceM(a.lat, a.lon, b.lat, b.lon) }.sum()
                Text("My track: ${mine.size} points, ${Geo.formatDistance(len, s.metric)} (last 48 h)", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Button({ vm.backtrackMine(0)?.let { toast(it) } ?: run { onBacktrackStarted(); onDismiss() } }, enabled = mine.size >= 2) { Text("To the start") }
                    OutlinedButton({ pick = true }, enabled = mine.size >= 2) { Text("Pick a point…") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Record my track", Modifier.weight(1f)); Switch(s.recordMyTrack, { vm.setRecordMyTrack(it) })
                }
                Text("Recording keeps going even when tracks are hidden. Your track stays on this phone.", fontSize = 12.sp, color = Color(0xFF90A4AE))
                TextButton({ confirm = "Delete my own recorded track? Backtrack will start fresh." to { vm.clearMyTrack() } }, enabled = mine.isNotEmpty()) { Text("Clear my track…") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show tracks on the map", Modifier.weight(1f), fontWeight = FontWeight.Bold); Switch(s.tracksVisible, { vm.setTracksVisible(it) })
                }
                Text("Hiding only changes the map. Nothing is deleted.", fontSize = 12.sp, color = Color(0xFF90A4AE))
                partners.filter { !it.node.isSelf || it.trail.isNotEmpty() }.forEach { p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(p.color, 12.dp); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (p.node.isSelf) "My radio" else p.node.longName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.trail.size} points", fontSize = 11.sp, color = Color(0xFF90A4AE))
                        }
                        TextButton({ vm.backtrackPartner(p.node.num)?.let { toast(it) } ?: run { onBacktrackStarted(); onDismiss() } }, enabled = p.trail.isNotEmpty()) { Text("↩") }
                        Checkbox(p.node.num !in s.hiddenTracks, { vm.setTrackHidden(p.node.num, !it) }, enabled = s.tracksVisible)
                    }
                }
                TextButton({ confirm = "Delete the track history of ALL partners? Partners, chats and pins are kept." to { vm.clearTrails() } }) { Text("Clear all partner tracks…") }
                Text("↩ next to a partner follows their received track to reach them.", fontSize = 12.sp, color = Color(0xFF90A4AE))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } })
    if (pick) {
        val step = (mine.size / 15).coerceAtLeast(1)
        val idxs = (0 until mine.size - 1 step step).toList()
        val fmt = remember { SimpleDateFormat("h:mm a", Locale.US) }
        AlertDialog(onDismissRequest = { pick = false }, title = { Text("Backtrack to which point?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    idxs.forEach { i ->
                        val t = mine[i]
                        Text("${if (i == 0) "Start · " else ""}${fmt.format(Date(t.time))} · %.5f, %.5f".format(t.lat, t.lon),
                            Modifier.fillMaxWidth().clickable { pick = false; vm.backtrackMine(i)?.let { toast(it) } ?: run { onBacktrackStarted(); onDismiss() } }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton({ pick = false }) { Text("Cancel") } })
    }
    confirm?.let { (msg, act) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Clear track history") }, text = { Text("$msg\n\nThis can't be undone.") },
            confirmButton = { TextButton({ act(); confirm = null }) { Text("Clear", color = Color(0xFFFF5252)) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}
