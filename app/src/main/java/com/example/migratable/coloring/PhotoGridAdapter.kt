package com.example.migratable.coloring

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R

/** 相册里的一张照片 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    var selected: Boolean = false,
    /** 原始照片缩略图 */
    var thumb: Bitmap? = null,
    /** 生成后的线稿缩略图 */
    var lineThumb: Bitmap? = null,
    var failed: Boolean = false,
    var isLoading: Boolean = false
) {
    val isDone: Boolean get() = lineThumb != null
}

/** 相册九宫格：点击切换选中，线稿生成后原地替换为线稿预览 */
class PhotoGridAdapter(
    private val onToggle: (PhotoItem) -> Unit,
    private val onNeedThumb: (PhotoItem) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.VH>() {

    private val items = mutableListOf<PhotoItem>()

    fun submit(list: List<PhotoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun refresh() = notifyDataSetChanged()

    fun indexOf(item: PhotoItem) = items.indexOf(item)

    val current: List<PhotoItem> get() = items

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: FrameLayout = v.findViewById(R.id.frame_photo)
        val img: ImageView = v.findViewById(R.id.img_photo)
        val check: CheckBox = v.findViewById(R.id.check_photo)
        val badge: TextView = v.findViewById(R.id.text_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.row_photo, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        val display = item.lineThumb ?: item.thumb
        if (display != null) {
            holder.img.setImageBitmap(display)
        } else {
            holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
            onNeedThumb(item)
        }

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.selected
        holder.check.setOnCheckedChangeListener { _, checked ->
            item.selected = checked
            onToggle(item)
        }

        holder.frame.setBackgroundColor(if (item.selected) 0x331565C0 else 0x00000000)

        holder.badge.visibility = View.VISIBLE
        holder.badge.text = when {
            item.failed -> "失败"
            item.isDone -> "线稿"
            else -> ""
        }
        if (!item.failed && !item.isDone) holder.badge.visibility = View.GONE

        holder.itemView.setOnClickListener {
            item.selected = !item.selected
            holder.check.isChecked = item.selected
            holder.frame.setBackgroundColor(if (item.selected) 0x331565C0 else 0x00000000)
            onToggle(item)
        }
    }
}
