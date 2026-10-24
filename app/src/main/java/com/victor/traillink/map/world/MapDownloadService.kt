package com.victor.traillink.map.world

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.victor.traillink.R

/** Foreground service that downloads queued world map areas one at a time (keeps going with the screen off). */
class MapDownloadService : Service() {
    companion object {
        const val CH = "maps"; const val NOTIF = 4711
        const val ACTION_PAUSE_ALL = "pause_all"
        @Volatile private var running = false
        fun start(ctx: Context) {
            if (!WorldMaps.canDownloadNow(ctx)) {
                WorldMaps.regions.value.filter { it.state == WorldRegion.QUEUED }.forEach { r -> WorldMaps.update(r.id) { it.copy(state = WorldRegion.WAITING, error = "Waiting for Wi-Fi") } }
                return
            }
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MapDownloadService::class.java)) }
        }
    }
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Map downloads", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try { ServiceCompat.startForeground(this, NOTIF, notif("Preparing map download…", 0, 0), type) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PAUSE_ALL) {
            WorldMaps.regions.value.filter { it.state == WorldRegion.DOWNLOADING || it.state == WorldRegion.QUEUED }.forEach { WorldMaps.pause(it.id) }
        }
        synchronized(this) {
            if (worker?.isAlive != true) {
                worker = Thread({ loop() }, "map-download").also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun loop() {
        WorldMaps.init(this)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SovereignComms:maps").also { it.acquire(6 * 3600_000L) }
        val nm = getSystemService(NotificationManager::class.java)
        try {
            while (!stopping) {
                val r = WorldMaps.regions.value.firstOrNull { it.state == WorldRegion.QUEUED } ?: break
                if (!WorldMaps.canDownloadNow(this)) { WorldMaps.update(r.id) { it.copy(state = WorldRegion.WAITING, error = "Waiting for Wi-Fi") }; continue }
                val ticker = Thread {
                    try {
                        while (true) {
                            val c = WorldMaps.regions.value.firstOrNull { it.id == r.id } ?: break
                            nm.notify(NOTIF, notif("Downloading map: ${c.name}", c.bytesDone, c.bytesTotal))
                            Thread.sleep(1000)
                        }
                    } catch (_: InterruptedException) {}
                }.also { it.isDaemon = true; it.start() }
                try { WorldMaps.runDownload(this, r.id) { stopping } } finally { ticker.interrupt() }
            }
        } finally {
            runCatching { wake?.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            val done = WorldMaps.regions.value.filter { it.state == WorldRegion.DONE }
            val failed = WorldMaps.regions.value.firstOrNull { it.state == WorldRegion.ERROR }
            if (failed != null) nm.notify(NOTIF + 1, NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_trail)
                .setContentTitle("Map download stopped: ${failed.name}").setContentText(failed.error ?: "").setContentIntent(open()).setAutoCancel(true).build())
            else if (done.isNotEmpty()) nm.notify(NOTIF + 1, NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_trail)
                .setContentTitle("Offline map ready").setContentText(done.last().name).setContentIntent(open()).setAutoCancel(true).build())
            stopSelf()
        }
    }

    private fun open() = PendingIntent.getActivity(this, 41, Intent(this, com.victor.traillink.ui.MainActivity::class.java).putExtra("tab", "maps")
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notif(title: String, done: Long, total: Long) = NotificationCompat.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_stat_trail).setContentTitle(title)
        .setContentText(if (total > 0) "%.1f of %.1f MB".format(done / 1e6, total / 1e6) else "Working out what to fetch…")
        .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setContentIntent(open())
        .addAction(0, "Pause", PendingIntent.getService(this, 42, Intent(this, MapDownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() { stopping = true; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopping = true; stopSelf() }
}
