package com.victor.traillink

import com.victor.traillink.core.RadioPolicy
import com.victor.traillink.core.lookForRadio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadioFinderTest {
    private val addr = "C0:FF:EE:12:34:56"
    private fun now() = System.nanoTime() / 1_000_000

    @Test fun firstRunNeverLooksAndNeverAsksForBluetooth() {
        assertFalse(RadioPolicy.lookAtLaunch(null, radioAllowed = true, alreadyBusy = false))
        assertFalse(RadioPolicy.lookAtLaunch("", true, false))
        val p = RadioPolicy.launchPermissions(34, null, true)
        assertFalse(p.any { it.contains("BLUETOOTH") })
        assertTrue(p.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun rememberedRadioIsLookedForAtLaunchWithConnectPermissionOnly() {
        assertTrue(RadioPolicy.lookAtLaunch(addr, true, false))
        assertFalse(RadioPolicy.lookAtLaunch(addr, radioAllowed = false, alreadyBusy = false))   // Internet-only mode
        assertFalse(RadioPolicy.lookAtLaunch(addr, true, alreadyBusy = true))                    // already connecting
        val p = RadioPolicy.launchPermissions(34, addr, true)
        assertTrue(p.contains("android.permission.BLUETOOTH_CONNECT"))
        assertFalse(p.contains("android.permission.BLUETOOTH_SCAN"))
        assertFalse(RadioPolicy.launchPermissions(30, addr, true).any { it.contains("BLUETOOTH") })   // < Android 12: install-time
    }

    @Test fun searchAsksForBluetoothScanAndConnect() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), RadioPolicy.searchPermissions(31))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), RadioPolicy.searchPermissions(29))
    }

    @Test fun addressValidation() {
        assertTrue(RadioPolicy.validAddress(addr)); assertTrue(RadioPolicy.validAddress("c0:ff:ee:12:34:56"))
        assertFalse(RadioPolicy.validAddress("Meshtastic_ab12")); assertFalse(RadioPolicy.validAddress("COM7"))
    }

    @Test fun capIs30Seconds() = assertEquals(30_000L, RadioPolicy.LOOK_CAP_MS)

    @Test fun foundOnSecondTry() = runBlocking {
        var tries = 0
        val ok = lookForRadio(2_000, ::now, pauseMs = 50) { tries++; if (tries < 2) error("not in range") }
        assertTrue(ok); assertEquals(2, tries)
    }

    @Test fun radioThatNeverAnswersStopsAtTheCap() = runBlocking {
        var tries = 0
        val t0 = now()
        val ok = lookForRadio(600, ::now, pauseMs = 50) { tries++; CompletableDeferred<Unit>().await() }   // hangs forever
        val took = now() - t0
        assertFalse(ok); assertEquals(1, tries)
        assertTrue("took $took ms", took in 550..900)
    }

    @Test fun failingRadioRetriesOnlyUntilTheCap() = runBlocking {
        var tries = 0
        val t0 = now()
        val ok = lookForRadio(500, ::now, pauseMs = 100) { tries++; delay(20); error("gatt 133") }
        assertFalse(ok); assertTrue(now() - t0 in 450..800); assertTrue(tries in 3..6)
        val after = tries; delay(300); assertEquals(after, tries)   // nothing keeps running afterwards
    }

    @Test fun droppedLinkRetriesAFewTimesThenStops() {
        assertTrue(RadioPolicy.retryAfterDrop(true, 0))
        assertTrue(RadioPolicy.retryAfterDrop(true, RadioPolicy.LINK_RETRIES - 1))
        assertFalse(RadioPolicy.retryAfterDrop(true, RadioPolicy.LINK_RETRIES))
        assertFalse(RadioPolicy.retryAfterDrop(false, 0))
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L), (0..4).map { RadioPolicy.backoffMs(it) })
    }
}
