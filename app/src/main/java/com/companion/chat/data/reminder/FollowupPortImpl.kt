package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.reminder.ActiveFollowup
import com.companion.chat.domain.reminder.ReminderMemoryMatcher
import com.companion.chat.domain.reminder.FollowupPort

/**
 * 追问端口真实实现（V2.2.2）。
 *
 * 顺序固定：
 * - active()    → DAO 判据（pending + followup + attemptCount>0）由 SQL 负责
 * - complete()  → 先 updateState(DONE) 再 cancelAll：语义先落库，取消失败也不会留「pending 却再也不响」
 * - abandon()   → 先 updateState(ABANDONED) 再 cancelAll（统计里「放弃」不能混进「完成」）
 * - advance()   → 先 cancel 旧 TIMEOUT → 推 triggerTime → 再排下一次 FOLLOWUP（到点回查 DB 才看到新值）
 *
 * advance 刻意**不** incrementAttempt：次数已在「FOLLOWUP 闹钟到点投队列」时加过，
 * 这里再加会让 maxAttempts 提前触顶。
 *
 * 不包 runCatching：异常交给上层 MoodService 兜，静默吞掉会让 V2.2.4 排查困难。
 */
class FollowupPortImpl(
    private val dao: ReminderDao,
    private val alarms: FollowupAlarms,
    /**
     * V2.2.2 遗留收口（2026-11-20）：**必须传生产实例**。
     *
     * 曾经这里没有队列，「停链」只做了一半：`updateState` + `cancelAll` 之后，
     * **已经入队**的那条 `followup:<id>:…` 仍躺在 `pending_messages` 里，
     * 15 分钟后照常送达 → 用户在对话里说「我做完了」，回头还是被催一次。
     *
     * V2.2.5 只在「点通知按钮」那条路（`ReminderActionHandler`）补了 `cancelPendingFollowups`，
     * `MoodService` 判 completed/abandoned 走的是本类，属于同一语义的另一半。
     */
    private val queue: PendingMessageQueue,
    /**
     * 2026-11-20：停链时同步清掉对应的短期记忆（用户要求「监督结束就别留在记忆里」）。
     *
     * 追问链的短记忆行是「FOLLOWUP 到点投队列 → 送达生成正文」时才按任务原文建的
     * （`handleInstruction` 之外的另一条路），内容是 `followup:<id>:<task>` 编码。
     * 不传则跳过（测试/旧装配零改动）。
     */
    private val memoryShortDao: MemoryShortDao? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : FollowupPort {

    override suspend fun activeAll(): List<ActiveFollowup> =
        dao.activeFollowups().map { ActiveFollowup(it.id, it.taskDesc, it.stopCondition) }

    /**
     * 完成：先落库 + 取消三个闹钟，**再**清掉已入队的残留追问。
     *
     * 顺序与 [com.companion.chat.data.reminder.ReminderActionHandler] 的 done 分支逐字一致，
     * **不可对调**：先清队列而 `updateState` 失败 → 闹钟仍在、链仍 pending → 下次到点又入队，
     * 变成无限追问；反之（先成功、清队列失败）最坏只多收一条，是噪音不是死链。
     */
    override suspend fun complete(reminderId: Long) {
        dao.updateState(reminderId, ReminderState.DONE)
        alarms.cancelAll(reminderId)
        queue.cancelPendingFollowups(reminderId)
        deleteFollowupMemory(reminderId)
    }

    /** 放弃：语义同上，只是落 `ABANDONED`（统计里「放弃」不能混进「完成」）。 */
    override suspend fun abandon(reminderId: Long) {
        dao.updateState(reminderId, ReminderState.ABANDONED)
        alarms.cancelAll(reminderId)
        queue.cancelPendingFollowups(reminderId)
        deleteFollowupMemory(reminderId)
    }

    /**
     * 删除这条链的短期记忆（2026-11-20，用户要求「监督结束就别留在记忆里」）。
     *
     * **不能按 id / 编码前缀删**（真机数据推翻了这个假设）：`memory_short` 里存的是
     * 自由的**中文原文**，两类来源措辞都与 `reminders.taskDesc` 不同，也没有 reminderId 列：
     *  - `handleInstruction` 写的提醒原文；
     *  - `MemoryExtractor` 从对话提炼的「用户要求…提醒喝水…」。
     * 所以按 [ReminderMemoryMatcher] 的相似度规则挑出来再逐条删。
     * 表很小（几十条），一次 `getAll` + 内存过滤比模糊 SQL 更准。
     * 全程 runCatching：清理失败绝不能挡住「停链」这个主流程。
     */
    private suspend fun deleteFollowupMemory(reminderId: Long) {
        val mem = memoryShortDao ?: return
        val task = this.dao.getById(reminderId)?.taskDesc ?: return
        runCatching {
            mem.getAll()
                .filter { ReminderMemoryMatcher.shouldClear(it.content, task) }
                .forEach { mem.deleteById(it.id) }
        }
    }

    override suspend fun advance(reminderId: Long) {
        alarms.cancelTimeout(reminderId)
        val minutes = dao.getById(reminderId)?.followupInterval ?: DEFAULT_INTERVAL_MINUTES
        val triggerTime = nowMillis() + minutes * 60_000L
        dao.updateTriggerTime(reminderId, triggerTime)
        alarms.scheduleFollowup(reminderId, triggerTime)
    }

    override suspend fun taskOf(reminderId: Long): String? = dao.getById(reminderId)?.taskDesc

    companion object {
        /** V2.2.3 建行时也用这个值，别各写一个魔数。 */
        const val DEFAULT_INTERVAL_MINUTES = 15
    }
}
