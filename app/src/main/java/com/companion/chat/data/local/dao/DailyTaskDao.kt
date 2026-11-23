package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.DailyTaskEntity
import kotlinx.coroutines.flow.Flow

/** 每日任务 DAO（2026-11-23）。 */
@Dao
interface DailyTaskDao {

    /** 列表按时间升序，界面直接按这个顺序展示。 */
    @Query("SELECT * FROM daily_tasks ORDER BY hour ASC, minute ASC, id ASC")
    fun observeAll(): Flow<List<DailyTaskEntity>>

    @Query("SELECT * FROM daily_tasks ORDER BY hour ASC, minute ASC, id ASC")
    suspend fun getAll(): List<DailyTaskEntity>

    @Query("SELECT * FROM daily_tasks WHERE enabled = 1")
    suspend fun getEnabled(): List<DailyTaskEntity>

    @Query("SELECT * FROM daily_tasks WHERE id = :id")
    suspend fun getById(id: Long): DailyTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DailyTaskEntity): Long

    @Update
    suspend fun update(task: DailyTaskEntity)

    @Query("UPDATE daily_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE daily_tasks SET conversationId = :conversationId WHERE id = :id")
    suspend fun setConversationId(id: Long, conversationId: Long)

    @Query("DELETE FROM daily_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(task: DailyTaskEntity)
}
