package com.example.migratable.inventory

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 房子：物品可以放在多个不同地方的房子里，例如「自己家」「父母家」「公司」 */
@Entity(tableName = "houses")
data class House(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 可选备注，例如地址 */
    val note: String = ""
)

/** 位置：属于某个房子，例如「厨房冰箱」「主卧衣柜」 */
@Entity(
    tableName = "locations",
    foreignKeys = [ForeignKey(
        entity = House::class,
        parentColumns = ["id"],
        childColumns = ["houseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("houseId")]
)
data class StorageLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val houseId: Long,
    val name: String
)

/** 物品 */
@Entity(
    tableName = "items",
    foreignKeys = [ForeignKey(
        entity = StorageLocation::class,
        parentColumns = ["id"],
        childColumns = ["locationId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("locationId"), Index("expiryAt")]
)
data class InvItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 所在位置 id，可为空（未指定位置） */
    val locationId: Long? = null,
    /** 照片文件绝对路径，可为空 */
    val photoPath: String? = null,
    /** 条形码 / 二维码内容，可为空 */
    val barcode: String? = null,
    /** 过期时间：当天 00:00 的 epoch millis；空表示不过期 */
    val expiryAt: Long? = null,
    /** 提前几天提醒（默认 3 天） */
    val remindDaysBefore: Int = 3,
    /** 上次已提醒的日期（epoch millis，当天 00:00），避免同一天重复推送 */
    @ColumnInfo(defaultValue = "0") val lastNotifiedDay: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 位置 + 所属房子（列表展示用） */
data class LocationWithHouse(
    @Embedded val location: StorageLocation,
    @Relation(parentColumn = "houseId", entityColumn = "id")
    val house: House
)

/** 物品 + 位置 + 房子（列表展示用，手动 JOIN 字段） */
data class ItemRow(
    @Embedded val item: InvItem,
    val locationName: String?,
    val houseName: String?
)
