package com.victor.traillink.ui

import com.victor.traillink.service.LinkService
import com.victor.traillink.service.NavState
import com.victor.traillink.data.PinEntity
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.victor.traillink.core.Geo
import com.victor.traillink.data.NodeEntity
import com.victor.traillink.core.Range
import com.victor.traillink.data.Prefs
import com.victor.traillink.data.TrackPoint
import com.victor.traillink.data.TrailDb
import com.victor.traillink.data.MessageEntity
import com.victor.traillink.service.LinkRepo
import com.victor.traillink.service.PhoneLocation
import com.victor.traillink.service.PhoneLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

val PALETTE = listOf(0xFFFF5252, 0xFF40C4FF, 0xFFFFD740, 0xFF69F0AE, 0xFFE040FB, 0xFFFF9E40, 0xFFB2FF59, 0xFFF8BBD0).map { it.toInt() }
const val SELF_COLOR = 0xFFFFFFFF.toInt()
const val PHONE_COLOR = 0xFF2979FF.toInt()

data class PartnerUi(
    val node: NodeEntity, val color: Int, val trail: List<TrackPoint>,
    val distanceM: Double?, val bearingDeg: Double?,
)

/** Possible-range radius (m) and age for a partner right now. */
fun PartnerUi.range(now: Long, s: com.victor.traillink.data.Settings): Triple<Double, Long, Long>? {
    val n = node
    if (n.lat == null || n.isSelf) return null
    val fixT = if (n.posTime > 0) n.posTime else n.lastHeard
    val age = now - fixT
    val maxAge = s.rangeMaxMin * 60_000L
    val last = trail.lastOrNull(); val prev = trail.getOrNull(trail.size - 2)
    val derived = Range.derivedSpeed(prev?.let { Triple(it.time, it.lat, it.lon) }, last?.let { Triple(it.time, it.lat, it.lon) })
    val v = Range.speedMps(s.rangeSpeedMps.toDouble(), derived, last?.let { now - it.time })
    return Triple(Range.radiusM(n.accM?.toDouble(), age, v, maxAge), age, maxAge)
}

data class PhoneFix(val lat: Double, val lon: Double, val accuracy: Float?, val time: Long)

enum class LocStatus { OK, NO_PERMISSION, SYSTEM_OFF, WAITING }

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {
    private val dao = TrailDb.get(app).dao()

    val link = LinkRepo.state
    val settings = Prefs.settings

    private val _phone = MutableStateFlow<PhoneFix?>(null)
    val phone: StateFlow<PhoneFix?> get() = _phone
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> get() = _heading
    val focus = MutableStateFlow<Long?>(null)
    val now = flow { while (true) { emit(System.currentTimeMillis()); delay(1000) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    private val pointsFlow = settings.map { it.trailHours }.distinctUntilChanged()
        .flatMapLatest { h -> dao.pointsSince(System.currentTimeMillis() - h * 3_600_000L) }

    /** Every node on our channel: self radio first, then partners by color slot. */
    val partners: StateFlow<List<PartnerUi>> = combine(dao.nodes(), pointsFlow, _phone) { nodes, pts, ph ->
        val visible = nodes
        val byNode = pts.groupBy { it.nodeNum }
        val others = visible.filter { !it.isSelf }.sortedBy { it.num }
        visible.map { n ->
            val color = if (n.isSelf) SELF_COLOR else PALETTE[others.indexOf(n).coerceAtLeast(0) % PALETTE.size]
            val d = if (ph != null && n.lat != null) Geo.distanceM(ph.lat, ph.lon, n.lat, n.lon!!) else null
            val b = if (ph != null && n.lat != null) Geo.bearingDeg(ph.lat, ph.lon, n.lat, n.lon!!) else null
            PartnerUi(n, color, byNode[n.num].orEmpty(), d, b)
        }.sortedWith(compareBy({ !it.node.isSelf }, { others.indexOf(it.node) }))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Newest system last-known location if younger than [maxAgeMs] (used to centre the map before a fresh fix). */
    @android.annotation.SuppressLint("MissingPermission")
    fun lastKnownRecent(ctx: Context, maxAgeMs: Long): PhoneFix? {
        if (!PhoneLocator.hasPermission(ctx)) return null
        val lm = ctx.getSystemService(android.location.LocationManager::class.java) ?: return null
        val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - best.elapsedRealtimeNanos) / 1_000_000
        return if (age in 0..maxAgeMs) PhoneFix(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy else null, System.currentTimeMillis() - age) else null
    }

    // ---------------------------------------------------------------- pins
    private val pinDao = TrailDb.get(app).pins()
    val pins: StateFlow<List<PinEntity>> = pinDao.visible(0).combine(now) { l, t -> l.filter { it.expires == 0L || it.expires > t } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedPin = MutableStateFlow<String?>(null)
    val nav = NavState.pin

    fun savePin(p: PinEntity) = viewModelScope.launch(Dispatchers.IO) {
        pinDao.put(p); LinkRepo.log("Pin '${p.name}' saved${if (p.shared) " (shared)" else " (only on this phone)"}")
        if (p.shared) LinkService.publishPin(p.id)
    }
    fun deletePin(p: PinEntity) = viewModelScope.launch(Dispatchers.IO) {
        if (p.mine) { pinDao.put(p.copy(deleted = true, updated = System.currentTimeMillis())); if (p.shared) LinkService.publishPin(p.id) }
        else pinDao.put(p.copy(hidden = true))
        if (NavState.pin.value == p.id) NavState.stop(getApplication())
        if (selectedPin.value == p.id) selectedPin.value = null
    }
    fun startNav(id: String) { com.victor.traillink.service.BacktrackState.stop(); NavState.start(id); LinkRepo.log("Navigate to pin $id") }
    fun stopNav() = NavState.stop(getApplication())

    private fun checkArrival(l: Location) {
        val id = NavState.pin.value ?: return
        val p = pins.value.firstOrNull { it.id == id } ?: return
        val d = Geo.distanceM(l.latitude, l.longitude, p.lat, p.lon)
        if (d <= 20 && !NavState.arrived) { NavState.arrived = true; NavState.notifyArrived(getApplication(), p.name) }
    }

    // ---------------------------------------------------------------- messages
    private val msgs = TrailDb.get(app).msgs()
    val unread: StateFlow<Int> = msgs.unread().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val recentMsgs: StateFlow<List<MessageEntity>> = msgs.recent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val openConvo = MutableStateFlow<String?>(null)
    val convoMsgs: StateFlow<List<MessageEntity>> = openConvo.flatMapLatest { c -> if (c == null) flowOf(emptyList()) else msgs.convo(c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sos: StateFlow<List<MessageEntity>> = msgs.sosSince(System.currentTimeMillis() - 12 * 3_600_000L)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun clearConvo(c: String) = viewModelScope.launch(Dispatchers.IO) { msgs.clearConvo(c) }

    /** Peer key used for messaging this node: internet phones by install id, radios by node number. */
    fun peerKey(n: NodeEntity): String = if (n.num > 0xFFFFFFFFL && n.netId != null) "n:${n.netId}" else "l:${n.num}"
    fun nameFor(key: String): String = if (key == "group") "Everyone" else
        partners.value.firstOrNull { peerKey(it.node) == key }?.node?.longName
            ?: recentMsgs.value.firstOrNull { it.fromKey == key }?.fromName ?: key

    // ---------------------------------------------------------------- phone GPS + compass
    private var locator: PhoneLocator? = null
    private val _locStatus = MutableStateFlow(LocStatus.WAITING)
    val locStatus: StateFlow<LocStatus> get() = _locStatus
    private val sm = app.getSystemService(SensorManager::class.java)
    private var declination = 0f
    private var statusJob: kotlinx.coroutines.Job? = null

    private fun computeStatus(ctx: Context): LocStatus = when {
        !PhoneLocator.hasPermission(ctx) -> LocStatus.NO_PERMISSION
        !PhoneLocator.systemEnabled(ctx) -> LocStatus.SYSTEM_OFF
        _phone.value == null || System.currentTimeMillis() - _phone.value!!.time > 120_000 -> LocStatus.WAITING
        else -> LocStatus.OK
    }

    private val _lastKnown = MutableStateFlow<PhoneFix?>(null)
    /** System last-known position (< 10 min), refreshed on every start; used to centre the map before a live fix. */
    val lastKnown: StateFlow<PhoneFix?> get() = _lastKnown

    fun startSensors(ctx: Context) {
        val app = getApplication<Application>()
        _lastKnown.value = lastKnownRecent(app, 10 * 60_000L)
        if (locator == null) locator = PhoneLocator(app, 2000L) { onLocation(it) }
        locator?.let { if (!it.running) it.start() }
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            var wasOn = PhoneLocator.systemEnabled(app)
            while (true) {
                val st = computeStatus(app)
                _locStatus.value = st
                // (re)start updates once permission/Location appear
                val on = PhoneLocator.systemEnabled(app)
                if (on && !wasOn) { locator?.stop(); locator?.start() }
                if (locator?.running != true && PhoneLocator.hasPermission(app)) locator?.start()
                wasOn = on
                delay(1500)
            }
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stopSensors() {
        locator?.stop()
        statusJob?.cancel()
        sm?.unregisterListener(this)
    }

    private fun onLocation(l: Location) {
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 10 * 60_000L) return   // stale cached fix (could be from somewhere else hours ago)
        _phone.value = PhoneFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, System.currentTimeMillis())
        _locStatus.value = computeStatus(getApplication())
        PhoneLocation.last = l.latitude to l.longitude
        com.victor.traillink.service.MyTrack.onFix(getApplication(), l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null,
            if (l.hasAltitude()) l.altitude.toInt() else null)
        stepBacktrack(l.latitude, l.longitude)
        checkArrival(l)
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination
    }

    private val rot = FloatArray(9); private val rot2 = FloatArray(9); private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val r = (getApplication<Application>().getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (ax, ay) = when (r) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, rot2)
        SensorManager.getOrientation(rot2, ori)
        val az = ((Math.toDegrees(ori[0].toDouble()).toFloat() + declination) + 360f) % 360f
        val prev = _heading.value
        _heading.value = if (prev == null) az else {
            var d = az - prev; if (d > 180) d -= 360; if (d < -180) d += 360
            (prev + d * 0.25f + 360f) % 360f
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------------------------------------------------------------- actions
    fun setMuted(num: Long, muted: Boolean) = viewModelScope.launch(Dispatchers.IO) { dao.setMuted(num, muted) }
    fun forget(num: Long) = viewModelScope.launch(Dispatchers.IO) { dao.deletePoints(num); dao.deleteNode(num) }

    /**
     * 2.1: remove a partner from THIS phone only (nothing is sent to anyone): the partner, their trail, your direct chat
     * with them only if deleteChat (incl. voice clips) and the pins they shared. Old/retained data won't bring them back; new activity will,
     * unless [block] is set.
     */
    fun removePartner(num: Long, name: String, block: Boolean, extraKey: String? = null, deleteChat: Boolean = false) =
        viewModelScope.launch(Dispatchers.IO) { removeNow(num, name, block, extraKey, deleteChat) }

    fun removeMany(list: List<NodeEntity>, deleteChat: Boolean = false) =
        viewModelScope.launch(Dispatchers.IO) { list.forEach { removeNow(it.num, it.longName, false, null, deleteChat) } }

    /** [deleteChat] false (default) keeps the direct chat; if the partner shows up again it's the same chat key, so it links back up. */
    private suspend fun removeNow(num: Long, name: String, block: Boolean, extraKey: String?, deleteChat: Boolean) {
        com.victor.traillink.data.RemovedPartners.add(num, name, block)
        val n = dao.node(num)
        val keys = buildSet {
            if (num <= 0xFFFFFFFFL) add("l:$num")
            n?.netId?.let { add("n:$it") }
            extraKey?.let { add(it) }
        }
        dao.deletePoints(num); dao.deleteNode(num)
        var pins = 0
        for (k in keys) {
            if (deleteChat) {
                msgs.clipsIn(k).forEach { runCatching { java.io.File(it).delete() } }
                msgs.clearConvo(k)
                androidx.core.app.NotificationManagerCompat.from(getApplication()).cancel(2000 + (k.hashCode() and 0xFFFF))
                if (openConvo.value == k) openConvo.value = null
            }
            pins += pinDao.deleteBy(k)
        }
        androidx.core.app.NotificationManagerCompat.from(getApplication()).cancel(1000 + (num and 0xFFFF).toInt())
        LinkRepo.log("Removed partner $name from this phone${if (block) " (blocked)" else ""}; chat ${if (deleteChat) "deleted" else "kept"}; $pins pin(s) removed")
    }

    /** Partners (not my radio) not heard for more than [days]. */
    fun stalePartners(days: Int, now: Long = System.currentTimeMillis()): List<NodeEntity> =
        partners.value.map { it.node }.filter { !it.isSelf && (it.lastHeard == 0L || now - it.lastHeard > days * 86_400_000L) }
    fun clearTrail(num: Long) = viewModelScope.launch(Dispatchers.IO) { dao.deletePoints(num); LinkRepo.log("Track history cleared for node $num") }
    /** 2.2: clears partners' track history only (never partners, chats or pins; my own track has its own button). */
    fun clearTrails() = viewModelScope.launch(Dispatchers.IO) { dao.clearPartnerPoints(); LinkRepo.log("All partner track history cleared") }

    // ---------------------------------------------------------------- 2.2 tracks + backtrack
    /** My own recorded track (last 48 h, oldest first). Recorded even when tracks are hidden. */
    val myTrack: StateFlow<List<TrackPoint>> = dao.myTrackSince(System.currentTimeMillis() - 48 * 3_600_000L)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val backtrack = com.victor.traillink.service.BacktrackState.route

    fun setTracksVisible(v: Boolean) = Prefs.update(getApplication()) { it.copy(tracksVisible = v) }
    fun setTrackHidden(num: Long, hidden: Boolean) = Prefs.update(getApplication()) {
        it.copy(hiddenTracks = if (hidden) it.hiddenTracks + num else it.hiddenTracks - num)
    }
    fun setRecordMyTrack(v: Boolean) = Prefs.update(getApplication()) { it.copy(recordMyTrack = v) }
    fun clearMyTrack() = viewModelScope.launch(Dispatchers.IO) {
        dao.deletePoints(com.victor.traillink.service.MyTrack.NUM); com.victor.traillink.service.MyTrack.forget(); LinkRepo.log("My track history cleared")
    }

    /** Backtrack along my own track: back to the start ([fromIndex] 0) or to a point I picked. */
    fun backtrackMine(fromIndex: Int = 0): String? {
        val t = myTrack.value.map { it.lat to it.lon }
        if (t.size < 2) return "No track recorded yet. Your track records while the app has a GPS fix."
        val r = com.victor.traillink.core.Backtrack.reverseRoute(t, fromIndex)
        com.victor.traillink.service.BacktrackState.start(com.victor.traillink.service.BacktrackRoute(
            if (fromIndex == 0) "Back to the start of my track" else "Back to my track point at " + SimpleDateFormat("h:mm a", Locale.US).format(Date(myTrack.value[fromIndex].time)), r))
        NavState.stop(getApplication())
        _phone.value?.let { stepBacktrack(it.lat, it.lon) }
        return null
    }

    /** Follow a partner's received track toward them (ends at their newest position). */
    fun backtrackPartner(num: Long): String? {
        val p = partners.value.firstOrNull { it.node.num == num } ?: return "Partner not found"
        val t = p.trail.map { it.lat to it.lon } + listOfNotNull(p.node.lat?.let { it to p.node.lon!! })
        if (t.size < 2) return "No track received from ${p.node.longName} yet."
        com.victor.traillink.service.BacktrackState.start(com.victor.traillink.service.BacktrackRoute(
            "Along ${p.node.longName}'s track to them", com.victor.traillink.core.Backtrack.forwardRoute(t), partner = num))
        NavState.stop(getApplication())
        _phone.value?.let { stepBacktrack(it.lat, it.lon) }
        return null
    }

    fun stopBacktrack() = com.victor.traillink.service.BacktrackState.stop()

    private fun stepBacktrack(lat: Double, lon: Double) {
        val b = com.victor.traillink.service.BacktrackState.route.value ?: return
        val pr = com.victor.traillink.core.Backtrack.progress(b.points, b.idx, lat, lon)
        if (pr.idx != b.idx) {
            com.victor.traillink.service.BacktrackState.route.value = b.copy(idx = pr.idx)
            if (pr.idx >= b.points.size) LinkRepo.log("Backtrack: arrived (${b.title})")
        }
    }

    suspend fun exportGpx(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val list = partners.value
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Sovereign Comms\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>Sovereign Comms export</name><time>${iso.format(Date())}</time></metadata>\n")
        var pts = 0
        for (p in pins.value) {
            sb.append("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\">${p.alt?.let { "<ele>$it</ele>" } ?: ""}<time>${iso.format(Date(p.created))}</time><name>${esc(p.name)}</name>" +
                "<desc>${esc((com.victor.traillink.core.PinKind.of(p.kind).label + " · by " + (if (p.mine) "me" else p.byName) + if (p.note.isNotBlank()) " · " + p.note else ""))}</desc><sym>${esc(com.victor.traillink.core.PinKind.of(p.kind).label)}</sym></wpt>\n")
        }
        for (p in list) {
            if (p.node.lat != null) sb.append("  <wpt lat=\"${p.node.lat}\" lon=\"${p.node.lon}\">${p.node.altM?.let { "<ele>$it</ele>" } ?: ""}<time>${iso.format(Date(p.node.posTime))}</time><name>${esc(p.node.longName)} (last)</name></wpt>\n")
        }
        for (p in list) {
            if (p.trail.isEmpty()) continue
            sb.append("  <trk><name>${esc(p.node.longName)}</name><trkseg>\n")
            for (t in p.trail) {
                sb.append("    <trkpt lat=\"${t.lat}\" lon=\"${t.lon}\">${t.altM?.let { "<ele>$it</ele>" } ?: ""}<time>${iso.format(Date(t.time))}</time></trkpt>\n")
                pts++
            }
            sb.append("  </trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sb.toString().toByteArray()) } ?: return@withContext "Couldn't write the file"
        "Exported ${list.count { it.trail.isNotEmpty() }} tracks, $pts points"
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
