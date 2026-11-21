package com.companion.chat.data.reminder

import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.NotificationActions
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.reminder.ActiveFollowup
import com.companion.chat.domain.reminder.FollowupPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列时钟固定值：让 triggerTime 恒为「到点」，不必为每条用例各造一个时间。 */
private const val T0 = 1_000_000L

/**
 * 通知按钮回执分支单测（V2.2.5）。
 *
 * 为什么不是测 `ReminderDoneReceiver`：它是 Android `BroadcastReceiver`，JVM 跑不了。
 * 分支抽到 [ReminderActionHandler] 后，「done / postpone 各只触发对应分支」才可断言。
 */
class ReminderActionHandlerTest {

    /** 记录式假 port（项目无 Mockito，不引依赖；写法同 MoodServiceFollowupTest）。 */
    private class FakeFollowupPort(private val order: MutableList<String>? = null) : FollowupPort {
        val called = mutableListOf<String>()
        var taskOfResult: String? = null
        override suspend fun activeAll(): List<ActiveFollowup> = emptyList()
        override suspend fun complete(reminderId: Long) {
            called += "complete:$reminderId"
            order?.add("complete:$reminderId")
        }
        override suspend fun abandon(reminderId: Long) { called += "abandon:$reminderId" }
        override suspend fun advance(reminderId: Long) {
            called += "advance:$reminderId"
            order?.add("advance:$reminderId")
        }
        override suspend fun taskOf(reminderId: Long): String? = null
    }

    /** 三个依赖 + 一个共享 order 流水，避免每个用例重复五行装配。 */
    private class Harness(order: MutableList<String>? = null) {
        val dao = FakePendingMessageDao().apply { this.order = order }
        val queue = PendingMessageQueue(dao, now = { T0 })
        val port = FakeFollowupPort(order)
        val deleted = mutableListOf<Long>()
        val handler = ReminderActionHandler(
            followupPort = port,
            queue = queue,
            deleteMemory = { deleted += it }
        )
    }

    @Test
    fun done_deletes_memory_and_completes_followup() = runBlocking {
        val h = Harness()
        // 队列里躺着的下一条追问：点「已完成」时必须一并取消，否则会继续催
        val queued = h.queue.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 2, "背单词"),
            T0
        )

        assertTrue(h.handler.handle(NotificationActions.ACTION_DONE, memoryId = 77L, reminderId = 9L))

        assertEquals(listOf(77L), h.deleted)
        assertTrue(h.port.called.contains("complete:9"))
        assertEquals(
            PendingMessageState.CANCELLED,
            h.dao.items.first { it.id == queued }.state
        )
    }

    /** 2026-11-20：追问对应的短记忆由 FollowupPortImpl.complete() 清理（对话路径同源），handler 不再重复删。 */
    @Test
    fun done_does_not_delete_memory_by_task_prefix_anymore() = runBlocking {
        val h = Harness()
        h.port.taskOfResult = "背单词"

        assertTrue(h.handler.handle(NotificationActions.ACTION_DONE, memoryId = 0L, reminderId = 9L))

        assertTrue(h.deleted.isEmpty())
        assertTrue(h.port.called.contains("complete:9"))
    }

    @Test
    fun done_without_ids_is_ignored() = runBlocking {
        val h = Harness()
        val queued = h.queue.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 1, "背单词"),
            T0
        )

        assertFalse(h.handler.handle(NotificationActions.ACTION_DONE, memoryId = 0L, reminderId = 0L))

        assertTrue("无 id 时不许删记忆", h.deleted.isEmpty())
        assertTrue("无 id 时不许动追问链", h.port.called.isEmpty())
        assertTrue("无 id 时连 DAO 都不该碰", h.dao.cancelPrefixCalls.isEmpty())
        assertEquals(PendingMessageState.PENDING, h.dao.items.first { it.id == queued }.state)
    }

    /** 「再等等」= 只重排下一次，**不**推进次数（advance 内部刻意不 incrementAttempt）。 */
    @Test
    fun postpone_only_advances() = runBlocking {
        val h = Harness()
        val queued = h.queue.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 1, "背单词"),
            T0
        )

        assertTrue(h.handler.handle(NotificationActions.ACTION_POSTPONE, memoryId = 0L, reminderId = 9L))

        assertEquals(listOf("advance:9"), h.port.called)
        assertFalse("postpone 不许 complete", h.port.called.any { it.startsWith("complete:") })
        assertTrue("postpone 不许清队列", h.dao.cancelPrefixCalls.isEmpty())
        assertEquals(PendingMessageState.PENDING, h.dao.items.first { it.id == queued }.state)
    }

    /**
     * 两个 id 都非空却点了 postpone：不删记忆。
     * 删了不可恢复，保留只是多留一条短期记忆，取更保守的一侧。
     */
    @Test
    fun postpone_never_deletes_memory() = runBlocking {
        val h = Harness()
        h.handler.handle(NotificationActions.ACTION_POSTPONE, memoryId = 77L, reminderId = 9L)
        assertTrue("postpone 绝不能删记忆", h.deleted.isEmpty())
    }

    /** 旧通知没有 EXTRA_ACTION：必须按 done 处理，否则升级后点旧通知完全没反应。 */
    @Test
    fun null_action_is_treated_as_done_for_old_notifications() = runBlocking {
        val h = Harness()
        assertTrue(h.handler.handle(action = null, memoryId = 0L, reminderId = 9L))
        assertTrue(h.port.called.contains("complete:9"))
    }

    /** 未知 action 不静默变成 done：将来加新 action 拼错时宁可什么都不做。 */
    @Test
    fun unknown_action_is_ignored() = runBlocking {
        val h = Harness()
        assertFalse(h.handler.handle("reply", memoryId = 77L, reminderId = 9L))
        assertTrue(h.deleted.isEmpty())
        assertTrue(h.port.called.isEmpty())
        assertTrue(h.dao.cancelPrefixCalls.isEmpty())
    }

    /**
     * 顺序不可对调（DONE）：
     * 若先清队列再 complete，而 complete 因 DB 异常失败 → 链仍 pending、闹钟还在，
     * 下一次 FOLLOWUP 到点又入队 → **无限追问**，比「漏清一条残留」严重得多。
     */
    @Test
    fun complete_runs_before_queue_cleanup() = runBlocking {
        val order = mutableListOf<String>()
        val h = Harness(order)
        h.queue.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 2, "背单词"),
            T0
        )

        h.handler.handle(NotificationActions.ACTION_DONE, memoryId = 0L, reminderId = 9L)

        val completeAt = order.indexOf("complete:9")
        val cancelAt = order.indexOf("cancel")
        assertTrue("complete 必须真的被调用", completeAt >= 0)
        assertTrue("队列清理必须真的被调用", cancelAt >= 0)
        assertTrue("complete($completeAt) 必须先于 cancel($cancelAt)", completeAt < cancelAt)
    }
}
