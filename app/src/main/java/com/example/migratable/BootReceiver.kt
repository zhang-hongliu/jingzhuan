package com.example.migratable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 开机完成后重新调度周期任务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isEnabled(context)) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    ReminderScheduler.WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    ReminderScheduler.buildRequest()
                )
            }
            // 物品过期检查任务开机重新调度
            com.example.migratable.inventory.ExpiryScheduler.schedule(context)
        }
    }
}
