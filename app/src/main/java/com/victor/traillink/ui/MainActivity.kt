package com.victor.traillink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.sp
import com.victor.traillink.data.Prefs
import com.victor.traillink.map.OfflineMaps
import com.victor.traillink.service.LinkRepo
import com.victor.traillink.service.LinkService
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String, val icon: String, val inBar: Boolean = true) { MAP("Map", "🗺"), PARTNERS("Partners", "👥"), MESSAGES("Chat", "💬"), RADIO("Radio", "📡"), INTERNET("Internet", "🌐"), MORE("More", "☰"), MAPS("Offline maps", "⬇", false) }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val tab = MutableStateFlow(Tab.MAP)
    private val about = MutableStateFlow(false)
    private val mapHandle = MapHandle()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startSensors(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.load(this)
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) need += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) perms.launch(need.toTypedArray())
        handleIntent(intent)
        OfflineMaps.refresh(this)
        if (Prefs.settings.value.netSharing) LinkService.send(this, LinkService.ACTION_NET_REFRESH)

        setContent {
            TrailTheme {
                val t by tab.collectAsState()
                val showAbout by about.collectAsState()
                val s by vm.settings.collectAsState()
                LaunchedEffect(s.keepScreenOn) {
                    if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val unread by vm.unread.collectAsState()
                val mapFull = t == Tab.MAP && !showAbout && s.fullscreen
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                Scaffold(bottomBar = {
                    if (!mapFull) NavigationBar {
                        Tab.entries.filter { it.inBar }.forEach { e ->
                            NavigationBarItem(selected = t == e || (e == Tab.MORE && t == Tab.MAPS), onClick = { about.value = false; tab.value = e },
                                modifier = Modifier.tourTarget("tab-" + e.name),
                                icon = {
                                    if (e == Tab.MESSAGES && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Text(e.icon, fontSize = 20.sp) }
                                    else Text(e.icon, fontSize = 20.sp)
                                }, label = { Text(e.label, fontSize = 11.sp, maxLines = 1) })
                        }
                    }
                }) { pad ->
                    val mapTab = t == Tab.MAP && !showAbout
                    val talking = vm.link.collectAsState().value.talking
                    Box((if (mapTab) Modifier.padding(bottom = pad.calculateBottomPadding()) else Modifier.padding(pad)).fillMaxSize()) {
                        if (showAbout) AboutScreen { about.value = false }
                        else when (t) {
                            Tab.MAP -> MapScreen(vm, mapHandle, bottomInset = 0.dp,
                                onGoRadio = { tab.value = Tab.RADIO }, onGoInternet = { tab.value = Tab.INTERNET })
                            Tab.PARTNERS -> Column {
                                var pinsTab by rememberSaveable { mutableStateOf(false) }
                                TabRow(if (pinsTab) 1 else 0) {
                                    Tab(!pinsTab, { pinsTab = false }, text = { Text("Partners") })
                                    Tab(pinsTab, { pinsTab = true }, text = { Text("📍 Pins") })
                                }
                                if (pinsTab) PinsList(vm) { p ->
                                    com.victor.traillink.service.LinkRepo.log("Map: open pin ${p.name} (pins list)")
                                    vm.selectedPin.value = p.id; tab.value = Tab.MAP; mapHandle.flyTo(p.lat, p.lon, 15.0)
                                }
                                else PartnersScreen(vm) { p ->
                                    com.victor.traillink.service.LinkRepo.log("Map: focus ${p.node.longName} (partners tab)")
                                    vm.focus.value = p.node.num; tab.value = Tab.MAP
                                    p.node.lat?.let { la -> mapHandle.flyTo(la, p.node.lon!!, 14.0) }
                                }
                            }
                            Tab.MESSAGES -> MessagesScreen(vm, onShowOnMap = { la, lo -> tab.value = Tab.MAP; mapHandle.flyTo(la, lo, 15.0) })
                            Tab.RADIO -> RadioScreen(vm)
                            Tab.MAPS -> MapsScreen()
                            Tab.INTERNET -> InternetScreen(vm)
                            Tab.MORE -> MoreScreen(vm, onAbout = { about.value = true }, onMaps = { tab.value = Tab.MAPS })
                        }
                        val call by com.victor.traillink.call.CallManager.ui.collectAsState()
                        if (call.busy)
                            Text("📞 On a call with ${call.peerName} — tap to return", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp)
                                    .background(androidx.compose.ui.graphics.Color(0xE01E4F8F), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                    .clickable { com.victor.traillink.call.CallManager.openScreen(this@MainActivity) }.padding(horizontal = 12.dp, vertical = 5.dp))
                        if (talking != null && talking != "You" && t != Tab.MESSAGES)
                            Text("🔊 $talking is talking", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 28.dp)
                                    .background(androidx.compose.ui.graphics.Color(0xE01B5E20), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                    .clickable { tab.value = Tab.MESSAGES }.padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
                OnboardingHost("Sovereign Comms", SovereignTour.PRIVACY, SovereignTour.steps { about.value = false; tab.value = Tab.MAP })
                CallRingPermissionPrompt()
                }
            }
        }
    }

    private var volPtt = false
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && vm.settings.value.volumeKeyPtt) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return true
                }
                val err = PttControl.start(this, vm.openConvo.value ?: "group")
                if (err != null) android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show() else volPtt = true
            } else if (event.action == android.view.KeyEvent.ACTION_UP && volPtt) { PttControl.stop(); volPtt = false }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() { com.victor.traillink.call.AppVisible.resumed--; super.onPause(); if (volPtt) { PttControl.stop(); volPtt = false } }
    override fun onResume() {
        super.onResume(); com.victor.traillink.call.AppVisible.resumed++
        // a call rang while we were in the background: bring up its screen
        val c = com.victor.traillink.call.CallManager.ui.value
        if (c.phase == com.victor.traillink.call.CallUi.Phase.INCOMING) com.victor.traillink.call.CallManager.openScreen(this)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    /** Notification taps and navigation:  am start -n com.victor.traillink/.ui.MainActivity --es tab map */
    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("tab")?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, true) }?.let { tab.value = it; about.value = false } }
        if (i.getStringExtra("tab") == "about") about.value = true
        i.getStringExtra("convo")?.let { vm.openConvo.value = it }
        i.getStringExtra("pin")?.let { id -> vm.selectedPin.value = id; vm.pins.value.firstOrNull { it.id == id }?.let { mapHandle.flyTo(it.lat, it.lon, 15.0) } }
        if (i.hasExtra("focusNode")) vm.focus.value = i.getLongExtra("focusNode", 0)
        if (i.hasExtra("focus")) {
            val idx = i.getIntExtra("focus", 0)
            vm.partners.value.filter { it.node.lat != null }.getOrNull(idx)?.let { p -> vm.focus.value = p.node.num; mapHandle.flyTo(p.node.lat!!, p.node.lon!!, 14.0) }
        }
        if (i.getBooleanExtra("fit", false)) mapHandle.didInitialFit = false
    }

    override fun onStart() { super.onStart(); mapHandle.rearm(System.currentTimeMillis()); vm.startSensors(this) }
    override fun onStop() { mapHandle.autoCenter.onBackground(System.currentTimeMillis()); vm.stopSensors(); super.onStop() }
}

/** First-run privacy note and guided tour content (More → Privacy / Tutorial reopen them). */
object SovereignTour {
    val PRIVACY = listOf(
        "Only your group can see your location. Nobody outside your group can track you with this app.",
        "Your group is everyone who uses the same passcode. Your location, messages, pins and voice are locked (encrypted) with it before they leave your phone. This is true when your radios are set up in this app with your group passcode.",
        "We don't sell your info.",
        "We don't run a server, and we don't store your info on one.",
        "Radio only: messages go from your phone to your LoRa radio and on to your group's radios. No internet is used.",
        "If you turn on Internet sharing: your locked info passes through a free public relay. The relay can't read it. It keeps the newest locked copies so friends who were offline can catch up: messages and voice for about a day, pins until they expire, and your last location until you tap Stop & forget.",
        "Calls (new in 2.0): your voice goes straight from your phone to the other phone, locked end to end (encrypted). The call setup is locked with your group passcode and passes through the same relay as messages. If a phone network blocks direct calls, you can add your own relay (TURN) server; it only passes along locked audio and can't listen in. Calls are never recorded or stored.",
        "There are no accounts, no ads and no trackers.",
    )
    fun steps(toMap: () -> Unit) = listOf(
        TourStep(null, "Sovereign Comms tour", "A quick look at the main parts of the app. Tap Next to go on, Back to go back, or Skip to leave the tour.", toMap),
        TourStep("tab-RADIO", "Group setup and passcode", "Start on the Radio tab. Pick one passcode for your group (8+ characters) and use it on every phone and radio. The group check code must match on every phone."),
        TourStep("tab-MESSAGES", "Messaging", "Chat has a group chat and direct messages. Messages go over the radio, the internet, or both, whichever can reach your group."),
        TourStep("tab-RADIO", "LoRa radio messaging", "Pair a LoRa radio here and the app sets it up for your group. Then messages and locations reach your group with no cell service. Messages also show on the radio's screen, and it can send quick replies with its button."),
        TourStep("tab-PARTNERS", "Removing old partners", "Long-press a partner (or tap Remove) to take them off this phone, with their trail and their pins. Your direct chat is kept unless you tick “Also delete our chat”. They come back only if they're active again, or tick Block to keep them hidden. More → Partners can clear out everyone not heard in 7, 30 or 90 days."),
        TourStep("tab-MESSAGES", "SOS", "In Chat, long-press “Need help” to send an SOS with your position. It shows in red on everyone's map."),
        TourStep("tab-MESSAGES", "Push-to-talk", "In Chat, hold the talk button to send your voice (needs internet). You can also turn on volume-down as push-to-talk in More."),
        TourStep("tab-MESSAGES", "Calls", "Open a partner's chat and tap 📞 Call to talk live, like a phone call, over Wi-Fi or cell data. No phone number needed. Both phones need Internet sharing on. Calls ring even when the screen is off. If calls won't connect (Starlink can block them), add a relay server in More → Calls."),
        TourStep("tab-INTERNET", "Location sharing", "Turn on Share my position here to share over Wi-Fi or cell when radios are out of range. It uses the same group passcode."),
        TourStep("gps", "You and the accuracy circle", "The blue dot is you. Its circle shows how accurate your GPS is. A partner's circle starts at their GPS accuracy and grows over time since they were last heard. Tap here to follow yourself."),
        TourStep("pins", "Map pins and arrow navigation", "Drop a pin at your spot, or long-press the map to pin anywhere. Tap a pin, then Go, for an arrow and distance that guide you to it."),
        TourStep("tracks", "Tracks", "👣 opens Tracks. Turn all tracks on or off, or tick each partner. Hiding only changes the map; nothing is deleted. You can also clear track history (it asks first, and never touches partners or chats)."),
        TourStep("tracks", "Backtrack", "Lost the trail? In 👣 tap Backtrack: the arrow leads you back along your own recorded track, waypoint by waypoint, to the start or a point you pick, with distance to the next point and distance left. Works offline with GPS only. ↩ next to a partner follows their track to reach them."),
        TourStep("layers", "Map layers", "Pick the base map (worldwide street map, or USGS topo in the US) and terrain shading. Over Kodiak you can also turn on 17(b) easements, the state road right-of-way, trails and logging roads. Tap anything on the map for its card. On the folded screen this is in the ⋯ menu."),
        TourStep("update", "Check for update", "This button checks kodiakking.com for a newer version. It checks once when the app opens."),
        TourStep("tab-MORE", "Settings", "More has the settings. There you can replay this Tutorial, read the Privacy note again, and check for updates."),
    )
}

/** Android 14+: ask once to allow the incoming-call screen over the lock screen. */
@Composable
fun CallRingPermissionPrompt() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by Prefs.settings.collectAsState()
    val stage by Onboarding.stage.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("calls", android.content.Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(stage, s.netSharing, s.callsEnabled) {
        show = stage == Onboarding.Stage.DONE && s.callsEnabled && s.netSharing &&
            !com.victor.traillink.call.CallManager.canRingFullScreen(ctx) && !prefs.getBoolean("fsiAsked", false)
    }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text("Let calls ring on the lock screen") },
        text = { Text("So a call can wake your phone and show Answer / Decline when the screen is off, allow \"full screen notifications\" for Sovereign Comms on the next screen.", fontSize = 15.sp) },
        confirmButton = { Button({ prefs.edit().putBoolean("fsiAsked", true).apply(); show = false
            runCatching { ctx.startActivity(com.victor.traillink.call.CallManager.fullScreenSettingsIntent(ctx)) } }) { Text("Open setting") } },
        dismissButton = { TextButton({ prefs.edit().putBoolean("fsiAsked", true).apply(); show = false }) { Text("Not now") } })
}
