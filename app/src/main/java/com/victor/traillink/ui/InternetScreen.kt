package com.victor.traillink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Geo
import com.victor.traillink.core.NetCrypto
import com.victor.traillink.core.Psk
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.Settings
import com.victor.traillink.data.Transport
import com.victor.traillink.service.LinkService
import com.victor.traillink.service.NetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves the internet key derived from a passcode (the passcode itself is not stored). */
suspend fun saveNetPasscode(ctx: android.content.Context, passcode: String, name: String?, short: String?) {
    val key = withContext(Dispatchers.Default) { NetCrypto.deriveNetKey(passcode) }
    val hex = key.joinToString("") { "%02x".format(it) }
    Prefs.update(ctx) { s -> s.copy(netKeyHex = hex, netGroup = Psk.channelName(passcode),
        myName = name?.takeIf { it.isNotBlank() } ?: s.myName, myShort = short?.takeIf { it.isNotBlank() } ?: s.myShort) }
}

@Composable
fun InternetScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val now by vm.now.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocationBanner(vm)
        Text("Internet sharing", style = MaterialTheme.typography.titleLarge)
        Text("Share positions over Wi-Fi or cell when you have service — no radio needed. Uses the same passcode as your radios; everything is end-to-end encrypted (AES-256-GCM) before it leaves the phone.", fontSize = 16.sp)

        if (!s.netReady || editing) {
            JoinCard(s, onCancel = if (s.netReady) ({ editing = false }) else null) { pass, name, short ->
                scope.launch {
                    saveNetPasscode(ctx, pass, name, short)
                    Prefs.update(ctx) { it.copy(netSharing = true) }
                    LinkService.send(ctx, LinkService.ACTION_NET_REFRESH)
                    editing = false
                }
            }
        } else {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Share my position", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("as ${s.myName} (${s.myShort}) · group ${s.netGroup}", fontSize = 15.sp)
                        }
                        Switch(s.netSharing, { on ->
                            Prefs.update(ctx) { it.copy(netSharing = on) }
                            LinkService.send(ctx, LinkService.ACTION_NET_REFRESH)
                        })
                    }
                    val st = if (s.netSharing) link.net else NetLink.NetStatus.OFF
                    Text("Status: ${st.label}", fontWeight = FontWeight.Bold, color = when (st) {
                        NetLink.NetStatus.ONLINE -> Color(0xFF69F0AE); NetLink.NetStatus.OFF -> Color(0xFF90A4AE); else -> Color(0xFFFFD740) })
                    if (st == NetLink.NetStatus.OFFLINE) link.netDetail?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFF8A80)) }
                    if (link.lastPublish > 0) Text("Last sent ${Geo.formatAge(now - link.lastPublish)}", fontSize = 15.sp)
                    else if (s.netSharing && st == NetLink.NetStatus.ONLINE) Text("Online, but nothing sent yet — waiting for this phone's position", fontSize = 15.sp, color = Color(0xFFFFD740))
                    if (link.forwarded > 0) Text("Forwarded ${link.forwarded} radio positions", fontSize = 15.sp)
                    if (!s.internetAllowed) Text("Transport is set to Radio only — internet is off.", color = Color(0xFFFFD740))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Change passcode", Modifier.weight(1f), primary = false) { editing = true }
                        BigButton("Stop & forget", Modifier.weight(1f), primary = false) { confirmForget = true }
                    }
                }
            }
        }

        Text("Transport", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Transport.entries.forEach { t ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                Prefs.update(ctx) { it.copy(transport = t) }; LinkService.send(ctx, LinkService.ACTION_NET_REFRESH)
            }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.transport == t, { Prefs.update(ctx) { it.copy(transport = t) }; LinkService.send(ctx, LinkService.ACTION_NET_REFRESH) })
                Text(t.label, fontSize = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gateway", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("When this phone is connected to a radio, forward positions heard over LoRa to the internet (e.g. home phone relays the field radio).", fontSize = 14.sp, color = Color(0xFF90A4AE))
            }
            Switch(s.gateway, { v -> Prefs.update(ctx) { it.copy(gateway = v) } })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep Location on while sharing", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                val canWrite = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                Text(if (canWrite) "Turns the phone's Location back on if something switches it off while you're sharing."
                    else "Needs a one-time grant from a computer: adb shell pm grant com.victor.traillink android.permission.WRITE_SECURE_SETTINGS",
                    fontSize = 14.sp, color = Color(0xFF90A4AE))
            }
            Switch(s.keepLocationOn, { v -> Prefs.update(ctx) { it.copy(keepLocationOn = v) }; LinkService.send(ctx, LinkService.ACTION_NET_REFRESH) })
        }
        Text("Send while moving: every ${s.netMovingSec} s"); Slider(s.netMovingSec.toFloat(), { v -> Prefs.update(ctx) { it.copy(netMovingSec = v.toInt()) } }, valueRange = 10f..300f)
        Text("Send while still: every ${s.netStillSec / 60} min"); Slider(s.netStillSec / 60f, { v -> Prefs.update(ctx) { it.copy(netStillSec = (v.toInt().coerceAtLeast(1)) * 60) } }, valueRange = 1f..30f)

        BrokerCard(s)
        Text("Note: the broker is a free public server with no uptime guarantee. It can see that an anonymous topic is active and how often, but not names or positions.", fontSize = 14.sp, color = Color(0xFF90A4AE))
    }

    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false },
        title = { Text("Stop sharing and forget the passcode?") },
        text = { Text("This phone stops sending, removes its last position from the broker, and forgets the internet key. Radios are not changed.") },
        confirmButton = { TextButton({ LinkService.send(ctx, LinkService.ACTION_NET_STOP_FORGET); confirmForget = false }) { Text("Stop & forget") } },
        dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } })
}

@Composable
private fun JoinCard(s: Settings, onCancel: (() -> Unit)?, onJoin: (String, String, String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(s.myName) }
    var short by remember { mutableStateOf(s.myShort) }
    var check by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pass) {
        check = null
        if (Psk.normalize(pass).length >= Psk.MIN_LENGTH) {
            kotlinx.coroutines.delay(600)
            val p = pass
            check = withContext(Dispatchers.Default) { "${Psk.channelName(p)} · ${Psk.fingerprint(NetCrypto.deriveNetKey(p))}" }
        }
    }
    val ok = Psk.normalize(pass).length >= Psk.MIN_LENGTH && pass == pass2 && name.isNotBlank() && short.isNotBlank()
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join a group", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("Use the same passcode on every phone (and radio) in your group.", fontSize = 15.sp)
            OutlinedTextField(pass, { pass = it }, label = { Text("Passcode (8+ characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(pass2, { pass2 = it }, label = { Text("Type it again") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(show, { show = it }); Text("Show passcode") }
            if (pass2.isNotEmpty() && pass != pass2) Text("Passcodes don't match", color = Color(0xFFFF8A80))
            check?.let { Text("Group check: $it\n(must match on every phone)", fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("This phone's name (e.g. Victor)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(short, { short = it.take(4).uppercase() }, label = { Text("Short name, up to 4 letters (map label)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onCancel?.let { c -> BigButton("Cancel", Modifier.weight(1f), primary = false) { c() } }
                BigButton("Start sharing", Modifier.weight(1f), enabled = ok) { onJoin(pass, name.trim(), short.trim().uppercase()) }
            }
        }
    }
}

@Composable
private fun BrokerCard(s: Settings) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var url by remember(s.brokerUrl) { mutableStateOf(s.brokerUrl) }
    var user by remember(s.brokerUser) { mutableStateOf(s.brokerUser) }
    var pw by remember(s.brokerPass) { mutableStateOf(s.brokerPass) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Server (MQTT broker)", fontWeight = FontWeight.Bold); Text(s.brokerUrl, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("URL (ssl://host:8883 or wss://host:port/mqtt)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                val valid = Regex("^(ssl|wss|tcp|ws)://[^\\s/:]+(:\\d+)?(/.*)?$").matches(url)
                if (url.startsWith("tcp://") || url.startsWith("ws://")) Text("Unencrypted connection — contents are still end-to-end encrypted, but use ssl:// or wss:// when you can.", fontSize = 13.sp, color = Color(0xFFFFD740))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Default", Modifier.weight(1f), primary = false) { url = Settings.DEFAULT_BROKER; user = ""; pw = "" }
                    BigButton("Save", Modifier.weight(1f), enabled = valid) {
                        Prefs.update(ctx) { it.copy(brokerUrl = url, brokerUser = user, brokerPass = pw) }
                        LinkService.send(ctx, LinkService.ACTION_NET_REFRESH); open = false
                    }
                }
            }
        }
    }
}
