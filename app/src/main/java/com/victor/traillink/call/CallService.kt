package com.victor.traillink.call

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.victor.traillink.R

/** Keeps the microphone, CPU and Wi-Fi awake while a call is up (also with the screen off). */
class CallService : Service() {
    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) }
                .onFailure { Log.w("SCCall", "call service start failed: $it") }
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, CallService::class.java)) } }
    }

    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val u = CallManager.ui.value
        val open = PendingIntent.getActivity(this, 5, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hang = PendingIntent.getBroadcast(this, 6, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CallManager.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle("📞 Call with ${u.peerName.ifBlank { "partner" }}")
            .setContentText("Tap to return to the call")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open).addAction(0, "Hang up", hang)
            .setUsesChronometer(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try { ServiceCompat.startForeground(this, CallManager.NOTIF_ONGOING, n, type) }
        catch (e: Exception) { Log.w("SCCall", "startForeground: $e") }
        if (cpu == null) cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SovereignComms:call").also { it.acquire() }
        if (wifi == null) runCatching {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SovereignComms:call")
                .also { it.acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cpu?.let { if (it.isHeld) it.release() }; cpu = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
        super.onDestroy()
    }
}
