package com.companion.chat.data.local.dao

import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ReminderDao 的 JVM 假实现（V2.2.1：全测试目录原本 0 个 Fake ReminderDao，这是新建的第 1 个）。
 *
 * 行为与真实 @Query 对齐，供 V2.2.2+ 的 domain/data 单测复用：
 *  - activeFollowup(): pending + followup + attemptCount>0，按 triggerTime 升序取第一条
 *  - anyPendingFollowup(): pending + followup，不限 attemptCount，取第一条（防重复创建用）
 */
class FakeReminderDao : ReminderDao {

    /** 可变存储；测试可直接读写断言。 */
    val items = ArrayList<ReminderEntity>()
    private var seq = 0L

    /** [getById] 调用次数（V2.2.4：断言「非法 reminderId 时零查询」用）。 */
    var getByIdCalls = 0
        private set

    /** [incrementAttempt] 调用次数（V2.2.4：断言 TIMEOUT 不计数时不必逐行比 attemptCount）。 */
    var incrementAttemptCalls = 0
        private set

    /**
     * 跨 fake 的调用顺序流水（V2.2.4）。测试把同一个 list 塞给 DAO / 队列 DAO / 闹钟 fake，
     * 就能断言「排 TIMEOUT → 计数 → 入队」的真实先后 —— 光断言「计数和入队都发生了」
     * 钉不住顺序（顺序反了只要两步都执行，断言照样通过）。
     */
    var order: MutableList<String>? = null

    override suspend fun insert(reminder: ReminderEntity): Long {
        val id = if (reminder.id == 0L) ++seq else reminder.id
        if (id > seq) seq = id
        // 对应 insert(onConflict = REPLACE)
        items.removeAll { it.id == id }
        items.add(reminder.copy(id = id))
        return id
    }

    override suspend fun update(reminder: ReminderEntity) {
        val i = items.indexOfFirst { it.id == reminder.id }
        if (i >= 0) items[i] = reminder
    }

    override suspend fun delete(reminder: ReminderEntity) {
        items.removeAll { it.id == reminder.id }
    }

    override fun observeAll(): Flow<List<ReminderEntity>> =
        flowOf(items.sortedBy { it.triggerTime })

    override suspend fun getPending(): List<ReminderEntity> =
        items.filter { it.state == ReminderState.PENDING }
            .sortedBy { it.triggerTime }

    override suspend fun getById(id: Long): ReminderEntity? {
        getByIdCalls++
        return items.firstOrNull { it.id == id }
    }

    override suspend fun updateState(id: Long, state: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(state = state)
    }

    override suspend fun getDue(now: Long): List<ReminderEntity> =
        items.filter { it.triggerTime <= now && it.state == ReminderState.PENDING }
            .sortedBy { it.triggerTime }

    override suspend fun deleteAll() {
        items.clear()
    }

    // ---- V2.2.1 新增 4 个方法：语义与真实 SQL 保持一致 ----

    override suspend fun activeFollowup(): ReminderEntity? =
        items.filter {
            it.state == ReminderState.PENDING &&
                it.type == ReminderType.FOLLOWUP &&
                it.attemptCount > 0
        }.sortedBy { it.triggerTime }.firstOrNull()

    /** 2026-11-20 多链：与 SQL 一致，按 triggerTime 升序返回全部活跃链。 */
    override suspend fun activeFollowups(): List<ReminderEntity> =
        items.filter {
            it.state == ReminderState.PENDING &&
                it.type == ReminderType.FOLLOWUP &&
                it.attemptCount > 0
        }.sortedBy { it.triggerTime }

    override suspend fun incrementAttempt(id: Long) {
        incrementAttemptCalls++
        order?.add("incrementAttempt")
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(attemptCount = items[i].attemptCount + 1)
    }

    override suspend fun updateTriggerTime(id: Long, triggerTime: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(triggerTime = triggerTime)
    }

    override suspend fun anyPendingFollowup(): ReminderEntity? =
        items.firstOrNull {
            it.state == ReminderState.PENDING && it.type == ReminderType.FOLLOWUP
        }

    /** 2026-11-20：追问去重键改为「任务文本」，对齐 SQL 的 `taskDesc = :task`（精确匹配）。 */
    override suspend fun pendingFollowupByTask(task: String): ReminderEntity? =
        items.firstOrNull {
            it.state == ReminderState.PENDING &&
                it.type == ReminderType.FOLLOWUP &&
                it.taskDesc == task
        }
}
