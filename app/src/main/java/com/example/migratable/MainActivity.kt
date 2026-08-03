package com.example.migratable

import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.inventory.ExpiryScheduler
import com.example.migratable.inventory.InventoryActivity
import com.example.migratable.reminders.ReminderLibraryActivity
import com.example.migratable.reminders.Reminders
import com.example.migratable.todos.TodoAdapter
import com.example.migratable.todos.TodoDatabase
import com.example.migratable.todos.TodoEntity
import com.example.migratable.todos.TodoListActivity
import com.example.migratable.todos.TodoListenerService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val todoAdapter = TodoAdapter(
        onToggle = { todo ->
            lifecycleScope.launch {
                TodoDatabase.get(this@MainActivity).todoDao().setDone(todo.id, !todo.done)
            }
        },
        onDelete = { todo ->
            lifecycleScope.launch {
                TodoDatabase.get(this@MainActivity).todoDao().delete(todo)
            }
        },
        appLabel = { pkg -> appLabel(pkg) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 首次启动把内置安心语库播种进数据库，使整个语库可管理
        lifecycleScope.launch { Reminders.bootstrap(this@MainActivity) }

        // ---------- 安心提醒（首页仅作入口） ----------
        val switchReminder = findViewById<Switch>(R.id.switch_enabled)
        switchReminder.isChecked = Prefs.isEnabled(this)
        switchReminder.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setEnabled(this, isChecked)
            if (isChecked) ReminderScheduler.schedule(this) else ReminderScheduler.cancel(this)
        }

        findViewById<Button>(R.id.btn_manage_reminders).setOnClickListener {
            startActivity(Intent(this, ReminderLibraryActivity::class.java))
        }

        // ---------- 待办 ----------
        val listTodos = findViewById<RecyclerView>(R.id.list_todos)
        listTodos.layoutManager = LinearLayoutManager(this)
        listTodos.adapter = todoAdapter
        val todoEmpty = findViewById<TextView>(R.id.text_todo_empty)

        findViewById<Button>(R.id.btn_add_todo).setOnClickListener { addTodo() }
        findViewById<Button>(R.id.btn_view_todos).setOnClickListener {
            startActivity(Intent(this, TodoListActivity::class.java))
        }

        lifecycleScope.launch {
            TodoDatabase.get(this@MainActivity).todoDao().observeAll()
                .collect { all ->
                    val recent = all.take(6)
                    todoAdapter.submit(recent)
                    todoEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
                    listTodos.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
                }
        }

        // ---------- 物品 ----------
        findViewById<Button>(R.id.btn_inventory).setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
        }

        // ---------- 通知待办监听 ----------
        val switchTodos = findViewById<Switch>(R.id.switch_todos)
        switchTodos.isChecked = Prefs.isTodosEnabled(this)
        switchTodos.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTodosEnabled(this, isChecked)
        }
        findViewById<Button>(R.id.btn_enable_listener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        updateListenerStatus()

        // 进入即确保调度（开关开启时）
        ReminderScheduler.schedule(this)
        ExpiryScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        updateListenerStatus()
    }

    private fun addTodo() {
        val edit = EditText(this).apply { hint = "例如：缴水电费、取快递" }
        AlertDialog.Builder(this)
            .setTitle("新增待办")
            .setView(edit)
            .setPositiveButton("添加") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotBlank()) {
                    lifecycleScope.launch {
                        TodoDatabase.get(this@MainActivity).todoDao()
                            .insert(TodoEntity(type = "TODO", title = text, content = ""))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateListenerStatus() {
        val status = findViewById<TextView>(R.id.text_listener_status)
        status.text = if (TodoListenerService.isEnabled(this)) {
            "✅ 通知监听已开启，正在自动抓取待办与快递码"
        } else {
            "⚠️ 未开启通知监听，点「去开启通知监听权限」到系统设置授权"
        }
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
