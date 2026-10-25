package com.victor.traillink.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2.5.1 radio discovery rules (same as Windows 1.3.2):
 *  - Never connected a radio: nothing is scanned or connected when the app opens, and Bluetooth permission is not
 *    asked for. "Search for radios" scans Bluetooth (BLE + paired radios) only when tapped.
 *  - A radio connected before (its Bluetooth address is remembered on the first successful sync): when the app opens
 *    it looks for that radio only, for at most [LOOK_CAP_MS], then shows "Radio not found" and stops.
 *  - A link that was working and drops is retried a few times ([LINK_RETRIES] lookups), never endlessly.
 */
object RadioPolicy {
    const val LOOK_CAP_MS = 30_000L       // remembered radio at app open (and each reconnect after a drop)
    const val MANUAL_CAP_MS = 120_000L    // tapping a radio found by Search (first pairing: time to type the PIN)
    const val LINK_RETRIES = 5
    const val SEARCH_SCAN_MS = 15_000L

    private const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    private const val NOTIF = "android.permission.POST_NOTIFICATIONS"

    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    fun validAddress(a: String?): Boolean = a != null && MAC.matches(a)

    /** Look for the remembered radio when the app opens? */
    fun lookAtLaunch(remembered: String?, radioAllowed: Boolean, alreadyBusy: Boolean): Boolean =
        radioAllowed && validAddress(remembered) && !alreadyBusy

    /** Asked when the app opens. Bluetooth only if there is a remembered radio to reconnect to (connect, no scan). */
    fun launchPermissions(sdk: Int, remembered: String?, radioAllowed: Boolean): List<String> {
        val p = mutableListOf(FINE, COARSE)
        if (sdk >= 31 && radioAllowed && validAddress(remembered)) p += CONNECT
        if (sdk >= 33) p += NOTIF
        return p
    }

    /** Asked when "Search for radios" is tapped. Before Android 12 BLE scanning needs location instead. */
    fun searchPermissions(sdk: Int): List<String> = if (sdk >= 31) listOf(SCAN, CONNECT) else listOf(FINE)

    /** Reconnect after a working link dropped? (attempt = lookups already made for this drop) */
    fun retryAfterDrop(autoReconnect: Boolean, attempt: Int): Boolean = autoReconnect && attempt < LINK_RETRIES

    fun backoffMs(attempt: Int): Long = (3_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)
}

/**
 * Tries [attempt] until it succeeds or [capMs] have passed in total. Each try is cut off at the time that is left, so
 * a radio that never answers can't hold it past the cap. Returns true when an attempt succeeded.
 */
suspend fun lookForRadio(
    capMs: Long,
    now: () -> Long,
    pauseMs: Long = 2_000L,
    onTry: (Int) -> Unit = {},
    attempt: suspend () -> Unit,
): Boolean {
    val end = now() + capMs
    var n = 0
    while (true) {
        val left = end - now()
        if (left <= 0) return false
        onTry(++n)
        val ok = withTimeoutOrNull(left) {
            try { attempt(); true } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        }
        if (ok == true) return true
        val l2 = end - now()
        if (l2 <= 0) return false
        delay(minOf(pauseMs, l2))
    }
}
