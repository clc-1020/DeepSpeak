package com.companion.chat.data.pending

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.domain.proactive.PendingMessageContent

/**
 * 统一待发队列的服务门面。任务 3.1
 *
 * 所有触发源只调 [enqueue] 投递，**不直接发消息**；发送层调 [pollNext] 每次只取「到点且
 * 优先级最高」的一条（同级按 triggerTime、再按 id 先后）。priority 由 [PendingMessageType]
 * 推导，调用方无需关心数值。
 *
 * 取出的语义：
 *  - [peekNext] 只看不发（不改状态，适合决策闸门先判断要不要发）。
 *  - [pollNext] 「认领」一条（置为 SENT）并返回，供发送层使用；没有可发的返回 null。
 *  - [discard] 决策闸门判定丢弃时调用（置 DISCARDED）。
 *  - [cancel] 触发源作废（如用户删了提醒）时调用（置 CANCELLED）。
 */
class PendingMessageQueue(
    private val dao: PendingMessageDao,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 投递一条待发消息，返回新增行 id。priority 由 type 自动推导。
     * @param triggerTime 计划触发时间（epoch millis）；<= 当前时间表示立即可发。
     */
    suspend fun enqueue(
        type: PendingMessageType,
        content: String,
        triggerTime: Long
    ): Long = dao.insert(
        PendingMessageEntity(
            type = type.code,
            priority = type.priority,
            content = content,
            triggerTime = triggerTime,
            state = PendingMessageState.PENDING,
            createdAt = now()
        )
    )

    /** 类型安全投递重载：生产端建议用 [PendingMessageContent] 走此重载，自动编码。 */
    suspend fun enqueue(
        type: PendingMessageType,
        content: PendingMessageContent,
        triggerTime: Long
    ): Long = enqueue(type, content.encode(), triggerTime)

    /** 取当前到点、优先级最高的一条（不改状态）；无则 null。 */
    suspend fun peekNext(nowMillis: Long = now()): PendingMessageEntity? = dao.nextDue(nowMillis)

    /**
     * 顺延一条待发消息的触发时间（任务 7.2：勿扰区间内把指令提醒推到区间结束时再发）。
     * 只对 PENDING 生效；返回是否真的改了。
     */
    suspend fun reschedule(id: Long, triggerTime: Long): Boolean =
        dao.updateTriggerTime(id, triggerTime) > 0

    /** 按 id 取一条（不改状态）。 */
    suspend fun get(id: Long): PendingMessageEntity? = dao.getById(id)

    /** 认领某条（仅 PENDING→SENT）；返回是否成功（false=不存在/已被处理）。 */
    suspend fun claim(id: Long): Boolean =
        dao.transitionState(id, PendingMessageState.SENT, listOf(PendingMessageState.PENDING)) > 0

    /** 取并认领（置 SENT）当前到点、优先级最高的一条；无则 null。 */
    suspend fun pollNext(nowMillis: Long = now()): PendingMessageEntity? {
        val next = dao.nextDue(nowMillis) ?: return null
        // 状态机约束：仅 PENDING→SENT；若并发已被别人认领（0 行）则本次不返回，避免重复发送
        val claimed = dao.transitionState(
            next.id, PendingMessageState.SENT, listOf(PendingMessageState.PENDING)
        )
        return if (claimed > 0) next else null
    }

    /**
     * 标记为丢弃（决策闸门用）。仅 PENDING 可丢弃。
     * @return 是否真正发生迁移（false=id 不存在或已非 PENDING）
     */
    suspend fun discard(id: Long): Boolean = dao.transitionState(
        id, PendingMessageState.DISCARDED, listOf(PendingMessageState.PENDING)
    ) > 0

    /**
     * 标记为取消（触发源作废用）。仅 PENDING 可取消（已 SENT 的不可回退为 CANCELLED）。
     * @return 是否真正发生迁移（false=id 不存在或已非 PENDING）
     */
    suspend fun cancel(id: Long): Boolean = dao.transitionState(
        id, PendingMessageState.CANCELLED, listOf(PendingMessageState.PENDING)
    ) > 0

    /** 待发总数（含未来触发时间的）。 */
    suspend fun pendingCount(): Int = dao.countPending()

    /**
     * 取消某条追问链**已入队**的全部待发追问（V2.2.5）。
     *
     * @return 实际被取消的行数（0 = 队列里本来就没有）。
     *
     * **尾冒号是必需的**：`FU_PREFIX + id` 会误杀 `followup:12:…`。见 DAO 注释。
     * **不会误杀当前通知对应的那条**：它已被 pollNext/claim 置为 SENT，
     * 而 SQL 里的 `state = 'pending'` 把它排除了。
     */
    suspend fun cancelPendingFollowups(reminderId: Long): Int {
        if (reminderId <= 0L) return 0
        return dao.cancelPendingByContentPrefix(
            prefix = PendingMessageContent.FU_PREFIX + reminderId + ":",
            newState = PendingMessageState.CANCELLED
        )
    }

    /**
     * 队列里是否已有相同 content 的待发消息（V2.1.5）。
     *
     * 环境钩子入队前用它防副本堆积，见 [com.companion.chat.data.local.dao.PendingMessageDao.countPendingByContent]。
     */
    suspend fun hasPendingWithContent(content: String): Boolean = dao.countPendingByContent(content) > 0

    /** 当前可出队数量（state=pending 且 triggerTime <= now）。 */
    suspend fun availableCount(nowMillis: Long = now()): Int = dao.countAvailable(nowMillis)
}
