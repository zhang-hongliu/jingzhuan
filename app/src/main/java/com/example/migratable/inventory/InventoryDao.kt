package com.example.migratable.inventory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    // ---------- 房子 ----------
    @Insert
    suspend fun insertHouse(house: House): Long

    @Update
    suspend fun updateHouse(house: House)

    @Delete
    suspend fun deleteHouse(house: House)

    @Query("SELECT * FROM houses ORDER BY id")
    fun observeHouses(): Flow<List<House>>

    @Query("SELECT * FROM houses ORDER BY id")
    suspend fun listHouses(): List<House>

    // ---------- 位置 ----------
    @Insert
    suspend fun insertLocation(location: StorageLocation): Long

    @Delete
    suspend fun deleteLocation(location: StorageLocation)

    @Transaction
    @Query("SELECT * FROM locations ORDER BY houseId, id")
    fun observeLocations(): Flow<List<LocationWithHouse>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY houseId, id")
    suspend fun listLocations(): List<LocationWithHouse>

    // ---------- 物品 ----------
    @Insert
    suspend fun insertItem(item: InvItem): Long

    @Update
    suspend fun updateItem(item: InvItem)

    @Delete
    suspend fun deleteItem(item: InvItem)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItem(id: Long): InvItem?

    @Query(
        """SELECT items.*, locations.name AS locationName, houses.name AS houseName
           FROM items
           LEFT JOIN locations ON items.locationId = locations.id
           LEFT JOIN houses ON locations.houseId = houses.id
           ORDER BY CASE WHEN items.expiryAt IS NULL THEN 1 ELSE 0 END, items.expiryAt ASC"""
    )
    fun observeItemRows(): Flow<List<ItemRow>>

    /** 需要检查过期的物品（有过期时间的） */
    @Query(
        """SELECT items.*, locations.name AS locationName, houses.name AS houseName
           FROM items
           LEFT JOIN locations ON items.locationId = locations.id
           LEFT JOIN houses ON locations.houseId = houses.id
           WHERE items.expiryAt IS NOT NULL"""
    )
    suspend fun listItemsWithExpiry(): List<ItemRow>

    @Query("UPDATE items SET lastNotifiedDay = :day WHERE id = :id")
    suspend fun markNotified(id: Long, day: Long)
}
