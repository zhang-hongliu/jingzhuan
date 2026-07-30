package com.example.migratable.todos

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待办记录（通知自动抓取 + 手动添加）。
 * type: "TODO" 普通待办 / "EXPRESS" 快递取件
 */
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 类型：TODO 普通待办 / EXPRESS 快递取件 */
    val type: String,
    /** 摘要标题 */
    val title: String,
    /** 详情 / 通知摘要 */
    val content: String,
    /** 快递取件码（EXPRESS 用） */
    val code: String? = null,
    /** 来源应用包名 */
    val sourceApp: String? = null,
    /** 来源通知标题 */
    val sourceTitle: String? = null,
    /** 原始通知文本（可选留存） */
    val rawText: String? = null,
    /** 到期时间（当天 00:00 的 epoch millis，可选） */
    val dueAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false
)
