package com.victor.traillink.core

/**
 * "Possible range" circle: where a partner could be now, given their last fix.
 *  radius = accuracy + speed × time since fix, frozen after [maxAgeMs].
 *  speed = configured pace, or the partner's recent reported/derived speed if that is higher (and fresh).
 */
object Range {
    const val DEFAULT_ACC_M = 15.0          // LoRa fixes carry no accuracy; typical GPS
    const val MAX_PLAUSIBLE_MPS = 70.0      // ignore junk speeds (> ~155 mph)
    const val SPEED_FRESH_MS = 10 * 60_000L

    data class Preset(val label: String, val mps: Double)
    val PRESETS = listOf(Preset("Hiking 2 mph", 0.894), Preset("Walking 3 mph", 1.34), Preset("Running 6 mph", 2.68), Preset("Vehicle 35 mph", 15.65))

    fun speedMps(paceMps: Double, reportedMps: Double?, reportedAgeMs: Long?): Double {
        val r = reportedMps?.takeIf { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_MPS && (reportedAgeMs ?: Long.MAX_VALUE) <= SPEED_FRESH_MS }
        return maxOf(paceMps.coerceAtLeast(0.0), r ?: 0.0)
    }

    fun radiusM(accM: Double?, ageMs: Long, speedMps: Double, maxAgeMs: Long): Double {
        val acc = (accM?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_ACC_M).coerceAtMost(50_000.0)
        val t = ageMs.coerceIn(0, maxAgeMs.coerceAtLeast(0)) / 1000.0
        return acc + speedMps * t
    }

    fun capped(ageMs: Long, maxAgeMs: Long) = ageMs >= maxAgeMs

    /** Fill alpha fades from 0.22 (fresh) to 0.05 (at max age); outline from 0.9 to 0.35. */
    fun fillOpacity(ageMs: Long, maxAgeMs: Long): Float = (0.22 - 0.17 * frac(ageMs, maxAgeMs)).toFloat()
    fun lineOpacity(ageMs: Long, maxAgeMs: Long): Float = (0.9 - 0.55 * frac(ageMs, maxAgeMs)).toFloat()
    private fun frac(a: Long, m: Long) = if (m <= 0) 1.0 else (a.toDouble() / m).coerceIn(0.0, 1.0)

    /** Speed from the last two trail points (m/s), or null. Points: (timeMs, lat, lon), newest last. */
    fun derivedSpeed(prev: Triple<Long, Double, Double>?, last: Triple<Long, Double, Double>?): Double? {
        if (prev == null || last == null) return null
        val dt = (last.first - prev.first) / 1000.0
        if (dt < 5 || dt > 600) return null
        return Geo.distanceM(prev.second, prev.third, last.second, last.third) / dt
    }

    fun label(radiusM: Double, ageMs: Long, maxAgeMs: Long, metric: Boolean): String {
        val cap = if (!capped(ageMs, maxAgeMs)) "" else if (maxAgeMs >= 3_600_000L) " · last seen ${maxAgeMs / 3_600_000L}h+" else " · last seen ${maxAgeMs / 60_000L}m+"
        return "could be within ${Geo.formatDistance(radiusM, metric)}$cap"
    }
}
