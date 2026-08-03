package com.example.migratable

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.migratable.reminders.ReminderDatabase
import com.example.migratable.reminders.Reminders
import java.util.Calendar

/**
 * 周期任务：每 30 分钟触发一次。
 * 仅在 09:00–22:00 之间、且提醒开关开启时，随机推送一条消息。
 */
class ReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.isEnabled(applicationContext)) return Result.success()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 9 || hour >= 22) return Result.success() // 窗口外静默跳过

        // 确保内置语库已播种进数据库
        Reminders.bootstrap(applicationContext)

        val userTexts = ReminderDatabase.get(applicationContext).reminderDao()
            .listAll().map { it.text }
        val msg = MessageProvider.random(userTexts)
        NotificationHelper.show(applicationContext, msg)
        return Result.success()
    }
}
