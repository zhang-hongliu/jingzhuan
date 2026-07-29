package com.example.migratable.inventory

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.io.File

/** 物品列表：按过期时间排序，越先过期越靠前 */
class InventoryActivity : AppCompatActivity() {

    private val adapter = ItemAdapter { item ->
        startActivity(
            Intent(this, ItemEditActivity::class.java).putExtra("itemId", item.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)

        val list = findViewById<RecyclerView>(R.id.list_items)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val empty = findViewById<TextView>(R.id.text_empty)

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, ItemEditActivity::class.java))
        }
        findViewById<Button>(R.id.btn_places).setOnClickListener {
            startActivity(Intent(this, PlacesActivity::class.java))
        }

        lifecycleScope.launch {
            AppDatabase.get(this@InventoryActivity).inventoryDao()
                .observeItemRows()
                .collect { rows ->
                    adapter.submit(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                }
        }
    }
}

private class ItemAdapter(
    val onClick: (InvItem) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    private val rows = mutableListOf<ItemRow>()

    fun submit(newRows: List<ItemRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.img_photo)
        val name: TextView = v.findViewById(R.id.text_name)
        val place: TextView = v.findViewById(R.id.text_place)
        val expiry: TextView = v.findViewById(R.id.text_expiry)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.row_item, parent, false)
    )

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.name.text = row.item.name
        holder.place.text = when {
            row.houseName != null && row.locationName != null -> "${row.houseName} · ${row.locationName}"
            row.locationName != null -> row.locationName
            else -> "未指定位置"
        }

        val expiryAt = row.item.expiryAt
        if (expiryAt == null) {
            holder.expiry.text = "不过期"
            holder.expiry.setTextColor(0xFF888888.toInt())
        } else {
            val days = DateParser.daysLeft(expiryAt)
            when {
                days < 0 -> {
                    holder.expiry.text = "已过期 ${-days} 天\n${DateParser.format(expiryAt)}"
                    holder.expiry.setTextColor(0xFFD32F2F.toInt())
                }
                days == 0 -> {
                    holder.expiry.text = "今天过期！\n${DateParser.format(expiryAt)}"
                    holder.expiry.setTextColor(0xFFD32F2F.toInt())
                }
                days <= row.item.remindDaysBefore -> {
                    holder.expiry.text = "还剩 $days 天\n${DateParser.format(expiryAt)}"
                    holder.expiry.setTextColor(0xFFF57C00.toInt())
                }
                else -> {
                    holder.expiry.text = "还剩 $days 天\n${DateParser.format(expiryAt)}"
                    holder.expiry.setTextColor(0xFF388E3C.toInt())
                }
            }
        }

        val path = row.item.photoPath
        if (path != null && File(path).exists()) {
            holder.photo.setImageBitmap(decodeThumb(path))
        } else {
            holder.photo.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        holder.itemView.setOnClickListener { onClick(row.item) }
    }

    private fun decodeThumb(path: String) = BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)
        inSampleSize = maxOf(1, minOf(outWidth, outHeight) / 112)
        inJustDecodeBounds = false
        BitmapFactory.decodeFile(path, this)
    }
}
