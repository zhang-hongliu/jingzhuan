package com.example.migratable.todos

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/** 通知待办列表：查看 / 标记完成 / 删除 / 手动添加 */
class TodoListActivity : AppCompatActivity() {

    private val adapter = TodoAdapter(
        onToggle = { todo ->
            lifecycleScope.launch {
                TodoDatabase.get(this@TodoListActivity).todoDao().setDone(todo.id, !todo.done)
            }
        },
        onDelete = { todo ->
            lifecycleScope.launch {
                TodoDatabase.get(this@TodoListActivity).todoDao().delete(todo)
            }
        },
        appLabel = { pkg -> appLabel(pkg) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_todo_list)

        val list = findViewById<RecyclerView>(R.id.list_todos)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val empty = findViewById<TextView>(R.id.text_empty)

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener { addManual() }

        lifecycleScope.launch {
            TodoDatabase.get(this@TodoListActivity).todoDao().observeAll()
                .collect { todos ->
                    adapter.submit(todos)
                    empty.visibility = if (todos.isEmpty()) View.VISIBLE else View.GONE
                    list.visibility = if (todos.isEmpty()) View.GONE else View.VISIBLE
                }
        }
    }

    private fun addManual() {
        val edit = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("添加待办")
            .setView(edit)
            .setPositiveButton("添加") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotBlank()) {
                    lifecycleScope.launch {
                        TodoDatabase.get(this@TodoListActivity).todoDao()
                            .insert(TodoEntity(type = "TODO", title = text, content = ""))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
