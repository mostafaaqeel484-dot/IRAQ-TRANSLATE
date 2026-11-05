package com.iraqtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Optional notification only; does not violate modern background MediaProjection/FGS constraints. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSharedPreferences("iraq_hub", Context.MODE_PRIVATE).getBoolean("boot_reminder", false)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "iraq_translate_reminder"
        manager.createNotificationChannel(NotificationChannel(channel, "IRAQ TRANSLATE تذكير", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(
            context, 0, Intent(context, ControlHubActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("IRAQ TRANSLATE")
            .setContentText("افتح لوحة التحكم لتشغيل الترجمة بموافقتك")
            .setContentIntent(pending).setAutoCancel(true).build()
        runCatching { manager.notify(1007, notification) }
    }
}
