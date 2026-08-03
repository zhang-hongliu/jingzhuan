package com.example.migratable.todos

import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.migratable.NotificationHelper
import com.example.migratable.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * 监听手机通知，识别待办 / 快递取件码并写入本地库，同时即时弹通知提醒。
 * 需在系统「通知使用权」中为本 App 授权后才会生效。
 */
class TodoListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 不抓取此类 App 的通知。这类是纯支付 / 金融类应用，其通知几乎都是
     * 「支付成功 / 交易确认 / 账单」等交易回执，不属于待办，强行解析会
     * 把支付信息误建成待办。
     */
    private val IGNORE_PACKAGES = setOf(
        "com.eg.android.AlipayGphone", // 支付宝：交易 / 支付回执
        "com.eg.android.AlipayGphoneRC" // 支付宝（双开 / 测试版）
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.isTodosEnabled(this)) return

        val pkg = sbn.packageName
        if (pkg == packageName) return // 忽略自身通知
        if (pkg in IGNORE_PACKAGES) return // 忽略支付类 App 的交易回执，避免误建待办

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val body = listOfNotNull(text, big, lines).joinToString("\n").trim()
        if (body.isBlank() && title.isNullOrBlank()) return

        val key = "$pkg|${title ?: ""}|$body"
        if (isRecent(key)) return

        val parsed = NotificationParser.parse(pkg, title, body)
        if (parsed.isEmpty()) return

        for (p in parsed) {
            scope.launch {
                val dao = TodoDatabase.get(this@TodoListenerService).todoDao()
                val entity = p.toEntity(pkg, title)
                val id = dao.insert(entity)
                NotificationHelper.showTodo(this@TodoListenerService, entity.copy(id = id))
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    /** 10 分钟内同一条通知只处理一次 */
    private fun isRecent(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[key]
            if (last != null && now - last < 10 * 60_000) return true
            recent[key] = now
            val it = recent.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > 30 * 60_000) it.remove()
            }
        }
        return false
    }

    companion object {
        private val recent = LinkedHashMap<String, Long>()

        /** 系统「通知使用权」是否已授权本服务 */
        fun isEnabled(context: android.content.Context): Boolean {
            val cn = ComponentName(context, TodoListenerService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(":").any {
                it == cn.flattenToString() || it == cn.flattenToShortString() || it.endsWith(cn.className)
            }
        }
    }
}
