package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

/** 提醒任务 DAO。任务 1.1 */
@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders ORDER BY triggerTime ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE state = 'pending' ORDER BY triggerTime ASC")
    suspend fun getPending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("UPDATE reminders SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: String)

    @Query("SELECT * FROM reminders WHERE triggerTime <= :now AND state = 'pending' ORDER BY triggerTime ASC")
    suspend fun getDue(now: Long): List<ReminderEntity>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    // ---- V2.2.1 追问链相关 ----

    /**
     * 活跃追问：已催过至少一次（attemptCount > 0）、仍在等待用户回复的那一条。
     * 消费者：V2.2.2 MoodService（要不要在对话里问「做完了没」）。
     * 注意判据是 attemptCount > 0 而不是 triggerTime <= now，见 §3.2。
     */
    @Query("""SELECT * FROM reminders
              WHERE state='pending' AND type='followup' AND attemptCount > 0
              ORDER BY triggerTime ASC LIMIT 1""")
    suspend fun activeFollowup(): ReminderEntity?

    /**
     * 全部活跃追问链（2026-11-20 多链并存后 MoodService 要用）。
     *
     * 为什么另开一条而不是把上面那条去掉：`activeFollowup()` 仍有单链调用点（兼容/日志），
     * 且返回 LIMIT 1 的语义对「只想知道有没有链」的地方更省。
     */
    @Query("""SELECT * FROM reminders
              WHERE state='pending' AND type='followup' AND attemptCount > 0
              ORDER BY triggerTime ASC""")
    suspend fun activeFollowups(): List<ReminderEntity>

    /** FOLLOWUP 闹钟到点准备投队列时 +1 */
    @Query("UPDATE reminders SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttempt(id: Long)

    /** 追问 advance：把触发时间推到下一次间隔 */
    @Query("UPDATE reminders SET triggerTime = :triggerTime WHERE id = :id")
    suspend fun updateTriggerTime(id: Long, triggerTime: Long)

    /**
     * 有没有「还没结束」的追问链（含刚创建、还没到点、attemptCount 仍为 0 的）。
     * 只用于 V2.2.3 防重复创建，判据只要 state=pending（§3.2 / V2.2.1 对照表）。
     */
    @Query("SELECT * FROM reminders WHERE state='pending' AND type='followup' LIMIT 1")
    suspend fun anyPendingFollowup(): ReminderEntity?

    /**
     * 同一任务是否已有在跑的追问链（2026-11-20 收口：把「全局最多一条」放宽成「同一任务最多一条」）。
     *
     * 原来只有 [anyPendingFollowup]：用户已有「背30个单词」链时再说「监督我喝水」，
     * 预检直接把新请求**静默**挡掉（无日志、无提示），而模型已经在对话里答应了「一分钟后开始」——
     * 真机表现为「说好了却一条提醒都不来」，用户只能干等。
     *
     * 不同任务各催各的不会互相干扰；只有**同一任务重复建链**才会变成「双链每天各催一遍」，
     * 所以去重键从「有没有链」收窄为「这条任务有没有链」。
     */
    @Query(
        "SELECT * FROM reminders WHERE state='pending' AND type='followup' AND taskDesc = :task LIMIT 1"
    )
    suspend fun pendingFollowupByTask(task: String): ReminderEntity?
}
