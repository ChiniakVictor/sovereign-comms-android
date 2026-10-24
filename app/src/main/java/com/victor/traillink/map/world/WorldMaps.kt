package com.victor.traillink.map.world

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.victor.traillink.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A downloaded (or downloading) area of the worldwide map. Stored in filesDir/worldmaps/regions.json. */
data class WorldRegion(
    val id: String,
    val name: String,
    val bbox: BBox,
    val maxZoom: Int,
    val terrain: Boolean,
    val terrainMaxZoom: Int,
    val created: Long,
    val state: String = QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
) {
    val complete get() = state == DONE
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("bbox", bbox.toString()).put("maxzoom", maxZoom)
        .put("terrain", terrain).put("terrain_maxzoom", terrainMaxZoom).put("created", created).put("state", state)
        .put("bytes_done", bytesDone).put("bytes_total", bytesTotal).apply { error?.let { put("error", it) } }
    companion object {
        const val QUEUED = "queued"; const val DOWNLOADING = "downloading"; const val PAUSED = "paused"
        const val WAITING = "waiting"   // waiting for Wi-Fi
        const val DONE = "done"; const val ERROR = "error"
        fun fromJson(o: JSONObject) = WorldRegion(o.getString("id"), o.optString("name"), BBox.parse(o.getString("bbox")), o.getInt("maxzoom"),
            o.optBoolean("terrain"), o.optInt("terrain_maxzoom", 11), o.optLong("created"), o.optString("state", QUEUED),
            o.optLong("bytes_done"), o.optLong("bytes_total"), if (o.has("error")) o.getString("error") else null)
    }
}

/** Detail levels shown to the user. */
enum class Detail(val label: String, val maxZoom: Int, val terrainZoom: Int, val hint: String) {
    OVERVIEW("Overview", 10, 9, "Highways, towns, coastlines"),
    TOWNS("Towns", 12, 10, "All roads, rivers, place names"),
    STREETS("Streets", 14, 11, "Street names, trails, buildings"),
    MAX("Max", 15, 11, "Everything (largest)"),
}

object WorldMaps {
    const val WARN_BYTES = 500L * 1024 * 1024
    const val MAX_REGION_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_TILES = 3_000_000L
    const val WORLD_ASSET = "world/world-z4.pmtiles"

    @Volatile var sourceUrl = WorldSources.PROTOMAPS_URL
    @Volatile var terrainUrl = WorldSources.TERRAIN_URL

    private val _regions = MutableStateFlow<List<WorldRegion>>(emptyList())
    val regions: StateFlow<List<WorldRegion>> get() = _regions
    /** Validated internet (any network). */
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> get() = _online
    private val _unmetered = MutableStateFlow(false)
    val unmetered: StateFlow<Boolean> get() = _unmetered
    private var inited = false
    lateinit var appCtx: Context; private set

    val userAgent get() = WorldSources.userAgent(BuildConfig.VERSION_NAME)
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "worldmaps").apply { mkdirs() }
    fun baseFile(ctx: Context, id: String) = File(dir(ctx), "$id.pmtiles")
    fun terrainFile(ctx: Context, id: String) = File(dir(ctx), "$id-terrain.pmtiles")
    private fun index(ctx: Context) = File(dir(ctx), "regions.json")

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true; appCtx = ctx.applicationContext
        _regions.value = runCatching {
            val a = JSONArray(index(ctx).readText()); (0 until a.length()).map { WorldRegion.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).map { if (it.state == WorldRegion.DOWNLOADING) it.copy(state = WorldRegion.QUEUED) else it }
        ensureWorldFile(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun recheck() {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            _online.value = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _unmetered.value = _online.value && caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (canDownloadNow(ctx) && _regions.value.any { it.state == WorldRegion.WAITING || it.state == WorldRegion.QUEUED }) {
                _regions.update { l -> l.map { if (it.state == WorldRegion.WAITING) it.copy(state = WorldRegion.QUEUED, error = null) else it } }
                MapDownloadService.start(ctx)
            }
        }
        recheck()
        runCatching {
            cm?.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = recheck()
                override fun onLost(network: Network) = recheck()
                override fun onAvailable(network: Network) = recheck()
            })
        }
    }

    fun canDownloadNow(ctx: Context): Boolean =
        _online.value && (_unmetered.value || com.victor.traillink.data.Prefs.settings.value.mapsMobileData)

    /** The bundled world overview (z0-4) copied out of the APK so MapLibre can open it as pmtiles://file:// */
    fun worldFile(ctx: Context) = File(dir(ctx), "world-z4-${BuildConfig.VERSION_CODE}.pmtiles")
    private fun ensureWorldFile(ctx: Context) {
        val f = worldFile(ctx)
        if (f.exists() && f.length() > 0) return
        runCatching {
            dir(ctx).listFiles { x -> x.name.startsWith("world-z4") }?.forEach { it.delete() }
            val tmp = File(f.path + ".tmp")
            ctx.assets.open(WORLD_ASSET).use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
    }

    @Synchronized private fun save() {
        val a = JSONArray(); _regions.value.forEach { a.put(it.toJson()) }
        val f = index(appCtx); val tmp = File(f.path + ".tmp"); tmp.writeText(a.toString(1)); tmp.renameTo(f)
    }

    fun update(id: String, persist: Boolean = true, f: (WorldRegion) -> WorldRegion) {
        _regions.update { l -> l.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    /** Planner per source, kept so repeated size estimates reuse the cached directories. */
    private val planners = HashMap<String, Planner>()
    @Synchronized fun planner(url: String): Planner = planners.getOrPut(url) { Planner(HttpRangeReader(url, http, userAgent)) }

    data class Estimate(val baseBytes: Long, val terrainBytes: Long, val tiles: Long) { val total get() = baseBytes + terrainBytes }

    /** Exact download size (reads only the archive directories, a few hundred KB). Throws TooBigException / IOException. */
    fun estimate(b: BBox, d: Detail, terrain: Boolean): Estimate {
        val base = planner(sourceUrl).size(b, d.maxZoom, MAX_TILES)
        val t = if (terrain) planner(terrainUrl).size(b, d.terrainZoom, MAX_TILES).bytes else 0
        return Estimate(base.bytes, t, base.tiles)
    }

    /** Very rough size before the exact one is known (bytes/tile seen in Alaska + Washington test extracts). */
    fun roughEstimate(b: BBox, d: Detail, terrain: Boolean): Long {
        val tiles = Pmtiles.tileCount(b, 0, d.maxZoom)
        val t = if (terrain) Pmtiles.tileCount(b, 0, d.terrainZoom) * 60_000L else 0
        return tiles * 2_500L + t
    }

    fun add(ctx: Context, name: String, b: BBox, d: Detail, terrain: Boolean, bytesTotal: Long): WorldRegion {
        init(ctx)
        val r = WorldRegion("r${System.currentTimeMillis()}", name.ifBlank { "Area" }, b, d.maxZoom, terrain, d.terrainZoom,
            System.currentTimeMillis(), WorldRegion.QUEUED, 0, bytesTotal)
        _regions.update { it + r }; save()
        com.victor.traillink.service.LinkRepo.log("Maps: queued '${r.name}' ${b} z${d.maxZoom}${if (terrain) " +terrain" else ""} ~${bytesTotal / 1_000_000} MB")
        MapDownloadService.start(ctx)
        return r
    }

    fun pause(id: String) = update(id) { if (it.complete) it else it.copy(state = WorldRegion.PAUSED) }
    fun resume(ctx: Context, id: String) { update(id) { if (it.complete) it else it.copy(state = WorldRegion.QUEUED, error = null) }; MapDownloadService.start(ctx) }
    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }
    fun delete(ctx: Context, id: String) {
        _regions.update { l -> l.filterNot { it.id == id } }; save()
        dir(ctx).listFiles { f -> f.name.startsWith("$id.") || f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    fun diskBytes(ctx: Context, id: String) = dir(ctx).listFiles { f -> f.name.startsWith("$id.") || f.name.startsWith("$id-") }?.sumOf { it.length() } ?: 0L

    // ---- download (runs inside MapDownloadService) -------------------------------------------------

    /** Downloads [id] until done, paused or failed. [stop] is checked between requests. */
    fun runDownload(ctx: Context, id: String, stop: () -> Boolean) {
        val r0 = _regions.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(state = WorldRegion.DOWNLOADING, error = null) }
        val jobs = buildList {
            add(Triple(sourceUrl, r0.maxZoom, baseFile(ctx, id)))
            if (r0.terrain) add(Triple(terrainUrl, r0.terrainMaxZoom, terrainFile(ctx, id)))
        }
        val paused = { stop() || _regions.value.firstOrNull { it.id == id }?.state != WorldRegion.DOWNLOADING }
        try {
            // plan every part first so the total is exact
            val plans = jobs.map { (url, z, out) ->
                val planF = File(out.path.removeSuffix(".pmtiles") + ".plan")
                if (out.exists()) null else if (planF.exists()) runCatching { Plan.load(planF) }.getOrNull() ?: run { planF.delete(); null } else null
            }.toMutableList()
            for ((i, j) in jobs.withIndex()) {
                val (url, z, out) = j
                if (out.exists() || plans[i] != null) continue
                val p = Planner(HttpRangeReader(url, http, userAgent)).plan(r0.bbox, z, MAX_TILES, name = r0.name,
                    extraMeta = JSONObject().put("region_id", id).put("app", "Sovereign Comms ${BuildConfig.VERSION_NAME}"))
                p.save(File(out.path.removeSuffix(".pmtiles") + ".plan")); plans[i] = p
            }
            val total = jobs.indices.sumOf { i -> plans[i]?.totalBytes ?: jobs[i].third.length() }
            if (total > MAX_REGION_BYTES) throw java.io.IOException("Area is ${total / 1_000_000} MB; the limit is ${MAX_REGION_BYTES / 1_000_000} MB. Pick a smaller box or less detail.")
            val already = jobs.indices.sumOf { i -> if (plans[i] == null) jobs[i].third.length() else File(jobs[i].third.path.removeSuffix(".pmtiles") + ".part").length() }
            val free = dir(ctx).usableSpace
            if (total - already + 200_000_000L > free) throw java.io.IOException("Not enough free storage (${free / 1_000_000} MB free, need ${(total - already) / 1_000_000} MB)")
            update(id) { it.copy(bytesTotal = total) }
            var doneBefore = 0L
            for ((i, j) in jobs.withIndex()) {
                val out = j.third; val p = plans[i]
                if (p == null) { doneBefore += out.length(); continue }
                val base = out.path.removeSuffix(".pmtiles")
                val part = File("$base.part")
                var lastSave = 0L
                Downloader(p, HttpRangeReader(p.sourceUrl, http, userAgent, p.etag), part).run(paused) { done, _ ->
                    val now = System.currentTimeMillis()
                    val persist = now - lastSave > 5_000; if (persist) lastSave = now
                    update(id, persist) { it.copy(bytesDone = doneBefore + done) }
                }
                if (!part.renameTo(out)) throw java.io.IOException("Couldn't finish file")
                File("$base.plan").delete()
                doneBefore += out.length()
            }
            update(id) { it.copy(state = WorldRegion.DONE, bytesDone = doneBefore, bytesTotal = doneBefore, error = null) }
            com.victor.traillink.service.LinkRepo.log("Maps: '${r0.name}' downloaded (${doneBefore / 1_000_000} MB)")
        } catch (e: java.io.InterruptedIOException) {
            update(id) { if (it.state == WorldRegion.DOWNLOADING) it.copy(state = if (stop()) WorldRegion.QUEUED else WorldRegion.PAUSED) else it }
        } catch (e: SourceChangedException) {
            // the server copy changed mid-download: start over against the new version
            dir(ctx).listFiles { f -> f.name.startsWith("$id") && (f.name.endsWith(".part") || f.name.endsWith(".plan")) }?.forEach { it.delete() }
            update(id) { it.copy(state = WorldRegion.QUEUED, bytesDone = 0, error = "Map data was updated on the server; restarting") }
        } catch (e: TooBigException) {
            update(id) { it.copy(state = WorldRegion.ERROR, error = "Too big: ${e.tiles} tiles. Pick a smaller box or less detail.") }
        } catch (e: Exception) {
            val waiting = !canDownloadNow(ctx)
            update(id) { it.copy(state = if (waiting) WorldRegion.WAITING else WorldRegion.ERROR, error = if (waiting) "Waiting for Wi-Fi" else (e.message ?: e.toString())) }
            com.victor.traillink.service.LinkRepo.log("Maps: '${r0.name}' download stopped: ${e.message}")
        }
    }
}
