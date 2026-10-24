package com.victor.traillink

import com.victor.traillink.ui.UpdateCheck
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PoiTest {
    /** 2.3: the Places layers were removed from Sovereign Comms (they live in Kodiak Maps). */
    @Test fun placesLayersRemoved() {
        assertFalse(File("src/main/assets/kodiak/poi.geojson").exists())
        assertFalse(File("src/main/java/com/victor/traillink/ui/PoiMap.kt").exists())
        assertEquals(listOf("food", "travel", "mil", "schools", "community"), com.victor.traillink.data.PoiPrefs.REMOVED_KEYS)
    }

    @Test fun updateLinkOnKodiakking() {
        val html = """<a href="https://kodiakking.com/wp-content/uploads/2026/10/sovereign-comms-1.8.zip">x</a> <a href="https://kodiakking.com/wp-content/uploads/2026/11/sovereign-comms-1.10.zip">y</a>"""
        assertEquals("1.10", UpdateCheck.latestFrom(html)!!.first)
        assertTrue(UpdateCheck.compare("1.10", "1.9") > 0)
    }

    @org.junit.Test fun kodiakLayersAllOnByDefault() {
        val d = com.victor.traillink.data.Settings()
        org.junit.Assert.assertTrue(d.layerKodiak && d.layerEasements && d.layerRow && d.layerTrails && d.layerLogRoads && d.easementCheck)
        org.junit.Assert.assertEquals("kodiakLayersOn_2_3", com.victor.traillink.data.Prefs.KODIAK_LAYERS_RESET_KEY)
    }
}
