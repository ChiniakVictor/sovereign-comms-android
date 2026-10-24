package com.victor.traillink

import com.victor.traillink.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrailsTest {
    private fun bundled() = KGeo.parse(GZIPInputStream(File("src/main/assets/kodiak/trails.geojson.gz").inputStream()).bufferedReader().readText())

    @Test fun bundledTrails() {
        val t = bundled()
        assertTrue(t.size in 60..120)
        assertTrue(t.all { it.k == "trail" && it.lines.isNotEmpty() })
        assertTrue(t.all { it.props.getString("cls") in TrailsInfo.CLASS_LABEL.keys })
        assertTrue(t.all { it.props.getString("src") == "Kodiak Island Borough" })
        assertEquals(t.size, t.map { it.props.getString("id") }.toSet().size)                 // ids unique (tap matching)
        assertEquals(t.size, t.map { it.props.getString("name") to it.props.getString("cls") }.toSet().size)   // merged by name+class
        assertEquals(265.0, TrailsInfo.miles(t), 5.0)
        // every vertex is on the archipelago
        assertTrue(t.all { f -> f.lines.flatten().all { KodiakArea.contains(it[0], it[1]) } })
    }

    @Test fun namesCleaned() {
        val names = bundled().map { it.props.getString("name") }.toSet()
        for (bad in listOf("Saltrey Cove Road", "Old Womens Mountain", "Zenter Creek Spur", "Zenter Creek Spur ", "Kalsin Bay to Summit Lake Trail", "None", "No Name", "null", ""))
            assertFalse("still has '$bad'", bad in names)
        for (good in listOf("Saltery Cove Road", "Old Woman Mountain", "Zentner Creek Spur", "Kalsin Bay to Summit Lake Trails", "Pillar Mountain Ridge Trail", "Termination Point Loop"))
            assertTrue("missing '$good'", good in names)
        assertTrue(names.all { it == it.trim() })
    }

    @Test fun summaryAndTitle() {
        val t = bundled()
        assertTrue(TrailsInfo.summary(t).matches(Regex("\\d+ trails, \\d+ mi")))
        assertEquals("no trails", TrailsInfo.summary(emptyList()))
        val f = t.first { it.props.getString("name") == "Saltery Cove Road" }
        assertEquals("Saltery Cove Road", f.title)
        assertEquals("Double track", TrailsInfo.classLabel(f.props.getString("cls")))
        assertEquals("Trail", TrailsInfo.classLabel("bogus"))
        // trails never count as easements in "Am I on an easement?"
        assertNull(KGeo.nearestEasement(57.79, -152.40, t))
    }

    @Test fun simplifiedButAccurate() {
        // a vertex-to-vertex gap can't exceed what the 1.5 m tolerance allows on a straight run; check that consecutive points are distinct
        val t = bundled()
        assertTrue(t.sumOf { f -> f.lines.sumOf { it.size } } < 30_000)
        assertTrue(t.all { f -> f.lines.all { l -> l.zipWithNext().all { (a, b) -> a[0] != b[0] || a[1] != b[1] } } })
    }

    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>Chiniak logging roads</name>
 <Style id="red"><LineStyle><color>ff0000ff</color><width>3</width></LineStyle></Style>
 <Style id="pinA"><IconStyle><color>ffff7f00</color></IconStyle></Style>
 <StyleMap id="sm"><Pair><key>normal</key><styleUrl>#red</styleUrl></Pair><Pair><key>highlight</key><styleUrl>#pinA</styleUrl></Pair></StyleMap>
 <Folder><name>Leisnoi side</name>
  <Placemark><name>Road A</name><description><![CDATA[Muddy<br>in spring]]></description><styleUrl>#sm</styleUrl>
   <LineString><coordinates>-152.20,57.60,0 -152.19,57.61,0 -152.18,57.615,0</coordinates></LineString></Placemark>
  <Placemark><name>Road B</name><Style><LineStyle><color>ff00ffff</color></LineStyle></Style>
   <MultiGeometry><LineString><coordinates>-152.1,57.6 -152.11,57.61</coordinates></LineString><LineString><coordinates>-152.12,57.6 -152.13,57.6</coordinates></LineString></MultiGeometry></Placemark>
 </Folder>
 <Placemark><name>Gate</name><styleUrl>#pinA</styleUrl><Point><coordinates>-152.25,57.62,0</coordinates></Point></Placemark>
 <Placemark><name>Kodiak Island borough property</name><Style><LineStyle><color>ffffffff</color></LineStyle></Style>
  <Polygon><outerBoundaryIs><LinearRing><coordinates>-152.3,57.6 -152.29,57.6 -152.29,57.61 -152.3,57.61 -152.3,57.6</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
</Document></kml>"""

    @Test fun kmlColour() {
        assertEquals("#FF0000", Kml.color("ff0000ff"))
        assertEquals("#007FFF", Kml.color("ffff7f00"))
        assertNull(Kml.color("red")); assertNull(Kml.color(null))
    }

    @Test fun kmlToGeoJson() {
        val fc = Kml.toGeoJson(kml.toByteArray(), "log", "test")
        val fs = KGeo.parse(fc.toString())
        val roads = fs.filter { it.k == "logroad" }; val pins = fs.filter { it.k == "logpin" }; val areas = fs.filter { it.k == "logarea" }
        assertEquals(3, roads.size); assertEquals(1, pins.size); assertEquals(1, areas.size)
        val a = roads.first { it.props.getString("name") == "Road A" }
        assertEquals("#FF0000", a.props.getString("color"))          // via StyleMap normal -> #red
        assertEquals(3.0, a.props.getDouble("width"), 0.0)
        assertEquals("Leisnoi side", a.props.getString("folder"))
        assertEquals("Muddy\nin spring", a.props.getString("desc"))
        assertEquals(3, a.lines[0].size); assertEquals(57.60, a.lines[0][0][0], 1e-9); assertEquals(-152.20, a.lines[0][0][1], 1e-9)
        assertTrue(roads.filter { it.props.getString("name") == "Road B" }.all { it.props.getString("color") == "#FFFF00" })   // inline style
        assertEquals("#007FFF", pins[0].props.getString("color")); assertEquals("Gate", pins[0].title)
        assertFalse(pins[0].props.has("folder"))
        assertEquals("#FFFFFF", areas[0].props.getString("color"))
        assertEquals(fs.size, fs.map { it.props.getString("id") }.toSet().size)
        assertTrue(fs.all { it.props.getString("src") == "test" })
    }

    @Test fun kmzAccepted() {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("files/readme.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry() }
        val fc = Kml.toGeoJson(bo.toByteArray())
        assertEquals(5, fc.getJSONArray("features").length())
        assertEquals("logroad", fc.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getString("k"))
    }

    @Test fun kmlEmptyAndHtml() {
        val fc = Kml.toGeoJson("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document/></kml>""".toByteArray())
        assertEquals(0, fc.getJSONArray("features").length())
        assertEquals("a & b", Kml.stripHtml("<b>a</b> &amp; b"))
        assertNull(Kml.stripHtml("<br/>"))
    }

    private val gEarth = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2"><Document>
 <gx:CascadingStyle kml:id="s1_normal"><Style><IconStyle><Icon><href>https://earth.google.com/earth/document/icon?color=1976d2&amp;id=2000&amp;scale=4</href></Icon></IconStyle>
  <LineStyle><color>ff2dc0fb</color><width>2.4</width></LineStyle></Style></gx:CascadingStyle>
 <gx:CascadingStyle kml:id="s2_normal"><Style><LineStyle><width>2</width></LineStyle></Style></gx:CascadingStyle>
 <StyleMap id="s1"><Pair><key>normal</key><styleUrl>#s1_normal</styleUrl></Pair></StyleMap>
 <StyleMap id="s2"><Pair><key>normal</key><styleUrl>#s2_normal</styleUrl></Pair></StyleMap>
 <Folder><name>Untitled folder</name>
  <Placemark><name>Untitled path</name><styleUrl>#s1</styleUrl><LineString><coordinates>-152.2,57.6,10 -152.21,57.61,12</coordinates></LineString></Placemark>
  <Placemark><name>Borough Property</name><styleUrl>#s2</styleUrl><LineString><coordinates>-152.3,57.6 -152.31,57.6</coordinates></LineString></Placemark>
 </Folder>
 <Placemark><name>Gate</name><styleUrl>#s1</styleUrl><Point><coordinates>-152.25,57.61,27.1</coordinates></Point></Placemark>
</Document></kml>"""

    @Test fun googleEarthStyles() {
        val fs = KGeo.parse(Kml.toGeoJson(gEarth.toByteArray(), "log", "Victor (kodiakking.com)", Regex("(?i)borough property")).toString())
        val road = fs.single { it.k == "logroad" }
        assertEquals("#FBC02D", road.props.getString("color"))       // ff2dc0fb via StyleMap -> CascadingStyle
        assertFalse(road.props.has("name")); assertFalse(road.props.has("folder"))   // "Untitled path/folder" dropped
        assertEquals("Logging road", road.title)
        val area = fs.single { it.k == "logarea" }
        assertEquals("Borough Property", area.title); assertFalse(area.props.has("color"))
        val gate = fs.single { it.k == "logpin" }
        assertEquals("#1976D2", gate.props.getString("color"))       // from the Earth icon URL
        assertEquals("Victor (kodiakking.com)", gate.props.getString("src"))
    }

    @Test fun bundledLoggingRoads() {
        val bytes = File("src/main/assets/kodiak/logging_roads.kml").readBytes()
        val fs = KGeo.parse(Kml.toGeoJson(bytes, "log", "Victor (kodiakking.com)", Regex("(?i)borough property")).toString())
        val roads = fs.filter { it.k == "logroad" }; val areas = fs.filter { it.k == "logarea" }; val pins = fs.filter { it.k == "logpin" }
        assertEquals(335, roads.size); assertEquals(14, areas.size); assertEquals(11, pins.size)
        assertTrue(roads.all { it.props.optString("color").matches(Regex("#[0-9A-F]{6}")) })
        assertTrue(roads.map { it.props.getString("color") }.toSet().size >= 6)
        assertEquals(6, pins.count { it.title == "Gate" })
        assertTrue(pins.any { it.title == "Possible connection" })
        assertTrue(pins.count { it.title.lowercase().startsWith("might not be pass") } == 3)
        assertTrue(roads.any { it.title == "Back side of kash" })
        assertTrue(fs.all { f -> (f.lines.flatten() + listOfNotNull(f.point)).all { KodiakArea.contains(it[0], it[1]) } })
    }
}
