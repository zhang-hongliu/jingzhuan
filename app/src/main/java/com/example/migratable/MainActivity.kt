package com.example.migratable

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import com.example.migratable.inventory.ExpiryScheduler
import com.example.migratable.inventory.InventoryActivity
import android.content.ComponentName
import android.provider.Settings
import com.example.migratable.todos.TodoListActivity
import com.example.migratable.todos.TodoListenerService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val listView = findViewById<ListView>(R.id.list_messages)
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            Messages.LIST
        )

        val switch = findViewById<Switch>(R.id.switch_enabled)
        switch.isChecked = Prefs.isEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setEnabled(this, isChecked)
            if (isChecked) ReminderScheduler.schedule(this)
            else ReminderScheduler.cancel(this)
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            NotificationHelper.show(this, MessageProvider.random())
        }

        findViewById<Button>(R.id.btn_inventory).setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
        }

        // 通知待办：开关 + 查看 + 权限引导
        val switchTodos = findViewById<Switch>(R.id.switch_todos)
        switchTodos.isChecked = Prefs.isTodosEnabled(this)
        switchTodos.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTodosEnabled(this, isChecked)
        }
        findViewById<Button>(R.id.btn_todos).setOnClickListener {
            startActivity(Intent(this, TodoListActivity::class.java))
        }
        findViewById<Button>(R.id.btn_enable_listener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        updateListenerStatus()

        // 进入即确保调度（开关开启时）
        ReminderScheduler.schedule(this)
        // 物品过期检查任务
        ExpiryScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        updateListenerStatus()
    }

    private fun updateListenerStatus() {
        val status = findViewById<TextView>(R.id.text_listener_status)
        status.text = if (TodoListenerService.isEnabled(this)) {
            "✅ 通知监听已开启，正在自动抓取待办与快递码"
        } else {
            "⚠️ 未开启通知监听，点「去开启通知监听权限」到系统设置授权"
        }
    }
}
