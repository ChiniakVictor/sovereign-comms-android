package com.victor.traillink.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.victor.traillink.core.RadioPolicy
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
import com.victor.traillink.ble.MeshBle
import com.victor.traillink.core.MeshCodec
import com.victor.traillink.core.MeshCodec.SetupRole
import com.victor.traillink.core.Psk
import com.victor.traillink.service.Conn
import com.victor.traillink.service.LinkRepo
import com.victor.traillink.service.LinkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Found(val address: String, val name: String, val rssi: Int, val bonded: Boolean)

@SuppressLint("MissingPermission")
@Composable
fun RadioScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val link by vm.link.collectAsState()
    val log by LinkRepo.log.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateMapOf<String, Found>() }
    var wizard by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val settings by com.victor.traillink.data.Prefs.settings.collectAsState()
    // 2.5.1: Bluetooth permission is asked for here, when Search for radios is tapped (not when the app opens)
    val searchPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) { found.clear(); scanning = true } else scanError = "Search for radios needs the Bluetooth permission (Nearby devices)."
    }
    fun startSearch() {
        val need = RadioPolicy.searchPermissions(Build.VERSION.SDK_INT)
        if (need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) { found.clear(); scanning = true }
        else searchPerms.launch(need.toTypedArray())
    }
    val cb = remember {
        object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val uuids = r.scanRecord?.serviceUuids.orEmpty()
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: ""
                val mesh = uuids.contains(ParcelUuid(MeshBle.SERVICE)) || name.contains("Meshtastic", true) || name.startsWith("RAK", true) || name.contains("Pocket", true)
                if (mesh) found[r.device.address] = Found(r.device.address, name.ifBlank { r.device.address }, r.rssi, r.device.bondState == 12)
            }
            override fun onScanFailed(errorCode: Int) { scanError = "Scan failed ($errorCode)"; scanning = false }
        }
    }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        scanError = null
        val sc = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || sc == null) { scanError = "Turn on Bluetooth first"; scanning = false; return@LaunchedEffect }
        // bonded radios show up even when they're not advertising (already connected elsewhere)
        runCatching { adapter.bondedDevices }.getOrNull().orEmpty().filter { it.address == settings.lastDevice || (it.name ?: "").let { n -> n.contains("Meshtastic", true) || n.startsWith("RAK", true) } }
            .forEach { found[it.address] = Found(it.address, it.name ?: it.address, 0, true) }
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            delay(RadioPolicy.SEARCH_SCAN_MS)
        } catch (e: SecurityException) { scanError = "Bluetooth permission missing" }
        finally { runCatching { sc.stopScan(cb) }; scanning = false }
    }
    DisposableEffect(Unit) { onDispose { runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } } }

    if (wizard) { SetupWizard(link.radio) { wizard = false }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(link.lookingFor?.let { "Looking for $it…" } ?: link.conn.label, style = MaterialTheme.typography.titleLarge,
                    color = when (link.conn) { Conn.CONNECTED -> Color(0xFF69F0AE); Conn.IDLE -> Color.Unspecified; else -> Color(0xFFFFD740) })
                val r = link.radio
                if (r.name != null || r.address != null) Text("${r.name ?: ""}  ${r.address ?: ""}")
                if (link.conn == Conn.CONNECTED) {
                    Text("Radio name: ${r.longName ?: "?"} (${r.shortName ?: "?"})")
                    Text("Firmware ${r.firmware ?: "?"} · ${r.hwModel ?: ""}")
                    r.lora?.let { Text("LoRa: ${it.region} · ${if (it.usePreset) it.modemPreset.name else "custom"}") }
                    r.device?.let { Text("Role: ${it.role}") }
                    r.primary?.settings?.let { s ->
                        val ours = s.name.startsWith("TL") && s.psk.size() == 32
                        Text("Channel: '${s.name.ifBlank { "(default)" }}' ${if (ours) "· Sovereign Comms private ✓" else if (s.psk.size() <= 1) "· default PUBLIC key ⚠" else ""}" +
                            if (s.useAead) " · AEAD" else "")
                    }
                    Text(if (r.aeadSupported) "AEAD (tamper protection) supported" else "AEAD needs firmware 2.8.1+", fontSize = 14.sp, color = Color(0xFF90A4AE))
                }
                link.error?.let { if (link.conn != Conn.CONNECTED) Text(it, color = Color(0xFFFF8A80)) }
            }
        }
        if (link.conn != Conn.IDLE && link.conn != Conn.NOT_FOUND && link.conn != Conn.FAILED) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Set up radio", Modifier.weight(1f), enabled = link.conn == Conn.CONNECTED) { LinkRepo.setup { com.victor.traillink.service.SetupProgress() }; wizard = true }
                BigButton(if (link.conn == Conn.CONNECTED) "Disconnect" else "Cancel", Modifier.weight(1f), primary = false) { LinkService.send(ctx, LinkService.ACTION_DISCONNECT) }
            }
        }
        // 2.5.1: the remembered radio (looked for when the app opens) and an always-available Search for radios
        val remembered = settings.lastDevice?.takeIf { RadioPolicy.validAddress(it) }
        if (remembered != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remembered radio: ${settings.lastDeviceName ?: remembered}", fontWeight = FontWeight.Bold)
                    Text("$remembered · Sovereign Comms looks for it (30 s) when the app opens.", fontSize = 15.sp)
                    if (link.conn == Conn.IDLE || link.conn == Conn.NOT_FOUND || link.conn == Conn.FAILED) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Reconnect", Modifier.weight(1f)) {
                            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) startSearch()
                            else LinkService.send(ctx, LinkService.ACTION_LOOK)
                        }
                        BigButton("Forget", Modifier.weight(1f), primary = false) {
                            com.victor.traillink.data.Prefs.update(ctx) { it.copy(lastDevice = null, lastDeviceName = null, lastDeviceNode = 0) }
                        }
                    }
                }
            }
        } else if (link.conn == Conn.IDLE) {
            Text("No radio remembered yet. Tap Search for radios (Bluetooth); the radio you connect is remembered and reconnected when the app opens.", fontSize = 15.sp)
        }
        BigButton(if (scanning) "Cancel search (searching Bluetooth…)" else "🔍 Search for radios", Modifier.fillMaxWidth()) {
            if (scanning) scanning = false else startSearch()
        }
        scanError?.let { Text(it, color = Color(0xFFFF8A80)) }
        found.values.sortedByDescending { it.rssi }.forEach { f ->
            Card(Modifier.fillMaxWidth().clickable {
                LinkService.send(ctx, LinkService.ACTION_CONNECT, f.address, f.name)
            }, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("${f.address}${if (f.bonded) " · paired" else ""}${if (f.rssi != 0) " · ${f.rssi} dBm" else ""}", fontSize = 15.sp)
                    }
                    Text("Connect ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp)) {
                Text("Bluetooth PIN", fontWeight = FontWeight.Bold)
                Text("When Android asks for a PIN, read it off the radio's screen (WisMesh Pocket shows a random 6-digit PIN while pairing). Radios without a screen usually use 123456.", fontSize = 15.sp)
            }
        }
        if (log.isNotEmpty()) {
            Text("Log", fontWeight = FontWeight.Bold)
            log.takeLast(25).reversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFB0BEC5)) }
        }
    }
}

@Composable
fun SetupWizard(radio: com.victor.traillink.service.RadioInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val progress by LinkRepo.setup.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf(SetupRole.FIELD) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var longName by remember { mutableStateOf(radio.longName?.takeUnless { it.startsWith("Meshtastic") } ?: "") }
    var shortName by remember { mutableStateOf(radio.shortName ?: "") }
    var moving by remember { mutableFloatStateOf(60f) }
    var still by remember { mutableFloatStateOf(10f) }
    var minDist by remember { mutableFloatStateOf(50f) }
    var aead by remember { mutableStateOf(radio.aeadSupported) }
    var relayKey by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var alsoNet by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pass) {
        fingerprint = null
        if (Psk.normalize(pass).length >= Psk.MIN_LENGTH) {
            delay(600)
            val p = pass
            fingerprint = withContext(Dispatchers.Default) { "${Psk.channelName(p)} · key ${Psk.fingerprint(Psk.derive(p))}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set up radio · step ${step + 1} of 4", style = MaterialTheme.typography.titleLarge)
        when (step) {
            0 -> {
                Text("What is this radio for?")
                RoleCard("Field radio (carried)", "TRACKER role. Sends its own GPS position on its own — no phone needed. Every ~${moving.toInt()} s while moving, every ~${still.toInt()} min while still.", role == SetupRole.FIELD) { role = SetupRole.FIELD }
                RoleCard("Home radio (with this phone)", "CLIENT role. Stays paired to the phone running Sovereign Comms and receives everyone's positions.", role == SetupRole.HOME) { role = SetupRole.HOME }
                RoleCard("Relay (left up high)", "ROUTER role. Put it on a ridge, roof or mast to bounce signals over hills. It always rebroadcasts and uses little airtime itself. Leave it powered (solar/USB pack).", role == SetupRole.RELAY) { role = SetupRole.RELAY }
            }
            1 -> {
                Text("Every radio in your group must use the same passcode. It becomes the AES-256 channel key.")
                OutlinedTextField(pass, { pass = it }, label = { Text("Passcode (8+ characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(pass2, { pass2 = it }, label = { Text("Type it again") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(show, { show = it }); Text("Show passcode") }
                Text(fingerprint?.let { "Channel / key check: $it\n(same on every radio = same passcode)" } ?: "", fontFamily = FontFamily.Monospace)
                OutlinedTextField(longName, { longName = it.take(24) }, label = { Text("Radio name (e.g. Victor field)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shortName, { shortName = it.take(4) }, label = { Text("Short name, up to 4 letters (shown on map)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            2 -> {
                when (role) {
                    SetupRole.FIELD -> {
                        Text("Moving: send at most every ${moving.toInt()} s"); Slider(moving, { moving = it }, valueRange = 30f..300f, steps = 8)
                        Text("Still: send every ${still.toInt()} min"); Slider(still, { still = it }, valueRange = 2f..60f)
                        Text("Counts as moving after ${minDist.toInt()} m"); Slider(minDist, { minDist = it }, valueRange = 25f..500f)
                        Text("Shorter intervals use more battery and airtime. 60 s / 10 min is a good default for two to four radios.", fontSize = 14.sp)
                    }
                    SetupRole.HOME -> Text("The home radio sends its own position every 15 min (so field radios know where home is) and relays others.")
                    SetupRole.RELAY -> {
                        Text("By default the relay gets the same channel name (same frequency) but NOT your key: it rebroadcasts your encrypted packets without being able to read them, so a stolen relay reveals nothing.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(relayKey, { relayKey = it })
                            Text("Store the passcode on the relay too (shows the relay on the map; less safe if it's taken)")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(aead, { aead = it }, enabled = radio.aeadSupported)
                    Spacer(Modifier.width(10.dp))
                    Text(if (radio.aeadSupported) "AEAD tamper protection. ALL your radios must have it ON (firmware 2.8.1+), or they can't hear each other."
                        else "AEAD unavailable: this radio runs ${radio.firmware ?: "?"}, needs 2.8.1+. Leave it off on every radio.", fontSize = 15.sp)
                }
            }
            3 -> {
                Text("Ready to configure ${radio.longName ?: "radio"}:", fontWeight = FontWeight.Bold)
                Text("• Region US 915 MHz, LongFast preset, hop limit 3\n• Private channel ${Psk.channelName(pass)} (AES-256${if (aead) ", AEAD" else ""})\n• Role ${when (role) { SetupRole.FIELD -> "TRACKER"; SetupRole.HOME -> "CLIENT"; SetupRole.RELAY -> "ROUTER" }}\n• Name '$longName' ($shortName)${if (role != SetupRole.RELAY) "\n• Messages show on the radio's screen; canned replies (OK, On my way, Need help…) can be sent with the radio's button" else ""}")
                Text("The radio reboots afterwards; this takes about 20 seconds.", fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoNet, { alsoNet = it })
                    Text("Also use this passcode for internet sharing on this phone (turn sharing on in the Internet tab)", fontSize = 15.sp)
                }
                progress.steps.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
                progress.error?.let { Text("Error: $it", color = Color(0xFFFF5252)) }
                if (progress.done) Text("Done ✓", color = Color(0xFF69F0AE), style = MaterialTheme.typography.titleLarge)
            }
        }
        val passOk = Psk.normalize(pass).length >= Psk.MIN_LENGTH && pass == pass2
        val namesOk = longName.isNotBlank() && shortName.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (step == 0) "Cancel" else "Back", Modifier.weight(1f), primary = false, enabled = !progress.running) {
                if (step == 0 || progress.done) onClose() else step--
            }
            when {
                step < 3 -> BigButton("Next", Modifier.weight(1f), enabled = step != 1 || (passOk && namesOk)) { step++ }
                progress.done -> BigButton("Close", Modifier.weight(1f)) { onClose() }
                else -> BigButton(if (progress.running) "Working…" else "Apply", Modifier.weight(1f), enabled = !progress.running) {
                    if (alsoNet) { val p = pass; scope.launch { saveNetPasscode(ctx, p, null, null) } }
                    LinkRepo.controller?.applySetup(MeshCodec.SetupPlan(role, pass, longName.trim(), shortName.trim().uppercase(),
                        moving.toInt(), still.toInt() * 60, minDist.toInt(), aead && radio.aeadSupported, relayKey))
                        ?: LinkRepo.setup { it.copy(error = "Service not running — reconnect the radio") }
                }
            }
        }
    }
}

@Composable
private fun RoleCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF3E2C10) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column { Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(body, fontSize = 15.sp) }
        }
    }
}
