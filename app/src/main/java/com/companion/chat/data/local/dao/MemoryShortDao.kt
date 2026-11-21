package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow

/** 短期记忆 DAO。任务 1.1（召回/去重的复杂查询在任务 2.x 补充） */
@Dao
interface MemoryShortDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryShortEntity): Long

    @Update
    suspend fun update(memory: MemoryShortEntity)

    @Delete
    suspend fun delete(memory: MemoryShortEntity)

    @Query("SELECT * FROM memory_short ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MemoryShortEntity>>

    @Query("SELECT * FROM memory_short")
    suspend fun getAll(): List<MemoryShortEntity>

    @Query("SELECT * FROM memory_short WHERE id = :id")
    suspend fun getById(id: Long): MemoryShortEntity?

    @Query("UPDATE memory_short SET weight = :weight WHERE id = :id")
    suspend fun updateWeight(id: Long, weight: Double)

    @Query("UPDATE memory_short SET fadeScore = :fadeScore WHERE id = :id")
    suspend fun updateFadeScore(id: Long, fadeScore: Double)

    /** 全表 fadeScore 原子自增（任务 2.5 淡忘批处理，单条 SQL 比逐条 update 快且不撕裂）。 */
    @Query("UPDATE memory_short SET fadeScore = fadeScore + :increment")
    suspend fun incrementAllFadeScore(increment: Double)

    /** 短期记忆总条数。 */
    @Query("SELECT COUNT(*) FROM memory_short")
    suspend fun count(): Int

    @Query("UPDATE memory_short SET lastMentioned = :time WHERE id = :id")
    suspend fun updateLastMentioned(id: Long, time: Long)

    /**
     * 「别提这个」三态落库：一次性更新权重、被点次数、冷却截止、拉黑标记。任务 2.8
     * （单条 SQL 原子更新，避免分多次 update 时状态撕裂。）
     */
    @Query(
        "UPDATE memory_short SET weight = :weight, dismissCount = :dismissCount, " +
            "coolingUntil = :coolingUntil, blacklisted = :blacklisted WHERE id = :id"
    )
    suspend fun applyFeedback(
        id: Long,
        weight: Double,
        dismissCount: Int,
        coolingUntil: Long?,
        blacklisted: Boolean
    )

    /** 按内容模糊查最近一条（提醒到点复用已存在的同主题记忆，避免重复）。任务 3.7 */
    @Query("SELECT * FROM memory_short WHERE content LIKE '%' || :keyword || '%' ORDER BY id DESC LIMIT 1")
    suspend fun findLatestContaining(keyword: String): MemoryShortEntity?

    /** 按 id 删除单条（提醒「已完成」回执用）。 */
    @Query("DELETE FROM memory_short WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_short")
    suspend fun deleteAll()
}
