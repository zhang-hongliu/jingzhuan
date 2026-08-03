package com.example.migratable.reminders

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.MessageProvider
import com.example.migratable.NotificationHelper
import com.example.migratable.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/** 安心提醒语库管理：内置语库 + 用户语库，全部支持新增 / 修改 / 删除 */
class ReminderLibraryActivity : AppCompatActivity() {

    private val adapter = ReminderLibraryAdapter(
        onEdit = { showEditDialog(it) },
        onDelete = { confirmDelete(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_library)

        // 首次进入先把内置语库播种进数据库
        lifecycleScope.launch { Reminders.bootstrap(this@ReminderLibraryActivity) }

        val list = findViewById<RecyclerView>(R.id.list_reminders)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val empty = findViewById<TextView>(R.id.text_empty)

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            showEditDialog(null)
        }

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            lifecycleScope.launch {
                val all = ReminderDatabase.get(this@ReminderLibraryActivity)
                    .reminderDao().listAll().map { it.text }
                if (all.isNotEmpty()) {
                    NotificationHelper.show(this@ReminderLibraryActivity, MessageProvider.random(all))
                }
            }
        }

        lifecycleScope.launch {
            ReminderDatabase.get(this@ReminderLibraryActivity).reminderDao()
                .observeAll()
                .collect { msgs ->
                    adapter.submit(msgs)
                    empty.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
                    list.visibility = if (msgs.isEmpty()) View.GONE else View.VISIBLE
                }
        }
    }

    /** msg 为 null 表示新增；否则为修改 */
    private fun showEditDialog(msg: UserMessage?) {
        val edit = EditText(this).apply {
            setText(msg?.text ?: "")
            hint = if (msg == null) "写一句让你安心的话" else "修改这条安心提醒"
        }
        AlertDialog.Builder(this)
            .setTitle(if (msg == null) "新增安心提醒" else "修改安心提醒")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotBlank()) {
                    lifecycleScope.launch {
                        val dao = ReminderDatabase.get(this@ReminderLibraryActivity).reminderDao()
                        if (msg == null) dao.insert(UserMessage(text = text))
                        else dao.update(msg.copy(text = text))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(msg: UserMessage) {
        AlertDialog.Builder(this)
            .setTitle("删除安心提醒")
            .setMessage("确定删除这条吗？\n\n${msg.text}")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    ReminderDatabase.get(this@ReminderLibraryActivity).reminderDao().delete(msg)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
