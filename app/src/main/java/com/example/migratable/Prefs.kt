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
}
