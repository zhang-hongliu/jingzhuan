package com.example.migratable

import android.content.Context
import android.content.SharedPreferences

/** 提醒开关状态持久化（SharedPreferences） */
object Prefs {
    private const val NAME = "migratable_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // 通知待办抓取开关（默认开启）
    private const val KEY_TODOS_ENABLED = "todos_enabled"

    fun isTodosEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_TODOS_ENABLED, true)

    fun setTodosEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_TODOS_ENABLED, enabled).apply()
    }

    // 安心提醒语库是否已首次播种（内置语库写入数据库）
    private const val KEY_REMINDERS_SEEDED = "reminders_seeded"

    fun isRemindersSeeded(context: Context): Boolean =
        sp(context).getBoolean(KEY_REMINDERS_SEEDED, false)

    fun setRemindersSeeded(context: Context, seeded: Boolean) {
        sp(context).edit().putBoolean(KEY_REMINDERS_SEEDED, seeded).apply()
    }
}
