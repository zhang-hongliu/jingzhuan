package com.example.migratable.reminders

import android.content.Context
import com.example.migratable.Messages
import com.example.migratable.Prefs

/**
 * 安心提醒语库引导：
 * 首次运行时把内置 Messages.LIST 写入数据库，使「内置语库 + 用户语库」统一管理、
 * 全部支持新增 / 修改 / 删除。之后不再自动重新播种，尊重用户的删除操作。
 */
object Reminders {

    suspend fun bootstrap(context: Context) {
        if (Prefs.isRemindersSeeded(context)) return
        val dao = ReminderDatabase.get(context).reminderDao()
        if (dao.count() == 0) {
            dao.insertAll(Messages.LIST.map { UserMessage(text = it) })
        }
        Prefs.setRemindersSeeded(context, true)
    }
}
