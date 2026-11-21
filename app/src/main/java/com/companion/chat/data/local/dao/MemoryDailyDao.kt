package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.MemoryDailyEntity
import kotlinx.coroutines.flow.Flow

/** 每日日志 DAO。任务 1.1 */
@Dao
interface MemoryDailyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MemoryDailyEntity): Long

    @Update
    suspend fun update(log: MemoryDailyEntity)

    @Delete
    suspend fun delete(log: MemoryDailyEntity)

    @Query("SELECT * FROM memory_daily ORDER BY date DESC")
    fun observeAll(): Flow<List<MemoryDailyEntity>>

    @Query("SELECT * FROM memory_daily ORDER BY date DESC")
    suspend fun getAll(): List<MemoryDailyEntity>

    @Query("SELECT * FROM memory_daily WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): MemoryDailyEntity?

    @Query("DELETE FROM memory_daily")
    suspend fun deleteAll()
}
