package com.victor.traillink.map.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md): header, directories, Hilbert tile ids.
 * Pure JVM (no Android) so it is unit-tested on the build machine and mirrors the Python version for Windows.
 */
object Pmtiles {
    const val HEADER_LEN = 127
    const val ROOT_MAX = 16_384 - HEADER_LEN
    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TYPE_MVT = 1; const val TYPE_PNG = 2; const val TYPE_JPEG = 3; const val TYPE_WEBP = 4

    data class Header(
        val rootOffset: Long, val rootLength: Long, val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long, val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean, val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
        val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
        val centerZoom: Int, val centerLon: Double, val centerLat: Double,
    )

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    fun parseHeader(b: ByteArray): Header {
        require(b.size >= HEADER_LEN && String(b, 0, 7, Charsets.US_ASCII) == "PMTiles") { "Not a PMTiles archive" }
        require(b[7].toInt() == 3) { "Unsupported PMTiles version ${b[7]}" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun l(i: Int) = bb.getLong(8 + i * 8)
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun e7(i: Int) = bb.getInt(i) / 10_000_000.0
        return Header(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10),
            u8(96) == 1, u8(97), u8(98), u8(99), u8(100), u8(101),
            e7(102), e7(106), e7(110), e7(114), u8(118), e7(119), e7(123))
    }

    fun writeHeader(h: Header): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PMTiles".toByteArray(Charsets.US_ASCII)); bb.put(3)
        listOf(h.rootOffset, h.rootLength, h.metadataOffset, h.metadataLength, h.leafOffset, h.leafLength,
            h.dataOffset, h.dataLength, h.addressedTiles, h.tileEntries, h.tileContents).forEach { bb.putLong(it) }
        bb.put(if (h.clustered) 1 else 0); bb.put(h.internalCompression.toByte()); bb.put(h.tileCompression.toByte())
        bb.put(h.tileType.toByte()); bb.put(h.minZoom.toByte()); bb.put(h.maxZoom.toByte())
        fun e7(d: Double) = bb.putInt(Math.round(d * 10_000_000.0).toInt())
        e7(h.minLon); e7(h.minLat); e7(h.maxLon); e7(h.maxLat)
        bb.put(h.centerZoom.toByte()); e7(h.centerLon); e7(h.centerLat)
        return bb.array()
    }

    // ---- varints / compression -------------------------------------------------------------
    private class Reader(val b: ByteArray) {
        var p = 0
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7; require(s < 64) { "varint too long" }
            }
        }
    }
    private fun ByteArrayOutputStream.varint(v0: Long) {
        var v = v0
        while (v >= 0x80) { write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        write(v.toInt())
    }

    fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE, 0 -> b
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> throw IllegalArgumentException("Unsupported compression $compression")
    }
    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun parseDirectory(raw: ByteArray): List<Entry> {
        val r = Reader(raw)
        val n = r.varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += r.varint(); ids[i] = last }
        val runs = IntArray(n) { r.varint().toInt() }
        val lens = IntArray(n) { r.varint().toInt() }
        val offs = LongArray(n)
        for (i in 0 until n) {
            val v = r.varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    fun serializeDirectory(entries: List<Entry>): ByteArray {
        val o = ByteArrayOutputStream()
        o.varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { o.varint(e.tileId - last); last = e.tileId }
        for (e in entries) o.varint(e.runLength.toLong())
        for (e in entries) o.varint(e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) o.varint(0) else o.varint(e.offset + 1)
        }
        return o.toByteArray()
    }

    /** Last entry with tileId <= id, or null. */
    fun findEntry(entries: List<Entry>, id: Long): Entry? {
        var lo = 0; var hi = entries.size - 1; var best = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (entries[m].tileId <= id) { best = m; lo = m + 1 } else hi = m - 1
        }
        if (best < 0) return null
        val e = entries[best]
        return if (e.runLength == 0) e else if (id < e.tileId + e.runLength) e else null
    }

    /**
     * Root + leaf directories (gzip) for sorted [entries]. Returns (root, leaves). Leaf size grows until the root fits
     * in the first 16 KiB (same approach as go-pmtiles).
     */
    fun buildDirectories(entries: List<Entry>): Pair<ByteArray, ByteArray> {
        val root = gzip(serializeDirectory(entries))
        if (root.size <= ROOT_MAX) return root to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream(); val rootEntries = ArrayList<Entry>()
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, minOf(i + leafSize, entries.size))
                val z = gzip(serializeDirectory(chunk))
                rootEntries += Entry(chunk[0].tileId, leaves.size().toLong(), z.size, 0)
                leaves.write(z); i += leafSize
            }
            val r = gzip(serializeDirectory(rootEntries))
            if (r.size <= ROOT_MAX) return r to leaves.toByteArray()
            leafSize = (leafSize * 1.2).toInt()
        }
    }

    // ---- tile ids ----------------------------------------------------------------------------
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom $z" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y out of range" }
        var acc = ((1L shl (2 * z)) - 1) / 3
        var tx = x.toLong(); var ty = y.toLong()
        var s = n / 2
        var d = 0L
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }

    fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
        var acc = 0L; var z = 0
        while (true) {
            val num = 1L shl (2 * z)
            if (acc + num > id) break
            acc += num; z++
        }
        val n = 1L shl z
        var t = id - acc
        var x = 0L; var y = 0L
        var s = 1L
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) { x = s - 1 - x; y = s - 1 - y }
                val tmp = x; x = y; y = tmp
            }
            x += s * rx; y += s * ry
            t /= 4; s *= 2
        }
        return Triple(z, x.toInt(), y.toInt())
    }

    // ---- web mercator tile ranges ------------------------------------------------------------
    fun lonToX(lon: Double, z: Int): Int = (((lon + 180.0) / 360.0) * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun latToY(lat: Double, z: Int): Int {
        val l = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return ((1.0 - Math.log(Math.tan(l) + 1.0 / Math.cos(l)) / Math.PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    fun tileCount(b: BBox, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
        (lonToX(b.east, z) - lonToX(b.west, z) + 1).toLong() * (latToY(b.south, z) - latToY(b.north, z) + 1)
    }
    /** All tile ids covering [b] for zooms minZ..maxZ, sorted. */
    fun tileIds(b: BBox, minZ: Int, maxZ: Int): LongArray {
        val n = tileCount(b, minZ, maxZ); require(n <= Int.MAX_VALUE / 2) { "too many tiles" }
        val out = LongArray(n.toInt()); var i = 0
        for (z in minZ..maxZ) for (x in lonToX(b.west, z)..lonToX(b.east, z)) for (y in latToY(b.north, z)..latToY(b.south, z)) out[i++] = zxyToTileId(z, x, y)
        out.sort(); return out
    }
}

data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    init { require(west < east && south < north) { "bad bbox" } }
    override fun toString() = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.US, west, south, east, north)
    companion object { fun parse(s: String) = s.split(',').map { it.trim().toDouble() }.let { BBox(it[0], it[1], it[2], it[3]) } }
}
