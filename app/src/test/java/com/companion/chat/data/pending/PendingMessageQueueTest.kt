package com.companion.chat.data.pending

import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingMessageQueue 出队/状态机单测（假 DAO）。任务 3.1
 *
 * V2.2.4：内嵌的 private `FakeDao` 提成共用 [FakePendingMessageDao]（`FollowupChainRunnerTest`
 * 需要同一套语义 + 可注入的 insert 失败），行为逐条保持不变。
 */
class PendingMessageQueueTest {

    private val t0 = 1000L

    @Test
    fun enqueue_sets_priority_from_type() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.INSTRUCTION, "叫起床", t0)
        q.enqueue(PendingMessageType.CHITCHAT, "闲聊", t0)
        assertEquals(5, dao.items.first { it.type == PendingMessageType.INSTRUCTION.code }.priority)
        assertEquals(1, dao.items.first { it.type == PendingMessageType.CHITCHAT.code }.priority)
        assertEquals(PendingMessageState.PENDING, dao.items.first().state)
        assertEquals(t0, dao.items.first().createdAt)
    }

    @Test
    fun picks_highest_priority_among_due() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.CHITCHAT, "闲聊", t0)
        q.enqueue(PendingMessageType.INSTRUCTION, "指令", t0)  // 最高
        q.enqueue(PendingMessageType.ENVIRONMENT, "环境", t0)
        q.enqueue(PendingMessageType.FOLLOW_UP, "追问", t0)
        q.enqueue(PendingMessageType.SPECIAL_DATE, "节日", t0)
        val next = q.peekNext(t0)
        assertEquals(PendingMessageType.INSTRUCTION.code, next!!.type)
    }

    @Test
    fun same_priority_earlier_trigger_time_first() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.FOLLOW_UP, "晚", t0 + 100)
        q.enqueue(PendingMessageType.FOLLOW_UP, "早", t0 + 10)
        val next = q.peekNext(t0 + 500)
        assertEquals("早", next!!.content)
    }

    @Test
    fun same_priority_same_time_earlier_id_first() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.SPECIAL_DATE, "先入", t0)
        q.enqueue(PendingMessageType.SPECIAL_DATE, "后入", t0)
        assertEquals("先入", q.peekNext(t0)!!.content)
    }

    @Test
    fun future_message_not_due() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.INSTRUCTION, "未来", t0 + 10_000)
        assertNull(q.peekNext(t0))
        // 到点后可出
        assertEquals("未来", q.peekNext(t0 + 10_000)!!.content)
    }

    @Test
    fun poll_next_claims_and_returns_next_in_turn() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.CHITCHAT, "A", t0)   // pri 1
        q.enqueue(PendingMessageType.INSTRUCTION, "B", t0) // pri 5
        val first = q.pollNext(t0)!!
        assertEquals("B", first.content)
        assertEquals(PendingMessageState.SENT, dao.items.first { it.id == first.id }.state)
        // 第二轮取剩下那条
        val second = q.pollNext(t0)!!
        assertEquals("A", second.content)
        // 都取完了
        assertNull(q.pollNext(t0))
    }

    @Test
    fun poll_twice_does_not_return_same_message() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.INSTRUCTION, "唯一", t0)
        assertEquals("唯一", q.pollNext(t0)!!.content)
        // 已 SENT，不再是候选
        assertNull(q.pollNext(t0))
        assertEquals(1, dao.items.count { it.state == PendingMessageState.SENT })
    }

    @Test
    fun discard_returns_true_and_excludes() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val idHigh = q.enqueue(PendingMessageType.INSTRUCTION, "指令", t0)
        q.enqueue(PendingMessageType.CHITCHAT, "闲聊", t0)
        assertTrue(q.discard(idHigh))
        assertEquals(PendingMessageState.DISCARDED, dao.items.first { it.id == idHigh }.state)
        // 高优先级被丢弃后，轮到闲聊
        assertEquals("闲聊", q.peekNext(t0)!!.content)
    }

    @Test
    fun cancel_returns_true_for_pending() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val id = q.enqueue(PendingMessageType.CHITCHAT, "闲聊", t0)
        assertTrue(q.cancel(id))
        assertNull(q.peekNext(t0))
    }

    @Test
    fun state_machine_forbids_illegal_transitions() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val id = q.enqueue(PendingMessageType.INSTRUCTION, "指令", t0)
        q.pollNext(t0) // PENDING → SENT
        // 已 SENT：不能再 cancel / discard
        assertFalse(q.cancel(id))
        assertFalse(q.discard(id))
        assertEquals(PendingMessageState.SENT, dao.items.first { it.id == id }.state)
        // 不存在的 id 也返回 false
        assertFalse(q.cancel(999L))
        assertFalse(q.discard(999L))
    }

    @Test
    fun pending_and_available_count_differ_with_future() = runBlocking {
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.CHITCHAT, "现", t0)          // 现在可出
        q.enqueue(PendingMessageType.INSTRUCTION, "未来", t0 + 10_000) // 未来
        assertEquals(2, q.pendingCount())        // 全部 pending
        assertEquals(1, q.availableCount(t0))    // 仅到点
        assertEquals(2, q.availableCount(t0 + 10_000))
    }

    @Test
    fun empty_queue_returns_null() = runBlocking {
        val q = PendingMessageQueue(FakePendingMessageDao(), now = { t0 })
        assertNull(q.peekNext(t0))
        assertNull(q.pollNext(t0))
    }

    @Test
    fun acceptance_many_simultaneous_only_highest_sent() = runBlocking {
        // 计划验收：多条消息同时触发，只发最高优先级那条；同级按时间先后
        val dao = FakePendingMessageDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        q.enqueue(PendingMessageType.CHITCHAT, "闲聊", t0)
        q.enqueue(PendingMessageType.ENVIRONMENT, "环境", t0)
        q.enqueue(PendingMessageType.SPECIAL_DATE, "节日", t0)
        q.enqueue(PendingMessageType.FOLLOW_UP, "追问", t0)
        q.enqueue(PendingMessageType.INSTRUCTION, "指令", t0)
        val sent = q.pollNext(t0)!!
        assertEquals(PendingMessageType.INSTRUCTION.code, sent.type)
        assertTrue(dao.items.count { it.state == PendingMessageState.SENT } == 1)
    }
}

