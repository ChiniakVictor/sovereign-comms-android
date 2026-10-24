package com.victor.traillink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

enum class Transport(val label: String) { AUTO("Auto (radio + internet)"), RADIO("Radio only"), NET("Internet only") }

data class Settings(
    val metric: Boolean = false,
    val lostMinutes: Int = 20,
    val lostAlarm: Boolean = true,
    val trailHours: Int = 24,
    val dimMap: Boolean = true,
    val lastDevice: String? = null,
    val lastDeviceName: String? = null,
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = false,
    // ---- internet sharing (1.1)
    val transport: Transport = Transport.AUTO,
    val netSharing: Boolean = false,          // user switched sharing on
    val netGroup: String? = null,             // channel name (TLxxxxxx), display only
    val netKeyHex: String? = null,            // derived AES key (the passcode itself is never stored)
    val deviceId: String = "",
    val myName: String = "",
    val myShort: String = "",
    val brokerUrl: String = DEFAULT_BROKER,
    val brokerUser: String = "",
    val brokerPass: String = "",
    val netMovingSec: Int = 30,
    val netStillSec: Int = 300,
    val gateway: Boolean = true,
    val keepLocationOn: Boolean = true,       // turn system Location back on while sharing (needs WRITE_SECURE_SETTINGS)
    // ---- map screen (1.3)
    val sheetState: Int = 0,                  // 0 collapsed, 1 half, 2 expanded
    val fullscreen: Boolean = false,
    val panelWidthDp: Int = 360,
    val panelCollapsed: Boolean = false,
    // ---- messages (1.4) / voice (1.5)
    val quickReplies: String = "",            // extra quick replies, one per line
    val voiceAutoPlay: Boolean = true,
    val volumeKeyPtt: Boolean = false,
    // ---- possible-range circle (1.5.2)
    val rangeSpeedMps: Float = 1.34f,
    val rangeMaxMin: Int = 120,
    // ---- Kodiak offline layer (1.7)
    val layerEasements: Boolean = true,
    val layerRow: Boolean = true,
    val easementCheck: Boolean = true,
    /** 2.3: master switch for the whole "Kodiak Island" layer group. */
    val layerKodiak: Boolean = true,
    /** 2.3: statewide Alaska easements group (BLM 17(b) outside Kodiak, RS 2477, DNR public access). */
    val layerAlaska: Boolean = true,
    val layerAk17b: Boolean = true,
    val layerAkRs2477: Boolean = true,
    val layerAkDnr: Boolean = true,
    // ---- 1.8
    val layerTrails: Boolean = true,
    val layerLogRoads: Boolean = true,
    // ---- 2.0 app-to-app calls
    val callsEnabled: Boolean = true,
    val stunUrl: String = DEFAULT_STUN,
    val turnUrl: String = "",                 // e.g. turn:my-vps.example.com:3478  (blank = none)
    val turnUser: String = "",
    val turnPass: String = "",
    val turnOnly: Boolean = false,            // always relay through TURN (hides your IP from the other phone)
    // ---- 2.2 track visibility (display only) + own track recording
    val tracksVisible: Boolean = true,
    val hiddenTracks: Set<Long> = emptySet(), // node numbers whose tracks are hidden
    val recordMyTrack: Boolean = true,
    // ---- 2.3 worldwide maps
    val basemap: String = "usgs",             // 2.4 default "usgs" (US topo, falls back to World outside the US); or "world"
    val terrainShade: Boolean = true,
    val offlineMapsOnly: Boolean = false,     // never fetch map tiles online, use only downloaded areas
    val mapsMobileData: Boolean = false,      // allow area downloads on mobile data
) {
    val netReady get() = netKeyHex != null && myName.isNotBlank()
    val internetAllowed get() = transport != Transport.RADIO
    val radioAllowed get() = transport != Transport.NET
    companion object { const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"; const val DEFAULT_STUN = "stun:stun.l.google.com:19302" }
}

object Prefs {
    /** Rotating retained-clip slot 0..4 for push-to-talk. */
    fun nextVoiceSlot(ctx: android.content.Context): Int {
        val p = ctx.getSharedPreferences("voice", android.content.Context.MODE_PRIVATE)
        val n = (p.getInt("slot", -1) + 1) % com.victor.traillink.core.VoiceWire.SLOTS
        p.edit().putInt("slot", n).apply(); return n
    }
    private const val FILE = "traillink"
    const val KODIAK_LAYERS_RESET_KEY = "kodiakLayersOn_2_3"
    const val USGS_DEFAULT_KEY = "basemapUsgs_2_4"
    private val _s = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = _s
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context): Settings {
        if (loaded) return _s.value
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        var id = p.getString("deviceId", null)
        if (id == null) { id = "%08x".format(Random.nextInt()); p.edit().putString("deviceId", id).apply() }
        // 2.3: Kodiak Island layers are one group, all on by default. Turn them all on once for upgrades from <= 2.2.
        if (!p.getBoolean(KODIAK_LAYERS_RESET_KEY, false)) {
            p.edit().putBoolean("layerKodiak", true).putBoolean("layerEasements", true).putBoolean("layerRow", true)
                .putBoolean("layerTrails", true).putBoolean("layerLogRoads", true).putBoolean("easementCheck", true)
                .putBoolean(KODIAK_LAYERS_RESET_KEY, true).apply()
        }
        // 2.4: USGS Topo is the default base map. Switch to it once on upgrade (and on fresh installs); the user's later choice sticks.
        if (!p.getBoolean(USGS_DEFAULT_KEY, false)) {
            p.edit().putString("basemap", "usgs").putBoolean(USGS_DEFAULT_KEY, true).apply()
        }
        _s.value = Settings(
            metric = p.getBoolean("metric", false),
            lostMinutes = p.getInt("lostMinutes", 20),
            lostAlarm = p.getBoolean("lostAlarm", true),
            trailHours = p.getInt("trailHours", 24),
            dimMap = p.getBoolean("dimMap", true),
            lastDevice = p.getString("lastDevice", null),
            lastDeviceName = p.getString("lastDeviceName", null),
            autoReconnect = p.getBoolean("autoReconnect", true),
            keepScreenOn = p.getBoolean("keepScreenOn", false),
            transport = runCatching { Transport.valueOf(p.getString("transport", "AUTO")!!) }.getOrDefault(Transport.AUTO),
            netSharing = p.getBoolean("netSharing", false),
            netGroup = p.getString("netGroup", null),
            netKeyHex = p.getString("netKeyHex", null),
            deviceId = id,
            myName = p.getString("myName", "") ?: "",
            myShort = p.getString("myShort", "") ?: "",
            brokerUrl = p.getString("brokerUrl", Settings.DEFAULT_BROKER) ?: Settings.DEFAULT_BROKER,
            brokerUser = p.getString("brokerUser", "") ?: "",
            brokerPass = p.getString("brokerPass", "") ?: "",
            netMovingSec = p.getInt("netMovingSec", 30),
            netStillSec = p.getInt("netStillSec", 300),
            gateway = p.getBoolean("gateway", true),
            keepLocationOn = p.getBoolean("keepLocationOn", true),
            sheetState = p.getInt("sheetState", 0),
            fullscreen = p.getBoolean("fullscreen", false),
            panelWidthDp = p.getInt("panelWidthDp", 360),
            panelCollapsed = p.getBoolean("panelCollapsed", false),
            quickReplies = p.getString("quickReplies", "") ?: "",
            voiceAutoPlay = p.getBoolean("voiceAutoPlay", true),
            volumeKeyPtt = p.getBoolean("volumeKeyPtt", false),
            rangeSpeedMps = p.getFloat("rangeSpeedMps", 1.34f),
            rangeMaxMin = p.getInt("rangeMaxMin", 120),
            layerEasements = p.getBoolean("layerEasements", true),
            layerRow = p.getBoolean("layerRow", true),
            easementCheck = p.getBoolean("easementCheck", true),
            layerKodiak = p.getBoolean("layerKodiak", true),
            layerAlaska = p.getBoolean("layerAlaska", true),
            layerAk17b = p.getBoolean("layerAk17b", true),
            layerAkRs2477 = p.getBoolean("layerAkRs2477", true),
            layerAkDnr = p.getBoolean("layerAkDnr", true),
            layerTrails = p.getBoolean("layerTrails", true),
            layerLogRoads = p.getBoolean("layerLogRoads", true),
            callsEnabled = p.getBoolean("callsEnabled", true),
            stunUrl = p.getString("stunUrl", Settings.DEFAULT_STUN) ?: Settings.DEFAULT_STUN,
            turnUrl = p.getString("turnUrl", "") ?: "",
            turnUser = p.getString("turnUser", "") ?: "",
            turnPass = p.getString("turnPass", "") ?: "",
            turnOnly = p.getBoolean("turnOnly", false),
            tracksVisible = p.getBoolean("tracksVisible", true),
            hiddenTracks = (p.getStringSet("hiddenTracks", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet(),
            recordMyTrack = p.getBoolean("recordMyTrack", true),
            basemap = (p.getString("basemap", "usgs") ?: "usgs").let { if (it == "world") "world" else "usgs" },
            terrainShade = p.getBoolean("terrainShade", true),
            offlineMapsOnly = p.getBoolean("offlineMapsOnly", false),
            mapsMobileData = p.getBoolean("mapsMobileData", false),
        )
        loaded = true
        return _s.value
    }

    @Synchronized
    fun update(ctx: Context, f: (Settings) -> Settings) {
        load(ctx)
        val n = f(_s.value)
        _s.value = n
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("metric", n.metric).putInt("lostMinutes", n.lostMinutes)
            .putBoolean("lostAlarm", n.lostAlarm).putInt("trailHours", n.trailHours)
            .putBoolean("dimMap", n.dimMap).putString("lastDevice", n.lastDevice)
            .putString("lastDeviceName", n.lastDeviceName).putBoolean("autoReconnect", n.autoReconnect)
            .putBoolean("keepScreenOn", n.keepScreenOn)
            .putString("transport", n.transport.name).putBoolean("netSharing", n.netSharing)
            .putString("netGroup", n.netGroup).putString("netKeyHex", n.netKeyHex)
            .putString("deviceId", n.deviceId).putString("myName", n.myName).putString("myShort", n.myShort)
            .putString("brokerUrl", n.brokerUrl).putString("brokerUser", n.brokerUser).putString("brokerPass", n.brokerPass)
            .putInt("netMovingSec", n.netMovingSec).putInt("netStillSec", n.netStillSec).putBoolean("gateway", n.gateway)
            .putBoolean("keepLocationOn", n.keepLocationOn).putInt("sheetState", n.sheetState).putBoolean("fullscreen", n.fullscreen)
            .putInt("panelWidthDp", n.panelWidthDp).putBoolean("panelCollapsed", n.panelCollapsed)
            .putString("quickReplies", n.quickReplies).putBoolean("voiceAutoPlay", n.voiceAutoPlay).putBoolean("volumeKeyPtt", n.volumeKeyPtt)
            .putFloat("rangeSpeedMps", n.rangeSpeedMps).putInt("rangeMaxMin", n.rangeMaxMin)
            .putBoolean("layerEasements", n.layerEasements).putBoolean("layerRow", n.layerRow).putBoolean("easementCheck", n.easementCheck).putBoolean("layerKodiak", n.layerKodiak)
            .putBoolean("layerAlaska", n.layerAlaska).putBoolean("layerAk17b", n.layerAk17b).putBoolean("layerAkRs2477", n.layerAkRs2477).putBoolean("layerAkDnr", n.layerAkDnr)
            .putBoolean("layerTrails", n.layerTrails).putBoolean("layerLogRoads", n.layerLogRoads)
            .putBoolean("callsEnabled", n.callsEnabled).putString("stunUrl", n.stunUrl).putString("turnUrl", n.turnUrl)
            .putString("turnUser", n.turnUser).putString("turnPass", n.turnPass).putBoolean("turnOnly", n.turnOnly)
            .putBoolean("tracksVisible", n.tracksVisible).putStringSet("hiddenTracks", n.hiddenTracks.map { it.toString() }.toSet())
            .putBoolean("recordMyTrack", n.recordMyTrack)
            .putString("basemap", n.basemap).putBoolean("terrainShade", n.terrainShade).putBoolean("offlineMapsOnly", n.offlineMapsOnly)
            .putBoolean("mapsMobileData", n.mapsMobileData)
            .apply()
    }

    /** Monotonic per-install sequence number for internet messages. */
    @Synchronized
    fun nextSeq(ctx: Context): Long {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = p.getLong("seq", 0) + 1
        p.edit().putLong("seq", s).apply()
        return s
    }
}
