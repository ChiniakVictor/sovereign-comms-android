package com.victor.traillink

import com.victor.traillink.map.world.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WorldMapsTest {
    private val fixtures = listOf(
        longArrayOf(1, 0, 1, 2L), longArrayOf(1, 1, 0, 4L), longArrayOf(2, 3, 0, 20L), longArrayOf(2, 0, 3, 10L),
        longArrayOf(5, 2, 8, 571L), longArrayOf(5, 18, 26, 925L), longArrayOf(5, 19, 11, 1205L),
        longArrayOf(10, 211, 384, 558682L), longArrayOf(10, 421, 1016, 782032L),
        longArrayOf(12, 3502, 2573, 17646076L), longArrayOf(12, 2035, 1472, 8125018L),
        longArrayOf(15, 15997, 5364, 465158174L), longArrayOf(15, 18870, 4797, 1327658396L),
        longArrayOf(20, 884365, 82223, 1406691124067L), longArrayOf(26, 9229206, 12562241, 1659184307620114L),
    )

    @Test fun hilbertMatchesReference() {
        for (f in fixtures) {
            assertEquals(f[3], Pmtiles.zxyToTileId(f[0].toInt(), f[1].toInt(), f[2].toInt()))
            assertEquals(Triple(f[0].toInt(), f[1].toInt(), f[2].toInt()), Pmtiles.tileIdToZxy(f[3]))
        }
    }

    @Test fun directoryRoundTripAndLeaves() {
        val rnd = java.util.Random(42)
        val entries = ArrayList<Pmtiles.Entry>(); var off = 0L; var id = 0L
        for (i in 0 until 60_000) { val len = 50 + rnd.nextInt(90_000); id += 1 + rnd.nextInt(40); entries += Pmtiles.Entry(id, off, len, 1); off += len + (if (rnd.nextInt(5) == 0) 7 else 0) }
        val raw = Pmtiles.serializeDirectory(entries)
        assertEquals(entries, Pmtiles.parseDirectory(raw))
        val (root, leaves) = Pmtiles.buildDirectories(entries)
        assertTrue(root.size <= Pmtiles.ROOT_MAX); assertTrue(leaves.isNotEmpty())
        val rootE = Pmtiles.parseDirectory(Pmtiles.decompress(root, 2))
        // every entry can be found through root -> leaf
        for (probe in listOf(0, 1, 4095, 4096, 33_333, 59_999)) {
            val e = entries[probe]
            val leafPtr = Pmtiles.findEntry(rootE, e.tileId)!!
            assertEquals(0, leafPtr.runLength)
            val leaf = Pmtiles.parseDirectory(Pmtiles.decompress(leaves.copyOfRange(leafPtr.offset.toInt(), (leafPtr.offset + leafPtr.length).toInt()), 2))
            assertEquals(e, Pmtiles.findEntry(leaf, e.tileId))
        }
    }

    @Test fun headerRoundTrip() {
        val h = Pmtiles.Header(127, 10, 137, 5, 142, 0, 142, 999, 5, 4, 3, true, 2, 2, 1, 0, 14,
            -154.85, 56.85, -151.9, 58.7, 10, -153.4, 57.8)
        val back = Pmtiles.parseHeader(Pmtiles.writeHeader(h))
        assertEquals(h.copy(), back.copy(minLon = h.minLon, minLat = h.minLat, maxLon = h.maxLon, maxLat = h.maxLat, centerLon = h.centerLon, centerLat = h.centerLat))
        assertEquals(-154.85, back.minLon, 1e-6)
    }

    @Test fun tileCounts() {
        val b = BBox(-150.1, 61.08, -149.65, 61.26)
        assertEquals(Pmtiles.tileIds(b, 0, 12).size.toLong(), Pmtiles.tileCount(b, 0, 12))
        assertTrue(Pmtiles.tileIds(b, 0, 12).toList().zipWithNext().all { (a, c) -> a < c })
    }

    /** Live extract (opt-in: -Dworldmaps.live=/tmp/out.pmtiles). Compared against go-pmtiles on the build box. */
    @Test fun liveExtract() {
        val out = System.getProperty("worldmaps.live") ?: System.getenv("WORLDMAPS_LIVE")
        assumeTrue(out != null)
        val bbox = BBox.parse(System.getenv("WORLDMAPS_BBOX") ?: "-152.55,57.70,-152.30,57.85")
        val z = (System.getenv("WORLDMAPS_Z") ?: "13").toInt()
        val url = System.getenv("WORLDMAPS_URL") ?: WorldSources.PROTOMAPS_URL
        val client = okhttp3.OkHttpClient()
        val src = HttpRangeReader(url, client, "SovereignComms-test/2.3")
        val t0 = System.currentTimeMillis()
        val plan = Planner(src).plan(bbox, z, 5_000_000, name = "test")
        println("plan: ${plan.addressedTiles} tiles, ${plan.lengths.size} blobs, ${plan.totalBytes} bytes in ${System.currentTimeMillis() - t0} ms")
        val part = File("$out.part"); part.delete()
        // pause after ~1/3, then resume from the saved plan
        var calls = 0
        try { Downloader(plan, src, part).run({ calls > 2 }) { _, _ -> calls++ } } catch (e: java.io.InterruptedIOException) { println("paused at ${part.length()}") }
        val planFile = File("$out.plan"); plan.save(planFile)
        val p2 = Plan.load(planFile)
        Downloader(p2, HttpRangeReader(url, client, "SovereignComms-test/2.3", p2.etag), part).run({ false }) { _, _ -> }
        assertEquals(plan.totalBytes, part.length())
        part.renameTo(File(out))
        println("done in ${System.currentTimeMillis() - t0} ms")
    }

    // ---- 2.4: USGS Topo default with World fallback
    @org.junit.Test fun usgsIsDefaultBasemap() {
        org.junit.Assert.assertEquals("usgs", com.victor.traillink.data.Settings().basemap)
        org.junit.Assert.assertEquals("basemapUsgs_2_4", com.victor.traillink.data.Prefs.USGS_DEFAULT_KEY)
    }
    @org.junit.Test fun usgsCoverage() {
        val W = com.victor.traillink.map.world.WorldStyle
        org.junit.Assert.assertTrue(W.inUsgsCoverage(57.79, -152.41))   // Kodiak
        org.junit.Assert.assertTrue(W.inUsgsCoverage(61.22, -149.90))   // Anchorage
        org.junit.Assert.assertTrue(W.inUsgsCoverage(47.61, -122.33))   // Seattle
        org.junit.Assert.assertTrue(W.inUsgsCoverage(21.31, -157.86))   // Honolulu
        org.junit.Assert.assertTrue(W.inUsgsCoverage(52.9, 173.2))      // Attu
        org.junit.Assert.assertFalse(W.inUsgsCoverage(51.51, -0.13))    // London
        org.junit.Assert.assertFalse(W.inUsgsCoverage(-33.87, 151.21))  // Sydney
        org.junit.Assert.assertFalse(W.inUsgsCoverage(53.5, -113.5))    // Edmonton
    }
    @org.junit.Test fun usgsFallsBackToWorld() {
        val W = com.victor.traillink.map.world.WorldStyle
        // in the US, online -> USGS, no note
        W.effectiveBasemap("usgs", 57.79, -152.41, true, false, false, false).let { org.junit.Assert.assertEquals("usgs", it.effective); org.junit.Assert.assertNull(it.note) }
        // abroad -> World with note
        W.effectiveBasemap("usgs", 51.51, -0.13, true, false, false, false).let { org.junit.Assert.assertEquals("world", it.effective); org.junit.Assert.assertNotNull(it.note) }
        // offline, no USGS saved, World area saved -> World with note
        W.effectiveBasemap("usgs", 57.79, -152.41, false, false, false, true).let { org.junit.Assert.assertEquals("world", it.effective); org.junit.Assert.assertNotNull(it.note) }
        // "downloaded maps only" counts as offline
        org.junit.Assert.assertEquals("world", W.effectiveBasemap("usgs", 57.79, -152.41, true, true, false, true).effective)
        // offline but USGS saved here -> stay USGS
        org.junit.Assert.assertEquals("usgs", W.effectiveBasemap("usgs", 57.79, -152.41, false, false, true, true).effective)
        // offline, nothing saved -> stay USGS (cached tiles may still show)
        org.junit.Assert.assertEquals("usgs", W.effectiveBasemap("usgs", 57.79, -152.41, false, false, false, false).effective)
        // user picked World -> World, no note
        W.effectiveBasemap("world", 57.79, -152.41, true, false, false, false).let { org.junit.Assert.assertEquals("world", it.effective); org.junit.Assert.assertNull(it.note) }
    }
}
