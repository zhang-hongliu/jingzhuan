package com.example.migratable

import kotlin.random.Random

/**
 * 从「内置语库 + 用户自定义安心提醒」中合并后随机取一条。
 * userMessages 由 ReminderDatabase 提供，为空时退化为仅内置语库。
 */
object MessageProvider {
    fun random(userMessages: List<String> = emptyList()): String {
        val all = Messages.LIST + userMessages
        return all[Random.nextInt(all.size)]
    }
}
