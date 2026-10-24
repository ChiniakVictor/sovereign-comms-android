package com.victor.traillink

import com.victor.traillink.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class KodiakTest {
    @Test fun area() {
        assertTrue(KodiakArea.contains(57.80, -152.40))   // Kodiak city
        assertTrue(KodiakArea.contains(57.62, -152.17))   // Chiniak
        assertTrue(KodiakArea.contains(58.30, -152.60))   // Afognak
        assertFalse(KodiakArea.contains(61.2, -149.9))    // Anchorage
        assertFalse(KodiakArea.contains(58.68, -156.66))  // King Salmon (peninsula)
        assertTrue(KodiakArea.overlaps(57.5, -153.0, 58.0, -152.0))
        assertFalse(KodiakArea.overlaps(60.0, -150.5, 61.5, -149.0))
    }

    @Test fun classify() {
        assertEquals(EsmtKind.TRAIL25, Easements.classify("Existing Trail up to 25 feet wide").kind)
        assertEquals(25, Easements.classify("Proposed Trail up to 25 feet wide").widthFt)
        assertEquals(EsmtKind.TRAIL50, Easements.classify("Existing Trail up to 50 feet wide").kind)
        assertEquals(60, Easements.classify("Existing Road up to 60 feet wide").widthFt)
        assertEquals(100, Easements.classify("Proposed Road up to 100 feet wide").widthFt)
        assertEquals(EsmtKind.SITE, Easements.classify("One Acre Site Easement").kind)
        assertEquals(EsmtKind.OTHER, Easements.classify(null).kind)
    }

    @Test fun nearestLine() {
        val line = listOf(doubleArrayOf(57.6, -152.2), doubleArrayOf(57.6, -152.1))
        val (d, at) = KGeo.toLine(57.6001, -152.15, line)   // ~11 m north of the line
        assertEquals(11.1, d, 0.5); assertEquals(57.6, at[0], 1e-6); assertEquals(-152.15, at[1], 1e-6)
        val (d2, _) = KGeo.toLine(57.6, -152.25, line)       // beyond the west end
        assertEquals(Geo.distanceM(57.6, -152.25, 57.6, -152.2), d2, 3.0)
    }

    @Test fun normalizeAndNearest() {
        val raw = JSONObject("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-152.2,57.6],[-152.1,57.6]]},
            "properties":{"EIN":"23 C5","Easement_ID":"X1","Easement_Type":"Existing Trail up to 25 feet wide","Land_Owner":"Leisnoi, Inc.","Conveyance_Doc":"https://x/y.pdf","Conveyance_Doc_Type":"IC","Conveyance_Doc_No":"123","GIS_Miles":6.2}}""")
        val out = Easements.normalize(raw, false)
        assertEquals(1, out.size)
        val fc = JSONObject().put("type", "FeatureCollection").put("features", org.json.JSONArray().put(out[0]))
        val fs = KGeo.parse(fc.toString())
        assertEquals("EIN 23 C5", fs[0].props.getString("label"))
        val on = KGeo.nearestEasement(57.60002, -152.15, fs)!!         // ~2 m off centre, 25 ft wide -> inside
        assertTrue(on.on); assertTrue(on.edgeM!! < 0)
        val off = KGeo.nearestEasement(57.601, -152.15, fs)!!          // ~111 m
        assertFalse(off.on); assertEquals(111.3 - 3.81, off.edgeM!!, 1.0)
        val site = Easements.normalize(JSONObject("""{"type":"Feature","geometry":{"type":"Point","coordinates":[-152.0,57.7]},"properties":{"EIN":"9 D1","Easement_Type":"One Acre Site Easement"}}"""), true)
        assertEquals(2, site.size)
        assertEquals(35.9, site[0].getJSONObject("properties").getDouble("r_m"), 0.05)
        val two = Easements.normalize(JSONObject("""{"type":"Feature","geometry":{"type":"Point","coordinates":[-152.51,57.64]},"properties":{"EIN":"25a D1, D9","Easement_Type":"Site Easement (2-acres)","Conveyance_Doc_Type":"PA","Conveyance_Doc":"50860067"}}"""), true)
        assertEquals(2.0, two[0].getJSONObject("properties").getDouble("acres"), 0.0)
        assertEquals(50.8, two[0].getJSONObject("properties").getDouble("r_m"), 0.05)
        assertTrue(two[0].getJSONObject("properties").getString("doc").startsWith("https://sdms.ak.blm.gov"))
    }

    @Test fun bundledData() {
        fun load(n: String) = GZIPInputStream(File("src/main/assets/kodiak/$n").inputStream()).bufferedReader().readText()
        val e = KGeo.parse(load("easements.geojson.gz")); val r = KGeo.parse(load("state_row.geojson.gz"))
        assertTrue(e.size > 300)
        assertTrue(e.all { it.k == "esmt" })
        assertTrue(r.any { it.k == "row" && it.props.getString("route") == "Chiniak Highway" })
        assertTrue(r.filter { it.k == "row" }.all { it.props.getInt("width_ft") in 60..300 })
        // every bundled easement touches the archipelago
        assertTrue(e.all { f -> (f.lines.flatten() + f.polys.flatten() + listOfNotNull(f.point)).any { KodiakArea.contains(it[0], it[1]) } })
    }
}
