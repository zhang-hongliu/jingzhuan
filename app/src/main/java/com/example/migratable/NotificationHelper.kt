package com.example.migratable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

import com.example.migratable.todos.TodoEntity
import com.example.migratable.todos.TodoListActivity

/** 通知渠道与通知展示 */
object NotificationHelper {
    const val CHANNEL_ID = "migratable_channel"
    const val CHANNEL_NAME = "重要的事-可迁移"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "每30分钟推送一条可迁移的安心提醒"
        }
        mgr.createNotificationChannel(channel)
    }

    fun show(context: Context, message: String) {
        ensureChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("重要的事-可迁移")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFY_ID, notification)
    }

    // ---------- 待办 / 快递提醒 ----------
    private const val TODO_CHANNEL_ID = "todo_channel"
    private const val TODO_NOTIFY_BASE = 30000

    fun ensureTodoChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            TODO_CHANNEL_ID,
            "待办与快递提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "从通知自动抓取的待办与快递取件提醒" }
        mgr.createNotificationChannel(channel)
    }

    fun showTodo(context: Context, todo: TodoEntity) {
        ensureTodoChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, TodoListActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, todo.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleText = if (todo.type == "EXPRESS") "📦 ${todo.title}" else "📋 ${todo.title}"
        val notification = NotificationCompat.Builder(context, TODO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleText)
            .setContentText(todo.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        mgr.notify(TODO_NOTIFY_BASE + todo.id.toInt(), notification)
    }
}
