package com.victor.traillink.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.ChatMsg
import com.victor.traillink.data.MessageEntity
import com.victor.traillink.data.Prefs
import com.victor.traillink.service.LinkService
import com.victor.traillink.service.MessagesVisible
import java.text.SimpleDateFormat
import java.util.*

val DEFAULT_QUICK = listOf("OK", "On my way", "Need help", "Where are you?")

@Composable
fun MessagesScreen(
    vm: AppViewModel,
    onShowOnMap: (Double, Double) -> Unit,
    talk: @Composable (convo: String) -> Unit = { TalkBar(vm, it) },
    clip: @Composable (MessageEntity) -> Unit = { ClipBubble(it) },
) {
    val convo by vm.openConvo.collectAsState()
    DisposableEffect(convo) { MessagesVisible.convo = convo; onDispose { MessagesVisible.convo = null } }
    androidx.activity.compose.BackHandler(enabled = convo != null) { vm.openConvo.value = null }
    if (convo == null) ConvoList(vm) else Chat(vm, convo!!, onShowOnMap, talk, clip)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvoList(vm: AppViewModel) {
    val partners by vm.partners.collectAsState()
    val recent by vm.recentMsgs.collectAsState()
    val link by vm.link.collectAsState()
    val s by vm.settings.collectAsState()
    val peers = partners.filter { !it.node.isSelf }
    val keys = buildList {
        add("group")
        peers.forEach { add(vm.peerKey(it.node)) }
        recent.map { it.convo }.distinct().forEach { if (it !in this) add(it) }
    }
    var removeTarget by remember { mutableStateOf<RemoveTarget?>(null) }
    removeTarget?.let { RemovePartnerDialog(vm, it) { removeTarget = null } }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Messages", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        val netOn = s.netSharing && link.net == com.victor.traillink.service.NetLink.NetStatus.ONLINE
        val radioOn = link.conn == com.victor.traillink.service.Conn.CONNECTED
        Text(when {
            netOn && radioOn -> "Sending over 🌐 internet and 📡 radio"
            netOn -> "Sending over 🌐 internet"
            radioOn -> "Sending over 📡 radio"
            else -> "No link: turn on Internet sharing (🌐 tab) or connect a radio. Messages wait until a link is up."
        }, fontSize = 12.sp, color = if (netOn || radioOn) Color(0xFF9E9E9E) else Color(0xFFFFB74D))
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(keys, key = { it }) { k ->
                val last = recent.firstOrNull { it.convo == k }
                val unread = recent.count { it.convo == k && !it.mine && !it.read }
                val p = peers.firstOrNull { vm.peerKey(it.node) == k }
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.openConvo.value = k },
                    onLongClick = { if (k != "group") com.victor.traillink.data.RemovedPartners.numForKey(k)?.let { num -> removeTarget = RemoveTarget(num, vm.nameFor(k), k) } })) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (k == "group") Text("👥", fontSize = 22.sp) else ColorDot(p?.color ?: 0xFF9E9E9E.toInt())
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (k == "group") "Everyone (group)" else vm.nameFor(k), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(last?.let { (if (it.mine) "You: " else if (k == "group") "${it.fromName}: " else "") + preview(it) } ?: "No messages yet",
                                fontSize = 12.sp, color = if (last?.sos == true) Color(0xFFFF5252) else Color(0xFF9E9E9E), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        last?.let { Text(timeFmt(it.time), fontSize = 11.sp, color = Color(0xFF9E9E9E)) }
                        if (unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(containerColor = Color(0xFFFF5252)) { Text("$unread") }
                        }
                    }
                }
            }
        }
    }
}

private fun preview(m: MessageEntity) = if (m.kind == "voice") "🎙 Voice clip ${(m.durMs ?: 0) / 1000}s" else m.text

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chat(vm: AppViewModel, convo: String, onShowOnMap: (Double, Double) -> Unit,
                 talk: @Composable (String) -> Unit, clip: @Composable (MessageEntity) -> Unit) {
    val ctx = LocalContext.current
    val msgs by vm.convoMsgs.collectAsState()
    val s by vm.settings.collectAsState()
    var text by remember(convo) { mutableStateOf("") }
    var editQuick by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<RemoveTarget?>(null) }
    removeTarget?.let { RemovePartnerDialog(vm, it) { removeTarget = null } }
    val list = rememberLazyListState()
    LaunchedEffect(convo, msgs.size) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
        if (msgs.any { !it.mine && !it.read }) LinkService.markRead(ctx, convo)
    }
    fun send(t: String, sos: Boolean = false) {
        if (t.isBlank()) return
        LinkService.sendMessage(ctx, convo, t, sos)
    }
    val quick = DEFAULT_QUICK + s.quickReplies.lines().map { it.trim() }.filter { it.isNotEmpty() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.openConvo.value = null }) { Text("←", fontSize = 20.sp) }
            Text(if (convo == "group") "Everyone (group)" else vm.nameFor(convo), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (convo.startsWith("n:") && s.callsEnabled) {
                val mic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) com.victor.traillink.call.CallManager.start(ctx, com.victor.traillink.call.CallTarget.AppPeer(convo.removePrefix("n:"), vm.nameFor(convo)))
                        ?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    else Toast.makeText(ctx, "Microphone permission is needed for calls", Toast.LENGTH_LONG).show()
                }
                Button({
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) mic.launch(android.Manifest.permission.RECORD_AUDIO)
                    else com.victor.traillink.call.CallManager.start(ctx, com.victor.traillink.call.CallTarget.AppPeer(convo.removePrefix("n:"), vm.nameFor(convo)))
                        ?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                }, contentPadding = PaddingValues(horizontal = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier.tourTarget("call")) { Text("📞 Call", fontSize = 14.sp, color = Color.White) }
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                TextButton({ menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Clear chat") }, onClick = { menu = false; confirmClear = true })
                    if (convo != "group") com.victor.traillink.data.RemovedPartners.numForKey(convo)?.let { num ->
                        DropdownMenuItem(text = { Text("Remove partner…", color = Color(0xFFFF8A80)) },
                            onClick = { menu = false; removeTarget = RemoveTarget(num, vm.nameFor(convo), convo) })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (msgs.isEmpty()) item { Text("No messages yet. Quick replies are below; long-press “Need help” to send an SOS with your position.",
                fontSize = 13.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(16.dp)) }
            items(msgs, key = { it.id }) { m -> Bubble(m, convo == "group", onShowOnMap, clip) }
        }
        talk(convo)
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quick) { q ->
                val help = q == "Need help"
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (help) Color(0xFF5D1F1F) else Color(0xFF2B3440))
                    .combinedClickable(onClick = { send(q) }, onLongClick = {
                        if (help) { send("Need help!", sos = true); Toast.makeText(ctx, "🆘 SOS sent with your position", Toast.LENGTH_LONG).show() }
                    }).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(if (help) "🆘 $q" else q, fontSize = 13.sp)
                }
            }
            item {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2B3440)).clickable { editQuick = true }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text("✎", fontSize = 13.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val bytes = text.toByteArray(Charsets.UTF_8).size
            OutlinedTextField(text, { text = ChatMsg.clip(it) }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4,
                supportingText = { Text("$bytes/${ChatMsg.MAX_BYTES}", fontSize = 10.sp, color = if (bytes > 180) Color(0xFFFFB74D) else Color(0xFF9E9E9E)) })
            Spacer(Modifier.width(6.dp))
            Button({ send(text); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }

    if (editQuick) {
        var v by remember { mutableStateOf(s.quickReplies) }
        AlertDialog({ editQuick = false }, title = { Text("Your quick replies") },
            text = { Column {
                Text("One per line. Built-in: ${DEFAULT_QUICK.joinToString(", ")}.", fontSize = 12.sp)
                OutlinedTextField(v, { v = it }, minLines = 4)
            } },
            confirmButton = { TextButton({ Prefs.update(ctx) { it.copy(quickReplies = v) }; editQuick = false }) { Text("Save") } },
            dismissButton = { TextButton({ editQuick = false }) { Text("Cancel") } })
    }
    if (confirmClear) AlertDialog({ confirmClear = false }, title = { Text("Clear this conversation?") },
        text = { Text("Deletes these messages from this phone only.") },
        confirmButton = { TextButton({ vm.clearConvo(convo); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun Bubble(m: MessageEntity, group: Boolean, onShowOnMap: (Double, Double) -> Unit, clip: @Composable (MessageEntity) -> Unit) {
    val bg = when { m.sos -> Color(0xFF8E1B1B); m.mine -> Color(0xFF1E4F8F); else -> Color(0xFF2B3440) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (!m.mine && group) Text(m.fromName, fontSize = 11.sp, color = Color(0xFF9E9E9E), modifier = Modifier.padding(start = 6.dp))
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (m.sos) Text("🆘 SOS", fontWeight = FontWeight.Black, fontSize = 14.sp)
            if (m.kind == "voice") clip(m) else Text(m.text, fontSize = 15.sp)
            if (m.sos && m.lat != null) {
                Text("%.5f, %.5f".format(m.lat, m.lon), fontSize = 12.sp)
                TextButton({ onShowOnMap(m.lat, m.lon!!) }, contentPadding = PaddingValues(0.dp)) { Text("📍 Show on map", color = Color.White) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFmt(m.time) + "  " + viaText(m.via), fontSize = 10.sp, color = Color(0xFFB0BEC5))
                if (m.mine) {
                    Spacer(Modifier.width(6.dp))
                    Text(when (m.status) { -1 -> "⚠ not sent"; 0 -> "⏳ sending"; 1 -> "✓ sent"; 2 -> "✓✓ delivered"; else -> "✓✓ read" },
                        fontSize = 10.sp, color = when (m.status) { -1 -> Color(0xFFFFB74D); 3 -> Color(0xFF82B1FF); else -> Color(0xFFB0BEC5) })
                }
            }
        }
    }
}

private fun viaText(v: String) = v.split("+").filter { it.isNotEmpty() }.joinToString("") { if (it == "net") "🌐" else "📡" }
private val tf = SimpleDateFormat("HH:mm", Locale.US)
private val df = SimpleDateFormat("MMM d HH:mm", Locale.US)
fun timeFmt(t: Long): String = if (System.currentTimeMillis() - t < 20 * 3_600_000L) tf.format(Date(t)) else df.format(Date(t))

/** Big hold-to-talk button (internet push-to-talk). */
@Composable
fun TalkBar(vm: AppViewModel, convo: String) {
    val ctx = LocalContext.current
    val link by vm.link.collectAsState()
    var holding by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0L) }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (!it) Toast.makeText(ctx, "Microphone permission is needed for push-to-talk", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(holding) { while (holding) { elapsed = System.currentTimeMillis() - started; kotlinx.coroutines.delay(200) } }
    val target = if (convo == "group") "everyone" else vm.nameFor(convo)
    val other = link.talking?.takeIf { it != "You" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (other != null) Text("🔊 $other is talking…", fontSize = 13.sp, color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp))
            .background(if (holding) Color(0xFFD32F2F) else Color(0xFF1B5E20))
            .pointerInput(convo) {
                detectTapGestures(onPress = {
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        perm.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                    }
                    val err = PttControl.start(ctx, convo)
                    if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show(); return@detectTapGestures }
                    holding = true; started = System.currentTimeMillis()
                    tryAwaitRelease()
                    PttControl.stop(); holding = false
                })
            }, contentAlignment = Alignment.Center) {
            Text(if (holding) "🎙 Talking to $target  ${elapsed / 1000}s / 60s" else "🎙 Hold to talk to $target",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Shared by the button and the volume key. */
object PttControl {
    fun start(ctx: android.content.Context, convo: String): String? {
        val svc = LinkService.instance ?: return "Push-to-talk needs Internet sharing on (🌐 tab)"
        return svc.voice.startTalk(convo)
    }
    fun stop() { LinkService.instance?.voice?.stopTalk() }
}

@Composable
fun ClipBubble(m: MessageEntity) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({
            val f = m.clipFile ?: return@TextButton
            if (playing) { com.victor.traillink.service.VoiceHub.stopReplay(); playing = false }
            else { playing = true; com.victor.traillink.service.VoiceHub.play(f, ctx.applicationContext) { playing = false } }
        }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (playing) "⏹" else "▶", fontSize = 22.sp, color = Color.White) }
        Text("🎙 Voice ${"%.1f".format((m.durMs ?: 0) / 1000.0)} s", fontSize = 15.sp)
    }
}
