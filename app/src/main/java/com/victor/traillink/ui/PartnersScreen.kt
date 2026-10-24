package com.victor.traillink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Geo
import kotlin.math.roundToInt

@Composable
fun PartnersScreen(vm: AppViewModel, onFocus: (PartnerUi) -> Unit) {
    val partners by vm.partners.collectAsState()
    val heading by vm.heading.collectAsState()
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val now by vm.now.collectAsState()
    var confirmForget by remember { mutableStateOf<PartnerUi?>(null) }

    Column(Modifier.fillMaxSize()) {
        LocationBanner(vm)
        Text("Partners (${partners.count { !it.node.isSelf }})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (partners.isEmpty()) Text("Nobody heard yet. Radios set up with the same passcode appear here automatically.", Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(partners, key = { it.node.num }) { p ->
                val n = p.node
                val age = now - n.lastHeard
                val lost = n.lastHeard > 0 && age > settings.lostMinutes * 60_000L
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onFocus(p) }, onLongClick = { if (!n.isSelf) confirmForget = p }), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (lost && !n.isSelf) Color(0xFF3A1416) else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(p.color, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (n.isSelf) "My radio · ${n.longName}" else n.longName, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp)); SourceBadge(n.posSource)
                            }
                            Text(p.distanceM?.let { "${Geo.formatDistance(it, settings.metric)} ${Geo.cardinal(p.bearingDeg!!)} ${p.bearingDeg.roundToInt()}°" }
                                ?: if (n.lat == null) "no position yet" else "no phone GPS", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                            Text(buildString {
                                append(if (n.lastHeard == 0L) "never heard" else "heard ${Geo.formatAge(age)}")
                                append(" · 🔋 ${batteryText(n.battery)}")
                                if (!n.isSelf) n.snr?.let { append(" · SNR %.1f".format(it)) }
                                n.hops?.let { if (it > 0) append(" · $it hop${if (it > 1) "s" else ""}") }
                            }, fontSize = 15.sp, color = ageColor(age, settings.lostMinutes))
                            if (lost && !n.isSelf) Text(if (n.alarmMuted) "LOST CONTACT (alarm muted)" else "LOST CONTACT", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                        if (p.bearingDeg != null) BearingArrow((p.bearingDeg - (heading ?: 0f)).toFloat(), Color(p.color), 56.dp)
                        if (!n.isSelf) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TextButton({ vm.setMuted(n.num, !n.alarmMuted) }, Modifier.heightIn(min = 48.dp)) {
                                Text(if (n.alarmMuted) "🔕" else "🔔", fontSize = 26.sp)
                            }
                            TextButton({ vm.clearTrail(n.num) }) { Text("Clear trail", fontSize = 13.sp) }
                            TextButton({ confirmForget = p }) { Text("Remove", fontSize = 13.sp, color = Color(0xFFFF8A80)) }
                        }
                    }
                }
            }
        }
    }
    confirmForget?.let { p ->
        RemovePartnerDialog(vm, RemoveTarget(p.node.num, p.node.longName)) { confirmForget = null }
    }
}
