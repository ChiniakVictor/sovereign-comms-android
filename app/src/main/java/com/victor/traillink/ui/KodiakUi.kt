package com.victor.traillink.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Easements
import com.victor.traillink.core.EsmtKind
import com.victor.traillink.core.Geo
import com.victor.traillink.core.KFeature
import com.victor.traillink.core.KGeo
import com.victor.traillink.core.TrailsInfo
import com.victor.traillink.data.KodiakData
import com.victor.traillink.data.Prefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Nearest point on the feature to (lat, lon): used for distance and as the navigation target. */
fun KFeature.nearestTo(lat: Double, lon: Double): DoubleArray {
    if (lines.isNotEmpty()) return lines.map { KGeo.toLine(lat, lon, it) }.minBy { it.first }.second
    if (polys.isNotEmpty()) {
        if (polys.any { KGeo.inPoly(lat, lon, it) }) return doubleArrayOf(lat, lon)
        return polys.map { KGeo.toLine(lat, lon, it) }.minBy { it.first }.second
    }
    return point ?: doubleArrayOf(lat, lon)
}

fun kodiakColor(f: KFeature): Color = when {
    f.k == "row" -> Color(0xFFFFC107)
    f.k == "trail" -> Color(android.graphics.Color.parseColor(TrailsInfo.COLOR))
    f.k == "rs2477" -> Color(android.graphics.Color.parseColor(AK_RS_COLOR))
    f.k == "dnr" -> Color(android.graphics.Color.parseColor(AK_DNR_COLOR))
    f.k.startsWith("log") -> Color(runCatching { android.graphics.Color.parseColor(f.props.optString("color")) }.getOrDefault(0xFFFFEB3B.toInt()))
    else -> Color(android.graphics.Color.parseColor(EsmtKind.of(f.props.optString("kind")).color))
}

private fun ft(v: Int, metric: Boolean) = if (metric) "${(v * 0.3048).roundToInt()} m" else "$v ft"

@Composable
fun KodiakStrip(f: KFeature, phone: PhoneFix?, metric: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c = kodiakColor(f)
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(c)); Spacer(Modifier.width(8.dp))
        Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        Text(phone?.let { p -> val n = f.nearestTo(p.lat, p.lon); Geo.formatDistance(Geo.distanceM(p.lat, p.lon, n[0], n[1]), metric) } ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun KodiakDetails(f: KFeature, phone: PhoneFix?, metric: Boolean, onNavigate: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = f.props
    fun s(k: String) = p.optString(k, "").takeIf { it.isNotBlank() && it != "null" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClose) { Text("✕") }
    }
    phone?.let { ph ->
        val n = f.nearestTo(ph.lat, ph.lon); val d = Geo.distanceM(ph.lat, ph.lon, n[0], n[1])
        val b = Geo.bearingDeg(ph.lat, ph.lon, n[0], n[1])
        Text("${Geo.formatDistance(d, metric)} ${Geo.cardinal(b)} (nearest point)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    @Composable fun line(label: String, v: String?) { if (v != null) Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF90A4AE), modifier = Modifier.width(92.dp)); Text(v, fontSize = 13.sp)
    } }
    if (f.k == "trail") {
        line("Name", s("name"))
        line("Type", TrailsInfo.classLabel(s("cls")))
        line("Length", s("miles")?.toDoubleOrNull()?.let { Geo.formatDistance(it * 1609.344, metric) + " (all sections)" })
        line("Source", "${TrailsInfo.SOURCE} trails GIS")
    } else if (f.k.startsWith("log")) {
        line("Name", s("name"))
        line("Folder", s("folder"))
        line("Notes", s("desc"))
        line("Source", s("src"))
    } else if (f.k == "rs2477") {
        line("Name", s("name"))
        line("Type", "RS 2477 right-of-way" + (s("width_ft")?.toIntOrNull()?.takeIf { it > 0 }?.let { " · ${ft(it, metric)} wide" } ?: ""))
        line("Allowed uses", s("uses"))
        line("DNR case", listOfNotNull(s("id"), s("case")?.lowercase()?.replaceFirstChar { it.uppercase() }, s("review")?.let { "$it review" }).joinToString(" · ").ifBlank { null })
        line("Length", f.lines.sumOf { l -> l.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a[0], a[1], b[0], b[1]) } }.takeIf { it > 0 }?.let { Geo.formatDistance(it, metric) + " (as mapped)" })
        line("Source", "Alaska DNR RS 2477 trails")
    } else if (f.k == "dnr") {
        line("Type", s("cat"))
        line("Allowed uses", s("uses"))
        line("DNR case", listOfNotNull(s("id"), s("case")).joinToString(" · ").ifBlank { null })
        line("Mapped as", when (s("geom")) { "pt" -> "Point"; "area" -> "Area (easement strip / parcel)"; else -> "Line" })
        line("Source", "Alaska DNR easements (status plats)")
    } else if (f.k == "row") {
        line("Route", s("route"))
        line("ROW width", p.optInt("width_ft").let { w -> "${ft(w, metric)} (≈${ft(p.optInt("left_ft"), metric)} / ${ft(p.optInt("right_ft"), metric)} each side) — derived" })
        line("Mileposts", s("mp")?.let { "$it (from route start)" })
        line("Owner", s("owner"))
        line("Width basis", s("basis_text"))
        line("Source", s("source"))
        Text("Approximate band. Exact ROW limits are on DOT&PF right-of-way plans.", fontSize = 12.sp, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 4.dp))
    } else {
        line("EIN", s("ein"))
        line("Type", listOfNotNull(s("cat"), s("status")).joinToString(" · ") + (s("width_ft")?.let { " · ${ft(it.toInt(), metric)} wide" } ?: ""))
        line("BLM type", s("type"))
        line("Allowed uses", s("uses"))
        line("Restrictions", s("restrict"))
        line("Name", s("name"))
        line("Landowner", s("owner"))
        line("Accesses", s("accessed"))
        line("Admin", s("agency"))
        line("Reserved", s("reserved"))
        line("Conveyance", s("doc_no"))
        line("Size", s("acres")?.toDoubleOrNull()?.let { a -> "${if (a % 1.0 == 0.0) a.toInt() else a} acre${if (a == 1.0) "" else "s"} (circle radius ${s("r_m") ?: "?"} m)" })
        line("Patent text", s("patent"))
        line("Length", s("miles")?.toDoubleOrNull()?.let { Geo.formatDistance(it * 1609.344, metric) })
        line("Location", s("approx"))
        if (p.optInt("ak") == 1) line("Source", "BLM Alaska 17(b) easements (statewide)")
        Text(Easements.NOTE, fontSize = 12.sp, color = Color(0xFFB0BEC5), modifier = Modifier.padding(top = 4.dp))
    }
    val disclaimer = when { f.k == "trail" -> TrailsInfo.NO_WARRANTY; f.k.startsWith("log") -> "Personal map; road conditions and gates change. Verify before relying on it."
        f.k == "rs2477" -> com.victor.traillink.data.AlaskaData.RS_DISCLAIMER; f.k == "dnr" -> com.victor.traillink.data.AlaskaData.DNR_DISCLAIMER; else -> Easements.DISCLAIMER }
    Text(disclaimer, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onNavigate, Modifier.weight(1f)) { Text("Navigate") }
        s("doc")?.let { url -> OutlinedButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.weight(1f)) { Text(if (f.k == "rs2477" || f.k == "dnr") "DNR case file" else "Legal description") } }
    }
    Spacer(Modifier.height(10.dp))
}

/** "Am I on an easement?" chip text, or null when not applicable. */
fun easementCheckText(near: KGeo.Near?, metric: Boolean): Pair<String, Boolean>? {
    near ?: return null
    val name = near.f.props.optString("label").ifBlank { EsmtKind.of(near.f.props.optString("kind")).label }
    val e = near.edgeM
    return if (near.on) "On easement $name · ${Geo.formatDistance(near.centreM, metric)} from centre, ${Geo.formatDistance(-e!!, metric)} inside edge" to true
    else "Off easement · nearest $name ${Geo.formatDistance(near.centreM, metric)}" + (e?.let { " (edge ${Geo.formatDistance(it, metric)})" } ?: "") to false
}

@Composable
fun LayersDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by Prefs.settings.collectAsState()
    val data by KodiakData.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Map layers") }, text = {
      Column {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Base map") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Kodiak Island") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Alaska") })
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            @Composable fun sw(label: String, sub: String, v: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = Color(0xFF90A4AE)) }
                Switch(v, f)
            }
          if (tab == 0) {
            // ---- 2.3 base map (worldwide)
            @Composable fun radio(label: String, sub: String, v: String) = Row(Modifier.fillMaxWidth().clickable { Prefs.update(ctx) { it.copy(basemap = v) } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.basemap == v, { Prefs.update(ctx) { it.copy(basemap = v) } })
                Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = Color(0xFF90A4AE)) }
            }
            radio("USGS topo (default)", "Contour lines and place names from The National Map. US only: elsewhere, or offline with no saved USGS topo but a downloaded World area, the World map is shown instead.", "usgs")
            radio("World street map", "Anywhere on Earth. Online: OpenFreeMap. Offline: the areas you download (More → Maps).", "world")
            sw("Terrain shading", "Hill shading from Mapterhorn (online, or in areas downloaded with terrain)", s.terrainShade) { v -> Prefs.update(ctx) { it.copy(terrainShade = v) } }
            sw("Downloaded maps only", "Never load map tiles from the internet; use only what's saved on the phone", s.offlineMapsOnly) { v -> Prefs.update(ctx) { it.copy(offlineMapsOnly = v) } }
            Text("Download areas for offline use: ⬇ on the map. Manage them in More → Maps.", fontSize = 12.sp, color = Color(0xFF90A4AE), modifier = Modifier.padding(top = 6.dp))
          } else if (tab == 2) {
            val ak by com.victor.traillink.data.AlaskaData.state.collectAsState()
            Text("Public easements across Alaska. Lines show from zoom 7, sites and areas from zoom 9, names from zoom 12. Tap one for type, width and allowed uses. On Kodiak the 17(b) easements come from the Kodiak Island tab.", fontSize = 13.sp, color = Color(0xFF90A4AE))
            Row(Modifier.fillMaxWidth().clickable { Prefs.update(ctx) { it.copy(layerAlaska = !it.layerAlaska) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All Alaska easements", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(s.layerAlaska, { v -> Prefs.update(ctx) { it.copy(layerAlaska = v) } })
            }
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            Column(Modifier.alpha(if (s.layerAlaska) 1f else 0.45f)) {
                sw("ANCSA 17(b) easements (BLM)", "${ak?.count("blm") ?: "…"} trails, roads and sites reserved across Native corporation land", s.layerAk17b) { v -> Prefs.update(ctx) { it.copy(layerAk17b = v) } }
                sw("RS 2477 trails (State)", "${ak?.count("rs2477") ?: "…"} historic public rights-of-way, usually 100 ft wide", s.layerAkRs2477) { v -> Prefs.update(ctx) { it.copy(layerAkRs2477 = v) } }
                sw("State public access easements (DNR)", "${ak?.let { it.count("dnr_line") + it.count("dnr_poly") + it.count("dnr_pt") } ?: "…"} access easements from DNR status plats", s.layerAkDnr) { v -> Prefs.update(ctx) { it.copy(layerAkDnr = v) } }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("┅ 17(b) by type", fontSize = 11.sp, color = Color(0xFFFF9100))
                    Text("··· RS 2477", fontSize = 11.sp, color = Color(android.graphics.Color.parseColor(AK_RS_COLOR)))
                    Text("╍ DNR access", fontSize = 11.sp, color = Color(android.graphics.Color.parseColor(AK_DNR_COLOR)))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(ak?.let { "Data: BLM 17(b) and DNR fetched ${it.meta.optString("built")} (DNR refreshed ${it.meta.optString("dnr_refreshed")})." } ?: "Loading…", fontSize = 12.sp)
            Text("Sources: " + com.victor.traillink.data.AlaskaData.SOURCES, fontSize = 11.sp, color = Color(0xFF90A4AE))
            Text("For reference only; easements can be relocated, vacated or disputed. Check the case file and respect private land.", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 8.dp))
          } else {
            Text("Shown only when the map is over the Kodiak archipelago. All on by default.", fontSize = 13.sp, color = Color(0xFF90A4AE))
            Row(Modifier.fillMaxWidth().clickable { Prefs.update(ctx) { it.copy(layerKodiak = !it.layerKodiak) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All Kodiak Island layers", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(s.layerKodiak, { v -> Prefs.update(ctx) { it.copy(layerKodiak = v) } })
            }
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
          Column(Modifier.alpha(if (s.layerKodiak) 1f else 0.45f)) {
            sw("17(b) easements", "BLM ANCSA public easements: trails, roads, sites", s.layerEasements) { v -> Prefs.update(ctx) { it.copy(layerEasements = v) } }
            sw("State road right-of-way", "Chiniak Hwy & Rezanof Dr (DOT&PF), approximate band", s.layerRow) { v -> Prefs.update(ctx) { it.copy(layerRow = v) } }
            sw("Kodiak Trails", "Kodiak Island Borough trail network: ${data?.trailsSummary ?: "…"}", s.layerTrails) { v -> Prefs.update(ctx) { it.copy(layerTrails = v) } }
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val tc = Color(android.graphics.Color.parseColor(TrailsInfo.COLOR))
                listOf("━━ double track", "╍╍ single track", "··· route").forEach { Text(it, fontSize = 11.sp, color = tc) }
            }
            if (data?.logJson != null) sw("Chiniak logging roads", "From the kodiakking.com Google Earth map", s.layerLogRoads) { v -> Prefs.update(ctx) { it.copy(layerLogRoads = v) } }
            sw("Am I on an easement?", "Shows distance to the nearest easement centre and edge", s.easementCheck) { v -> Prefs.update(ctx) { it.copy(easementCheck = v) } }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EsmtKind.TRAIL25, EsmtKind.TRAIL50, EsmtKind.ROAD, EsmtKind.SITE).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(k.color)))); Text(" " + k.label.substringBefore(" ("), fontSize = 11.sp)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(data?.let { "${it.counts}\nEasements: ${it.esmtSource} data, ${it.esmtDate}" } ?: "Loading…", fontSize = 12.sp)
            Text("Sources: BLM Alaska 17(b) easements (gis.blm.gov), Alaska DOT&PF Road Centerlines, Kodiak Island Borough parcels (ROW width), Kodiak Island Borough trails (KIB_Trails, no warranty).", fontSize = 11.sp, color = Color(0xFF90A4AE))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, onClick = { busy = true; msg = null; scope.launch { msg = KodiakData.update(ctx); busy = false } }) { Text("Update easement data") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp), strokeWidth = 2.dp)
            }
            msg?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Text(Easements.DISCLAIMER, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFCC80), modifier = Modifier.padding(top = 8.dp))
          }
          }
        }
      }
    }, confirmButton = { TextButton(onDismiss) { Text("Done") } })
}
