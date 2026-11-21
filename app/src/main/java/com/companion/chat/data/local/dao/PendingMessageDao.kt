package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.companion.chat.data.local.entity.PendingMessageEntity
import kotlinx.coroutines.flow.Flow

/** 统一待发队列 DAO。任务 3.1 */
@Dao
interface PendingMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: PendingMessageEntity): Long

    /**
     * 取「到点 + 待发」里优先级最高的一条：priority 降序 → triggerTime 升序 → id 升序。
     * 只返回一条（结算「多条同时触发只发最高优先级那条、同级按时间先后」）。任务 3.1
     */
    @Query(
        "SELECT * FROM pending_messages " +
            "WHERE state = 'pending' AND triggerTime <= :now " +
            "ORDER BY priority DESC, triggerTime ASC, id ASC LIMIT 1"
    )
    suspend fun nextDue(now: Long): PendingMessageEntity?

    @Query("SELECT * FROM pending_messages WHERE id = :id")
    suspend fun getById(id: Long): PendingMessageEntity?

    @Query("SELECT * FROM pending_messages WHERE state = :state ORDER BY priority DESC, triggerTime ASC, id ASC")
    suspend fun getByState(state: String): List<PendingMessageEntity>

    @Query("SELECT * FROM pending_messages ORDER BY priority DESC, triggerTime ASC, id ASC")
    fun observeAll(): Flow<List<PendingMessageEntity>>

    /**
     * 有状态机约束的状态迁移：仅当当前 state 属于 [validFrom] 时才更新，返回受影响行数。
     * 用于「SENT 后不能再 CANCELLED」这类非法转换防御（任务 3.1 审查改进②）。
     */
    @Query("UPDATE pending_messages SET state = :newState WHERE id = :id AND state IN (:validFrom)")
    suspend fun transitionState(id: Long, newState: String, validFrom: List<String>): Int

    /** 无条件改状态（内部/调试用），返回受影响行数。 */
    @Query("UPDATE pending_messages SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: String): Int

    /**
     * 顺延一条待发消息的触发时间（任务 7.2：勿扰区间内把指令提醒推到区间结束）。
     * 只动 PENDING 的行，已发/已丢的不碰。返回受影响行数。
     */
    @Query("UPDATE pending_messages SET triggerTime = :triggerTime WHERE id = :id AND state = 'pending'")
    suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int

    /** 待发总数（含未来触发时间的）。 */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE state = 'pending'")
    suspend fun countPending(): Int

    /**
     * 队列里是否已有相同 content 的待发消息（V2.1.5）。
     *
     * 用途：环境钩子每小时 `recall()` 一次，而去重键只在**送达成功后**才写。
     * 若连续几小时都命中同一钩子却还没送达（闸门拦着、或送达失败），每次都会
     * enqueue 一条新的同 content 消息 → 队列堆副本 → 闸门一开连环送达同一件事。
     * 入队前查这个，已有就不再投。
     */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE state = 'pending' AND content = :content")
    suspend fun countPendingByContent(content: String): Int

    /** 当前可出队数量（state=pending 且 triggerTime <= now）。任务 3.1 审查改进④ */
    @Query("SELECT COUNT(*) FROM pending_messages WHERE state = 'pending' AND triggerTime <= :now")
    suspend fun countAvailable(now: Long): Int

    /**
     * 按 content 前缀批量取消待发（V2.2.5）。
     *
     * 用途：用户点追问通知的「已完成」时，队列里可能已躺着下一条追问
     * （`tickFollowup` 在 FOLLOWUP 钟到点就入队，送达层还没取）。只 cancelAll() 三个闹钟
     * 拦不住已入队那条 —— 它会照常被 pollNext 取走并弹通知，表现为「点了完成还在催」。
     *
     * ⚠️ 传进来的 [prefix] 必须自带尾冒号（`"followup:12:"`）。用 `"followup:12"` 会把
     * `followup:123:…` 一起杀掉。
     * ⚠️ 前缀里若出现 `%` 或 `_`（LIKE 通配符）必须改成 `content LIKE :prefix ESCAPE '\'`。
     * 当前前缀只有数字与冒号，安全。
     */
    @Query("UPDATE pending_messages SET state = :newState WHERE state = 'pending' AND content LIKE :prefix || '%'")
    suspend fun cancelPendingByContentPrefix(prefix: String, newState: String): Int

    @Query("DELETE FROM pending_messages")
    suspend fun deleteAll()
}
