package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.MemoryLongEntity
import kotlinx.coroutines.flow.Flow

/** 长期记忆 DAO。任务 1.1 */
@Dao
interface MemoryLongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryLongEntity): Long

    @Update
    suspend fun update(memory: MemoryLongEntity)

    @Delete
    suspend fun delete(memory: MemoryLongEntity)

    @Query("SELECT * FROM memory_long ORDER BY createTime DESC")
    fun observeAll(): Flow<List<MemoryLongEntity>>

    @Query("SELECT * FROM memory_long")
    suspend fun getAll(): List<MemoryLongEntity>

    @Query("SELECT * FROM memory_long WHERE id = :id")
    suspend fun getById(id: Long): MemoryLongEntity?

    @Query("SELECT * FROM memory_long WHERE category = :category ORDER BY createTime DESC")
    suspend fun getByCategory(category: String): List<MemoryLongEntity>

    @Query("DELETE FROM memory_long")
    suspend fun deleteAll()
}
