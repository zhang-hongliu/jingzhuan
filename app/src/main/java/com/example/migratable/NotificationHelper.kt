package com.example.migratable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** 通知渠道与通知展示 */
object NotificationHelper {
    const val CHANNEL_ID = "migratable_channel"
    const val CHANNEL_NAME = "重要的事-可迁移"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "每30分钟推送一条可迁移的安心提醒"
        }
        mgr.createNotificationChannel(channel)
    }

    fun show(context: Context, message: String) {
        ensureChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("重要的事-可迁移")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFY_ID, notification)
    }
}
