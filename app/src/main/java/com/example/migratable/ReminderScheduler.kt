package com.example.migratable

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 统一管理 WorkManager 周期任务的调度与取消 */
object ReminderScheduler {
    const val WORK_NAME = "reminder_work"

    fun buildRequest() =
        PeriodicWorkRequestBuilder<ReminderWorker>(30, TimeUnit.MINUTES).build()

    fun schedule(context: Context) {
        if (!Prefs.isEnabled(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
