package com.victor.traillink.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.*
import com.victor.traillink.data.PinEntity
import com.victor.traillink.data.Prefs
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

fun pinEmoji(p: PinEntity) = p.emoji ?: PinKind.of(p.kind).emoji

/** Create (existing == null) or edit one of my pins. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PinDialog(lat: Double, lon: Double, alt: Int?, existing: PinEntity?, onSave: (PinEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(PinKind.of(existing?.kind ?: PinKind.MEETUP.name)) }
    var color by remember { mutableStateOf(existing?.color ?: PIN_COLORS[0]) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var expiryH by remember { mutableStateOf(0) }
    var shared by remember { mutableStateOf(existing?.shared ?: true) }
    AlertDialog(onDismiss, title = { Text(if (existing == null) "Drop pin" else "Edit pin") }, text = {
        Column(Modifier.verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("%.5f, %.5f".format(lat, lon), fontSize = 12.sp, color = Color(0xFF90A4AE))
            OutlinedTextField(name, { name = ChatMsg.clip(it, PinMsg.NAME_MAX) }, label = { Text("Name") }, placeholder = { Text(kind.label) }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PinKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text("${k.emoji} ${k.label}", fontSize = 12.sp) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PIN_COLORS.forEach { c ->
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == color) 3.dp else 1.dp, if (c == color) Color.White else Color(0xFF455A64), CircleShape).clickable { color = c })
                }
            }
            OutlinedTextField(note, { note = ChatMsg.clip(it, PinMsg.NOTE_MAX) }, label = { Text("Note (optional)") }, maxLines = 3)
            if (existing == null) {
                Text("Expires", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Never", 1 to "1 h", 6 to "6 h", 24 to "24 h").forEach { (h, l) -> FilterChip(expiryH == h, { expiryH = h }, { Text(l) }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share with group")
                    Text("Over the internet and the radio (stock Meshtastic apps see it too)", fontSize = 11.sp, color = Color(0xFF90A4AE))
                }
                Switch(shared, { shared = it })
            }
        }
    }, confirmButton = {
        TextButton({
            val now = System.currentTimeMillis()
            val s = Prefs.settings.value
            onSave(existing?.copy(name = name.ifBlank { kind.label }, kind = kind.name, color = color, note = note, shared = shared, updated = now, emoji = null)
                ?: PinEntity(PinMsg.newId(), name.ifBlank { kind.label }, kind.name, color, note, lat, lon, alt, "me", s.myName.ifBlank { "Me" },
                    now, now, if (expiryH > 0) now + expiryH * 3_600_000L else 0, shared = shared, mine = true))
        }) { Text(if (existing == null) "Drop pin" else "Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun Modifier.verticalScrollCompat() = this.then(Modifier.verticalScroll(rememberScrollState()))

private val whenFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

/** Sheet content for a tapped pin. */
@Composable
fun PinDetails(p: PinEntity, phone: PhoneFix?, heading: Float?, metric: Boolean, onNavigate: () -> Unit, onEdit: () -> Unit,
               onDelete: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val d = phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) }
    val b = phone?.let { Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon) }
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pinEmoji(p), fontSize = 26.sp); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(p.color))
            Text("${PinKind.of(p.kind).label} · by ${if (p.mine) "me" else p.byName} · ${whenFmt.format(Date(p.created))}" +
                (if (p.expires > 0) " · until ${whenFmt.format(Date(p.expires))}" else "") + if (!p.shared) " · only on this phone" else "",
                fontSize = 12.sp, color = Color(0xFF90A4AE))
        }
        TextButton(onClose) { Text("✕") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(b?.let { "${Geo.cardinal(it)}  ${it.roundToInt()}°" } ?: "Waiting for my GPS", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("%.5f, %.5f".format(p.lat, p.lon) + (Geo.formatAltitude(p.alt, metric)?.let { " · elev $it" } ?: ""), fontSize = 12.sp, color = Color(0xFF90A4AE))
        }
        if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(p.color), 56.dp)
    }
    if (p.note.isNotBlank()) Text(p.note, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Button(onNavigate, Modifier.weight(1f)) { Text("🧭 Navigate") }
        if (com.victor.traillink.map.OfflineMaps.isOnline(ctx)) OutlinedButton({
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}&travelmode=walking"))
            runCatching { ctx.startActivity(i) }
        }, Modifier.weight(1f)) { Text("Google Maps", fontSize = 13.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.mine) TextButton(onEdit) { Text("Edit") }
        TextButton({ confirm = true }) { Text(if (p.mine) "Delete" else "Remove from my map", color = Color(0xFFFF8A80)) }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text(if (p.mine) "Delete this pin?" else "Remove from my map?") },
        text = { Text(if (p.mine && p.shared) "It is removed for everyone on the passcode." else "Only this phone stops showing it.") },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text(if (p.mine) "Delete" else "Remove") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

/** Navigation mode: big compass arrow to the pin, distance remaining and ETA at the configured pace. */
@Composable
fun NavPanel(p: PinEntity, phone: PhoneFix?, heading: Float?, metric: Boolean, paceMps: Double, compact: Boolean, onStop: () -> Unit) {
    val d = phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) }
    val b = phone?.let { Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon) }
    val eta = d?.let { (it / paceMps.coerceAtLeast(0.1)).toLong() }
    val arrived = d != null && d <= 20
    @Composable fun etaText() = eta?.let { s -> if (s < 3600) "${(s / 60).coerceAtLeast(1)} min" else "%d h %02d min".format(s / 3600, (s % 3600) / 60) } ?: "—"
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 30.dp)
            Spacer(Modifier.width(8.dp))
            Text("${pinEmoji(p)} ${p.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (arrived) "Arrived" else d?.let { Geo.formatDistance(it, metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp)); Text(etaText(), fontSize = 12.sp, color = Color(0xFFB0BEC5))
            TextButton(onStop) { Text("Stop") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🧭 Navigating to ${pinEmoji(p)} ${p.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Button(onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E1B1B))) { Text("Stop") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (b != null) BearingArrow((b - (heading ?: 0f)).toFloat(), Color(0xFFFFD740), 120.dp) else Box(Modifier.size(120.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(if (arrived) "You're there" else d?.let { Geo.formatDistance(it, metric) } ?: "No GPS fix", fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(b?.let { "${Geo.cardinal(it)}  ${it.roundToInt()}°" } ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("ETA ${etaText()} at ${Geo.formatDistance(paceMps * 3600, metric)}/h", fontSize = 13.sp, color = Color(0xFFB0BEC5))
            if (heading == null) Text("Compass not available: arrow points relative to north", fontSize = 11.sp, color = Color(0xFFFFB74D))
        }
    }
}

/** Pins list (Partners tab): sorted by distance, filter by kind / mine. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PinsList(vm: AppViewModel, onOpen: (PinEntity) -> Unit) {
    val pins by vm.pins.collectAsState()
    val phone by vm.phone.collectAsState()
    val s by vm.settings.collectAsState()
    var kind by remember { mutableStateOf<PinKind?>(null) }
    var mineOnly by remember { mutableStateOf(false) }
    fun dist(p: PinEntity) = phone?.let { Geo.distanceM(it.lat, it.lon, p.lat, p.lon) }
    val list = pins.filter { (kind == null || PinKind.of(it.kind) == kind) && (!mineOnly || it.mine) }.sortedBy { dist(it) ?: Double.MAX_VALUE }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(kind == null && !mineOnly, { kind = null; mineOnly = false }, { Text("All") })
            FilterChip(mineOnly, { mineOnly = !mineOnly }, { Text("Mine") })
            PinKind.entries.filter { k -> pins.any { PinKind.of(it.kind) == k } }.forEach { k -> FilterChip(kind == k, { kind = if (kind == k) null else k }, { Text(k.emoji) }) }
        }
        if (list.isEmpty()) Text(if (pins.isEmpty()) "No pins yet. Long-press the map to drop one, or use 📍 to pin your own location." else "No pins match.",
            fontSize = 14.sp, color = Color(0xFF90A4AE), modifier = Modifier.padding(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(p) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pinEmoji(p), fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, color = Color(p.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("by ${if (p.mine) "me" else p.byName} · ${Geo.formatAge(System.currentTimeMillis() - p.created)} ago" + if (p.note.isNotBlank()) " · ${p.note}" else "",
                                fontSize = 12.sp, color = Color(0xFF90A4AE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val d = dist(p)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(d?.let { Geo.formatDistance(it, s.metric) } ?: "—", fontWeight = FontWeight.Bold)
                            phone?.let { Text(Geo.cardinal(Geo.bearingDeg(it.lat, it.lon, p.lat, p.lon)), fontSize = 12.sp, color = Color(0xFF90A4AE)) }
                        }
                    }
                }
            }
        }
    }
}
