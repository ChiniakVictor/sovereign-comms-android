package com.victor.traillink.map.world

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Byte-range access to a (remote) PMTiles archive. */
interface RangeReader {
    val id: String
    fun read(offset: Long, length: Long): ByteArray
}

class SourceChangedException(msg: String) : IOException(msg)

/** HTTP range reads with an honest User-Agent; remembers the ETag and refuses to mix two versions of the archive. */
class HttpRangeReader(val url: String, private val client: OkHttpClient, private val userAgent: String, var etag: String? = null) : RangeReader {
    override val id get() = url
    override fun read(offset: Long, length: Long): ByteArray {
        require(length > 0)
        val req = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Range", "bytes=$offset-${offset + length - 1}").build()
        var lastErr: IOException? = null
        for (attempt in 0 until 4) {
            try {
                client.newCall(req).execute().use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code} from ${r.request.url.host}")
                    val tag = r.header("ETag")
                    if (tag != null) {
                        val known = etag
                        if (known == null) etag = tag else if (known != tag) throw SourceChangedException("The map source was updated on the server")
                    }
                    val b = r.body!!.bytes()
                    if (b.size.toLong() != length) throw IOException("Short read ${b.size}/$length")
                    return b
                }
            } catch (e: SourceChangedException) { throw e } catch (e: IOException) {
                lastErr = e; Thread.sleep(500L * (1 shl attempt))
            }
        }
        throw lastErr ?: IOException("read failed")
    }
}

/** A planned extract: everything needed to write the region file except the tile bytes. */
class Plan(
    val sourceUrl: String, val etag: String?, val bbox: BBox, val minZoom: Int, val maxZoom: Int,
    /** header + root dir + metadata + leaf dirs, written at the start of the region file */
    val prefix: ByteArray,
    /** unique tile blobs in the source, sorted by source offset; written to the region file in this order */
    val srcOffsets: LongArray, val lengths: IntArray,
    val addressedTiles: Long,
) {
    val dataBytes: Long = lengths.fold(0L) { a, l -> a + l }
    val totalBytes: Long get() = prefix.size + dataBytes

    fun save(f: File) = DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
        o.writeInt(1); o.writeUTF(sourceUrl); o.writeUTF(etag ?: ""); o.writeUTF(bbox.toString())
        o.writeInt(minZoom); o.writeInt(maxZoom); o.writeLong(addressedTiles)
        o.writeInt(prefix.size); o.write(prefix)
        o.writeInt(srcOffsets.size); for (i in srcOffsets.indices) { o.writeLong(srcOffsets[i]); o.writeInt(lengths[i]) }
    }
    companion object {
        fun load(f: File): Plan = DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
            require(i.readInt() == 1) { "plan version" }
            val url = i.readUTF(); val etag = i.readUTF().ifEmpty { null }; val bbox = BBox.parse(i.readUTF())
            val minZ = i.readInt(); val maxZ = i.readInt(); val addr = i.readLong()
            val prefix = ByteArray(i.readInt()).also { i.readFully(it) }
            val n = i.readInt(); val offs = LongArray(n); val lens = IntArray(n)
            for (k in 0 until n) { offs[k] = i.readLong(); lens[k] = i.readInt() }
            Plan(url, etag, bbox, minZ, maxZ, prefix, offs, lens, addr)
        }
    }
}

/**
 * Reads the directory of a big remote PMTiles archive (e.g. the Protomaps planet) and plans an extract of a bounding
 * box. Leaf directories are cached, so re-planning while the user adjusts the box is cheap.
 */
class Planner(private val src: RangeReader, private val threads: Int = 4) {
    private var header: Pmtiles.Header? = null
    private var root: List<Pmtiles.Entry>? = null
    private var metadata: JSONObject? = null
    private val leafCache = object : LinkedHashMap<Long, List<Pmtiles.Entry>>(64, 0.75f, true) {
        var weight = 0L
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Pmtiles.Entry>>?): Boolean {
            if (weight > 3_000_000 && eldest != null) { weight -= eldest.value.size; return true }
            return false
        }
    }

    @Synchronized fun header(): Pmtiles.Header {
        header?.let { return it }
        val first = src.read(0, 16_384)
        val h = Pmtiles.parseHeader(first)
        val rootRaw = first.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
        root = Pmtiles.parseDirectory(Pmtiles.decompress(rootRaw, h.internalCompression))
        header = h
        return h
    }

    @Synchronized fun metadata(): JSONObject {
        metadata?.let { return it }
        val h = header()
        val m = if (h.metadataLength == 0L) JSONObject() else JSONObject(String(Pmtiles.decompress(src.read(h.metadataOffset, h.metadataLength), h.internalCompression)))
        metadata = m; return m
    }

    private fun loadLeaves(h: Pmtiles.Header, wanted: List<Pmtiles.Entry>): Map<Long, List<Pmtiles.Entry>> {
        val out = HashMap<Long, List<Pmtiles.Entry>>()
        val missing = ArrayList<Pmtiles.Entry>()
        synchronized(leafCache) { for (e in wanted) leafCache[e.offset]?.let { out[e.offset] = it } ?: missing.add(e) }
        if (missing.isEmpty()) return out
        missing.sortBy { it.offset }
        // coalesce neighbouring leaves (they are stored back to back) into requests of up to 4 MB
        val groups = ArrayList<List<Pmtiles.Entry>>(); var cur = ArrayList<Pmtiles.Entry>()
        for (e in missing) {
            val last = cur.lastOrNull()
            if (last != null && (e.offset - (last.offset + last.length) > 65_536 || e.offset + e.length - cur[0].offset > 4_000_000)) { groups += cur; cur = ArrayList() }
            cur += e
        }
        if (cur.isNotEmpty()) groups += cur
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        try {
            val futures = groups.map { g -> pool.submit(Callable {
                val start = g[0].offset; val end = g.last().offset + g.last().length
                val bytes = src.read(h.leafOffset + start, end - start)
                g.associate { e -> e.offset to Pmtiles.parseDirectory(Pmtiles.decompress(bytes.copyOfRange((e.offset - start).toInt(), (e.offset - start + e.length).toInt()), h.internalCompression)) }
            }) }
            for (f in futures) {
                val m = f.get()
                synchronized(leafCache) { m.forEach { (k, v) -> leafCache[k] = v; leafCache.weight += v.size } }
                out.putAll(m)
            }
        } finally { pool.shutdownNow() }
        return out
    }

    /** Resolves tile ids → (tileId, srcOffset, length) through root and (possibly nested) leaf directories. */
    private fun resolve(h: Pmtiles.Header, dir: List<Pmtiles.Entry>, ids: LongArray, outIds: LongArrayList, outOff: LongArrayList, outLen: IntArrayList, depth: Int = 0) {
        require(depth < 4) { "directory too deep" }
        val leafGroups = LinkedHashMap<Pmtiles.Entry, LongArrayList>()
        for (id in ids) {
            val e = Pmtiles.findEntry(dir, id) ?: continue
            if (e.runLength == 0) leafGroups.getOrPut(e) { LongArrayList() }.add(id)
            else { outIds.add(id); outOff.add(h.dataOffset + e.offset); outLen.add(e.length) }
        }
        if (leafGroups.isEmpty()) return
        val leaves = loadLeaves(h, leafGroups.keys.toList())
        for ((e, g) in leafGroups) resolve(h, leaves.getValue(e.offset), g.toArray(), outIds, outOff, outLen, depth + 1)
    }

    data class SizeInfo(val tiles: Long, val bytes: Long)

    /** Exact size of the extract (only directories are read). */
    fun size(b: BBox, maxZoom: Int, maxTiles: Long): SizeInfo {
        val p = plan(b, maxZoom, maxTiles, prefixOnly = false); return SizeInfo(p.addressedTiles, p.totalBytes)
    }

    fun plan(b: BBox, maxZoom0: Int, maxTiles: Long, prefixOnly: Boolean = false, name: String = "", extraMeta: JSONObject? = null): Plan {
        val h = header()
        val maxZoom = maxZoom0.coerceIn(h.minZoom, h.maxZoom)
        val n = Pmtiles.tileCount(b, 0, maxZoom)
        if (n > maxTiles) throw TooBigException(n, maxTiles)
        val ids = Pmtiles.tileIds(b, 0, maxZoom)
        val tIds = LongArrayList(); val tOff = LongArrayList(); val tLen = IntArrayList()
        resolve(h, root!!, ids, tIds, tOff, tLen)
        // unique blobs in source order -> new offsets
        val order = (0 until tIds.size).sortedWith(compareBy({ tOff[it] }, { tIds[it] }))
        val uOff = LongArrayList(); val uLen = IntArrayList()
        val newOffOf = HashMap<Long, Long>(order.size * 2)
        var pos = 0L
        for (k in order) {
            val so = tOff[k]
            if (!newOffOf.containsKey(so)) { newOffOf[so] = pos; uOff.add(so); uLen.add(tLen[k]); pos += tLen[k] }
        }
        // entries sorted by tile id with run-length merging (tIds is already sorted)
        val entries = ArrayList<Pmtiles.Entry>()
        for (k in 0 until tIds.size) {
            val no = newOffOf.getValue(tOff[k]); val last = entries.lastOrNull()
            if (last != null && last.offset == no && last.tileId + last.runLength == tIds[k]) entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            else entries += Pmtiles.Entry(tIds[k], no, tLen[k], 1)
        }
        val (rootDir, leaves) = Pmtiles.buildDirectories(entries)
        val meta = JSONObject(metadata().toString())
        meta.put("sovereign_comms", (extraMeta ?: JSONObject()).put("bbox", b.toString()).put("maxzoom", maxZoom).put("source", src.id).put("name", name))
        val metaBytes = Pmtiles.gzip(meta.toString().toByteArray())
        val rootOff = Pmtiles.HEADER_LEN.toLong(); val metaOff = rootOff + rootDir.size; val leafOff = metaOff + metaBytes.size
        val dataOff = leafOff + leaves.size
        val out = Pmtiles.Header(rootOff, rootDir.size.toLong(), metaOff, metaBytes.size.toLong(), leafOff, leaves.size.toLong(), dataOff, pos,
            tIds.size.toLong(), entries.size.toLong(), uOff.size.toLong(), h.clustered, Pmtiles.COMPRESSION_GZIP, h.tileCompression, h.tileType,
            0, maxZoom, b.west, b.south, b.east, b.north, maxZoom.coerceAtMost(12), (b.west + b.east) / 2, (b.south + b.north) / 2)
        val prefix = ByteArrayOutputStream().apply { write(Pmtiles.writeHeader(out)); write(rootDir); write(metaBytes); write(leaves) }.toByteArray()
        return Plan(src.id, (src as? HttpRangeReader)?.etag, b, 0, maxZoom, prefix, uOff.toArray(), uLen.toArray(), tIds.size.toLong())
    }
}

class TooBigException(val tiles: Long, val limit: Long) : IOException("Area has $tiles tiles at this detail (limit $limit)")

/**
 * Downloads the tile bytes of a [Plan] into [part] (prefix first, then tile blobs in source order). Resumable: the
 * file length says how far we got; on resume we cut back to the last whole blob and continue from there.
 */
class Downloader(private val plan: Plan, private val src: RangeReader, private val part: File, private val parallel: Int = 3) {
    fun run(cancelled: () -> Boolean, progress: (done: Long, total: Long) -> Unit) {
        val prefixLen = plan.prefix.size.toLong()
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() < prefixLen) { raf.setLength(0); raf.write(plan.prefix) }
            // find the first blob not fully written
            var have = raf.length() - prefixLen; var start = 0; var acc = 0L
            while (start < plan.lengths.size && acc + plan.lengths[start] <= have) { acc += plan.lengths[start]; start++ }
            raf.setLength(prefixLen + acc); raf.seek(prefixLen + acc)
            progress(prefixLen + acc, plan.totalBytes)
            // group the remaining blobs into range requests (small gaps are over-fetched, at most 8 MB per request)
            val reqs = ArrayList<IntRange>()
            var i = start
            while (i < plan.lengths.size) {
                var j = i; val s = plan.srcOffsets[i]
                while (j + 1 < plan.lengths.size) {
                    val endJ = plan.srcOffsets[j] + plan.lengths[j]
                    val next = plan.srcOffsets[j + 1]
                    if (next - endJ > 131_072 || next + plan.lengths[j + 1] - s > 8_000_000) break
                    j++
                }
                reqs += i..j; i = j + 1
            }
            val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, 4))
            try {
                val queue = ArrayDeque<Pair<IntRange, Future<ByteArray>>>()
                var next = 0
                fun submit() { while (queue.size < parallel && next < reqs.size) { val r = reqs[next++]; queue.add(r to pool.submit(Callable { fetch(r) })) } }
                submit()
                var done = prefixLen + acc
                while (queue.isNotEmpty()) {
                    if (cancelled()) throw InterruptedIOException("paused")
                    val (r, f) = queue.removeFirst()
                    val bytes = try { f.get(120, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException) ?: IOException(e.cause) }
                    val base = plan.srcOffsets[r.first]
                    for (k in r) {
                        val rel = (plan.srcOffsets[k] - base).toInt()
                        raf.write(bytes, rel, plan.lengths[k]); done += plan.lengths[k]
                    }
                    progress(done, plan.totalBytes)
                    submit()
                }
            } finally { pool.shutdownNow() }
            check(raf.length() == plan.totalBytes) { "size mismatch ${raf.length()} vs ${plan.totalBytes}" }
        }
    }

    private fun fetch(r: IntRange): ByteArray {
        val s = plan.srcOffsets[r.first]; val e = (r.first..r.last).maxOf { plan.srcOffsets[it] + plan.lengths[it] }
        return src.read(s, e - s)
    }
}

/** Minimal growable primitive lists (avoid boxing millions of tile ids). */
class LongArrayList(cap: Int = 1024) {
    private var a = LongArray(cap); var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray(): LongArray = a.copyOf(size)
}
class IntArrayList(cap: Int = 1024) {
    private var a = IntArray(cap); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}
