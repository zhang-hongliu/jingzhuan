package com.example.migratable.inventory

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.migratable.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 过期检查任务的调度 */
object ExpiryScheduler {
    const val WORK_NAME = "expiry_check_work"

    /** 每 12 小时检查一次（首次延迟到下一个上午 9 点附近） */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExpiryCheckWorker>(12, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    private fun initialDelayMinutes(): Long {
        val now = Calendar.getInstance()
        val next9 = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (before(now) || this == now) add(Calendar.DAY_OF_YEAR, 1)
        }
        val diff = (next9.timeInMillis - now.timeInMillis) / 60_000L
        // 若距离下个 9 点超过 12 小时，就不额外等（保证半天内至少查一次）
        return diff.coerceAtMost(12 * 60)
    }
}

/**
 * 过期检查：
 * - 已过期 → 每天提醒一次「已过期 N 天」
 * - 距过期 ≤ 物品设置的提前天数 → 每天提醒一次「还剩 N 天」
 * - 用 lastNotifiedDay 保证同一天只提醒一次
 */
class ExpiryCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = AppDatabase.get(ctx).inventoryDao()
        val today = DateParser.todayMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour >= 22) return Result.success() // 夜间静默

        for (row in dao.listItemsWithExpiry()) {
            val item = row.item
            val expiryAt = item.expiryAt ?: continue
            if (item.lastNotifiedDay >= today) continue // 今天已提醒过

            val days = DateParser.daysLeft(expiryAt)
            val place = listOfNotNull(row.houseName, row.locationName)
                .joinToString(" · ").ifEmpty { "未指定位置" }

            val text = when {
                days < 0 -> "「${item.name}」已过期 ${-days} 天！位置：$place"
                days == 0 -> "「${item.name}」今天过期！位置：$place"
                days <= item.remindDaysBefore -> "「${item.name}」还有 $days 天过期（${DateParser.format(expiryAt)}），位置：$place"
                else -> continue
            }

            notify(ctx, item.id, text)
            dao.markNotified(item.id, today)
        }
        return Result.success()
    }

    private fun notify(context: Context, itemId: Long, text: String) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "物品过期提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "物品到期前和过期后的提醒" }
            mgr.createNotificationChannel(channel)
        }

        val intent = Intent(context, InventoryActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, itemId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("物品过期提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFY_BASE + itemId.toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "expiry_channel"
        private const val NOTIFY_BASE = 20000
    }
}
