package com.example.migratable.reminders

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 用户自己新增的「安心提醒」语，与内置 Messages.LIST 一起随机推送 */
@Entity(tableName = "user_messages")
data class UserMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)
