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

        // 进入即确保调度（开关开启时）
        ReminderScheduler.schedule(this)
        // 物品过期检查任务
        ExpiryScheduler.schedule(this)
    }
}
