package com.example.migratable.reminders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R

/** 安心提醒语库列表：每条都支持「修改」与「删除」 */
class ReminderLibraryAdapter(
    private val onEdit: (UserMessage) -> Unit,
    private val onDelete: (UserMessage) -> Unit
) : RecyclerView.Adapter<ReminderLibraryAdapter.VH>() {

    private val items = mutableListOf<UserMessage>()

    fun submit(new: List<UserMessage>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.text_msg)
        val edit: Button = v.findViewById(R.id.btn_edit)
        val del: Button = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.row_reminder_library, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        holder.edit.setOnClickListener { onEdit(item) }
        holder.del.setOnClickListener { onDelete(item) }
    }
}
