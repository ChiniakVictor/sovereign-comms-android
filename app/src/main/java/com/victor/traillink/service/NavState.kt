package com.victor.traillink.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victor.traillink.R
import com.victor.traillink.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** Navigate-to-pin state (one target at a time), shared by the UI and the "Stop" notification action. */
object NavState {
    val pin = MutableStateFlow<String?>(null)
    @Volatile var arrived = false
    private const val NOTIF = 6100

    fun start(id: String) { pin.value = id; arrived = false }
    fun stop(ctx: Context) { pin.value = null; arrived = false; NotificationManagerCompat.from(ctx).cancel(NOTIF) }

    fun notifyArrived(ctx: Context, name: String) {
        LinkRepo.log("Navigation: arrived at '$name' (within 20 m)")
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val stop = PendingIntent.getBroadcast(ctx, NOTIF, Intent(ctx, NavStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, NOTIF, Intent(ctx, MainActivity::class.java).putExtra("tab", "map")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(NOTIF, NotificationCompat.Builder(ctx, LinkService.CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_trail).setContentTitle("📍 Arrived at $name").setContentText("You're within 20 m of the pin.")
            .setContentIntent(open).addAction(0, "Stop", stop).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
}

class NavStopReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = NavState.stop(c)
}
