package com.victor.traillink.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** The phone's own position: Google fused location when available, else LocationManager GPS + network. */
class PhoneLocator(private val ctx: Context, private val intervalMs: Long, private val onFix: (Location) -> Unit) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var fusedCb: LocationCallback? = null
    private val lmListener = LocationListener { l -> deliver(l) }
    private var lmOn = false
    private var best: Location? = null
    var usingFused = false; private set
    val running get() = fusedCb != null || lmOn

    private fun deliver(l: Location) {
        val b = best
        // ignore a coarse network fix right after a good GPS fix
        if (b != null && l.provider == LocationManager.NETWORK_PROVIDER && b.provider == LocationManager.GPS_PROVIDER &&
            l.time - b.time < 30_000 && l.accuracy > b.accuracy) return
        best = l
        onFix(l)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission(ctx)) return false
        val fusedOk = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS }.getOrDefault(false)
        if (fusedOk) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(ctx)
                val cb = object : LocationCallback() {
                    override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { deliver(it) } }
                }
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                    .setMinUpdateIntervalMillis(1000).setWaitForAccurateLocation(false).build()
                client.requestLocationUpdates(req, cb, Looper.getMainLooper())
                client.lastLocation.addOnSuccessListener { l -> if (l != null && best == null) deliver(l) }
                fusedCb = cb; usingFused = true
                return true
            } catch (e: Exception) { fusedCb = null }
        }
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm?.allProviders?.contains(p) == true) {
                    lm.requestLocationUpdates(p, intervalMs, 0f, lmListener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (best == null || it.time > best!!.time) deliver(it) }
                }
            }
            lmOn = true; usingFused = false
        } catch (_: SecurityException) { return false }
        return true
    }

    fun stop() {
        fusedCb?.let { cb -> runCatching { LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(cb) } }
        fusedCb = null
        if (lmOn) runCatching { lm?.removeUpdates(lmListener) }
        lmOn = false
    }

    companion object {
        fun hasPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        /** System-wide Location switch (quick settings). */
        fun systemEnabled(ctx: Context): Boolean =
            ctx.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
    }
}
