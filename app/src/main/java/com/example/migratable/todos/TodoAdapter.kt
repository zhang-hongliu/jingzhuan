package com.example.migratable.todos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.migratable.R
import com.example.migratable.inventory.DateParser

/** 待办列表通用适配器，首页与待办页复用 */
class TodoAdapter(
    private val onToggle: (TodoEntity) -> Unit,
    private val onDelete: (TodoEntity) -> Unit,
    private val appLabel: (String?) -> String?
) : RecyclerView.Adapter<TodoAdapter.VH>() {

    private val items = mutableListOf<TodoEntity>()

    fun submit(new: List<TodoEntity>) {
        items.clear()
        items.addAll(new)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check_done)
        val type: TextView = v.findViewById(R.id.text_type)
        val title: TextView = v.findViewById(R.id.text_title)
        val content: TextView = v.findViewById(R.id.text_content)
        val meta: TextView = v.findViewById(R.id.text_meta)
        val del: Button = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.row_todo, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.done
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != item.done) onToggle(item)
        }

        holder.title.text = item.title
        holder.title.paint.isStrikeThruText = item.done
        holder.title.setTextColor(if (item.done) 0xFF888888.toInt() else 0xFF000000.toInt())

        holder.content.text = item.content.ifBlank { "" }

        val isExpress = item.type == "EXPRESS"
        holder.type.text = if (isExpress) "快递" else "待办"
        holder.type.setBackgroundColor(if (isExpress) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt())

        val due = item.dueAt?.let { DateParser.format(it) }
        val parts = mutableListOf<String>()
        appLabel(item.sourceApp)?.let { parts += "来源：$it" }
        due?.let { parts += "到期：$it" }
        holder.meta.text = parts.joinToString(" · ")

        holder.del.setOnClickListener { onDelete(item) }
    }
}
