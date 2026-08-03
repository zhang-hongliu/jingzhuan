package com.example.migratable.reminders

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(msg: UserMessage): Long

    @Update
    suspend fun update(msg: UserMessage)

    @Delete
    suspend fun delete(msg: UserMessage)

    /** 全部用户安心语，最新在前 */
    @Query("SELECT * FROM user_messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserMessage>>

    /** 一次性取出全部文本，供随机推送合并使用 */
    @Query("SELECT * FROM user_messages")
    suspend fun listAll(): List<UserMessage>

    @Query("SELECT COUNT(*) FROM user_messages")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(msgs: List<UserMessage>)

    @Query("DELETE FROM user_messages")
    suspend fun clearAll()
}
