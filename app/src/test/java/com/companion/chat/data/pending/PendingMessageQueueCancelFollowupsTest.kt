package com.companion.chat.data.pending

import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.domain.proactive.PendingMessageContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PendingMessageQueue.cancelPendingFollowups` 单测（V2.2.5）。
 *
 * 这条能力是本任务真正的核心：用户点「已完成」时，`pending_messages` 里可能已经躺着
 * 下一条追问（上一轮 FOLLOWUP 到点被 `tickFollowup` 入队、送达层还没取）。
 * 只 cancelAll() 闹钟拦不住它 —— 它到点照样被 `pollNext` 取走、照样弹通知。
 */
class PendingMessageQueueCancelFollowupsTest {

    private val t0 = 1_000_000L

    private fun queue(dao: FakePendingMessageDao) = PendingMessageQueue(dao, now = { t0 })

    /**
     * **尾冒号的守卫**：去掉 `+ ":"` 后，取消 id=1 会把 `followup:12:…` 一起杀掉，这条变红。
     */
    @Test
    fun cancels_only_rows_of_target_reminder() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = queue(dao)
        val target = q.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(1L, 2, "背单词"),
            t0
        )
        val neighbour = q.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(12L, 1, "喝水"),
            t0
        )

        assertEquals(1, q.cancelPendingFollowups(1L))

        assertEquals(
            "目标追问链的残留行必须被取消",
            PendingMessageState.CANCELLED,
            dao.items.first { it.id == target }.state
        )
        assertEquals(
            "followup:12 不是 followup:1，绝不能被误杀",
            PendingMessageState.PENDING,
            dao.items.first { it.id == neighbour }.state
        )
    }

    /** 当前通知对应的那条已被 claim 成 sent，SQL 的 `state='pending'` 天然排除它。 */
    @Test
    fun sent_row_is_not_touched() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = queue(dao)
        val id = q.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 1, "背单词"),
            t0
        )
        assertTrue("前置：认领成功", q.claim(id))

        assertEquals(0, q.cancelPendingFollowups(9L))
        assertEquals(PendingMessageState.SENT, dao.items.first { it.id == id }.state)
    }

    /** 非法 id 直接在门面层短路，不许碰 DAO（避免把 `"followup:"` 这种前缀丢进 LIKE）。 */
    @Test
    fun zero_or_negative_id_is_noop() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = queue(dao)
        q.enqueue(PendingMessageType.FOLLOW_UP, PendingMessageContent.Followup(9L, 1, "背单词"), t0)

        assertEquals(0, q.cancelPendingFollowups(0L))
        assertEquals(0, q.cancelPendingFollowups(-1L))

        assertTrue("非法 id 必须一次 DAO 调用都没有", dao.cancelPrefixCalls.isEmpty())
        assertEquals(
            "非法 id 不许改动任何行",
            PendingMessageState.PENDING,
            dao.items.single().state
        )
    }
}