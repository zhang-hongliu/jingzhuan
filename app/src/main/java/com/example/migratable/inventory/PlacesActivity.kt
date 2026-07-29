package com.example.migratable.inventory

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.migratable.R
import kotlinx.coroutines.launch

/**
 * 房子与位置管理：
 * - 房子（组）→ 位置（子项），支持多个不同地方的房子
 * - 点组内「+ 添加位置」新增位置；长按删除
 */
class PlacesActivity : AppCompatActivity() {

    private var houses: List<House> = emptyList()
    private var locsByHouse: Map<Long, List<StorageLocation>> = emptyMap()
    private lateinit var listView: ExpandableListView
    private val adapter = PlacesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_places)

        listView = findViewById(R.id.list_places)
        listView.setAdapter(adapter)

        findViewById<Button>(R.id.btn_add_house).setOnClickListener {
            promptText("添加房子", "例如：自己家、父母家、公司") { name ->
                lifecycleScope.launch {
                    dao().insertHouse(House(name = name))
                    reload()
                }
            }
        }

        // 长按删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val packed = listView.getExpandableListPosition(position)
            when (ExpandableListView.getPackedPositionType(packed)) {
                ExpandableListView.PACKED_POSITION_TYPE_GROUP -> {
                    val house = houses[ExpandableListView.getPackedPositionGroup(packed)]
                    confirm("删除房子「${house.name}」？其下所有位置也会删除，物品会变为未指定位置。") {
                        lifecycleScope.launch { dao().deleteHouse(house); reload() }
                    }
                    true
                }
                ExpandableListView.PACKED_POSITION_TYPE_CHILD -> {
                    val g = ExpandableListView.getPackedPositionGroup(packed)
                    val c = ExpandableListView.getPackedPositionChild(packed)
                    val locs = locsByHouse[houses[g].id].orEmpty()
                    if (c < locs.size) {
                        val loc = locs[c]
                        confirm("删除位置「${loc.name}」？该位置上的物品会变为未指定位置。") {
                            lifecycleScope.launch { dao().deleteLocation(loc); reload() }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 点击子项：最后一行是「+ 添加位置」
        listView.setOnChildClickListener { _, _, groupPos, childPos, _ ->
            val house = houses[groupPos]
            val locs = locsByHouse[house.id].orEmpty()
            if (childPos == locs.size) { // 「+ 添加位置」行
                promptText("在「${house.name}」添加位置", "例如：厨房冰箱、主卧衣柜") { name ->
                    lifecycleScope.launch {
                        dao().insertLocation(StorageLocation(houseId = house.id, name = name))
                        reload()
                    }
                }
            }
            true
        }

        reload()
    }

    private fun dao() = AppDatabase.get(this).inventoryDao()

    private fun reload() {
        lifecycleScope.launch {
            houses = dao().listHouses()
            locsByHouse = dao().listLocations()
                .groupBy({ it.house.id }, { it.location })
            adapter.notifyDataSetChanged()
            for (i in houses.indices) listView.expandGroup(i)
        }
    }

    private fun promptText(title: String, hint: String, onOk: (String) -> Unit) {
        val edit = EditText(this).apply { this.hint = hint }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirm(message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class PlacesAdapter : BaseExpandableListAdapter() {
        override fun getGroupCount() = houses.size
        override fun getChildrenCount(groupPosition: Int) =
            locsByHouse[houses[groupPosition].id].orEmpty().size + 1 // +1 = 「添加位置」行

        override fun getGroup(groupPosition: Int) = houses[groupPosition]
        override fun getChild(groupPosition: Int, childPosition: Int): Any {
            val locs = locsByHouse[houses[groupPosition].id].orEmpty()
            return if (childPosition < locs.size) locs[childPosition] else "add"
        }

        override fun getGroupId(groupPosition: Int) = houses[groupPosition].id
        override fun getChildId(groupPosition: Int, childPosition: Int) = childPosition.toLong()
        override fun hasStableIds() = false
        override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true

        override fun getGroupView(
            groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?
        ): View {
            val v = convertView ?: layoutInflater.inflate(
                android.R.layout.simple_expandable_list_item_1, parent, false
            )
            v.findViewById<TextView>(android.R.id.text1).apply {
                text = "🏠 ${houses[groupPosition].name}"
                textSize = 16f
            }
            return v
        }

        override fun getChildView(
            groupPosition: Int, childPosition: Int, isLastChild: Boolean,
            convertView: View?, parent: ViewGroup?
        ): View {
            val v = convertView ?: layoutInflater.inflate(
                android.R.layout.simple_list_item_1, parent, false
            )
            val locs = locsByHouse[houses[groupPosition].id].orEmpty()
            v.findViewById<TextView>(android.R.id.text1).apply {
                if (childPosition < locs.size) {
                    text = "　📍 ${locs[childPosition].name}"
                    setTextColor(0xFF333333.toInt())
                } else {
                    text = "　＋ 添加位置"
                    setTextColor(0xFF1976D2.toInt())
                }
                textSize = 15f
            }
            return v
        }
    }
}
