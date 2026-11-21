package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProactiveCheckRunner 编排单测（假闸门 + 假召回 + 真队列配假 DAO）。任务 3.2 */
class ProactiveCheckRunnerTest {

    private class FakeDao : PendingMessageDao {
        val items = ArrayList<PendingMessageEntity>()
        private var seq = 0L
        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
            items.add(withId); return withId.id
        }
        override suspend fun nextDue(now: Long): PendingMessageEntity? = null
        override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByState(state: String): List<PendingMessageEntity> = items.filter { it.state == state }
        override fun observeAll(): Flow<List<PendingMessageEntity>> = emptyFlow()
        override suspend fun transitionState(id: Long, newState: String, validFrom: List<String>): Int {
            val i = items.indexOfFirst { it.id == id && it.state in validFrom }
            if (i < 0) return 0
            items[i] = items[i].copy(state = newState); return 1
        }
        override suspend fun updateState(id: Long, state: String): Int {
            val i = items.indexOfFirst { it.id == id }; if (i < 0) return 0
            items[i] = items[i].copy(state = state); return 1
        }
        override suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int {
            val i = items.indexOfFirst { it.id == id && it.state == PendingMessageState.PENDING }
            if (i < 0) return 0
            items[i] = items[i].copy(triggerTime = triggerTime); return 1
        }
        override suspend fun countPending(): Int = items.count { it.state == PendingMessageState.PENDING }
        override suspend fun countPendingByContent(content: String): Int = 0
        override suspend fun countAvailable(now: Long): Int =
            items.count { it.state == PendingMessageState.PENDING && it.triggerTime <= now }
        // V2.2.5：新增 DAO 方法，本测试只为满足接口
        override suspend fun cancelPendingByContentPrefix(prefix: String, newState: String): Int {
            var n = 0
            for (i in items.indices) {
                val row = items[i]
                if (row.state == PendingMessageState.PENDING && row.content.startsWith(prefix)) {
                    items[i] = row.copy(state = newState); n++
                }
            }
            return n
        }

        override suspend fun deleteAll() { items.clear() }
    }

    private fun gate(v: Boolean) = object : ProactiveGate {
        override suspend fun allow(category: ProactiveMessageCategory): Boolean = v
    }

    private val t0 = 5000L

    @Test
    fun gate_blocked_does_not_enqueue() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val runner = ProactiveCheckRunner(gate(false), recallHook = { 42L }, queue = q, now = { t0 }, log = {})
        val r = runner.runOnce()
        assertEquals(ProactiveCheckOutcome.GATE_BLOCKED, r.outcome)
        assertNull(r.pendingId)
        assertEquals(0, q.pendingCount())
    }

    @Test
    fun no_hook_does_not_enqueue() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val runner = ProactiveCheckRunner(gate(true), recallHook = { null }, queue = q, now = { t0 }, log = {})
        val r = runner.runOnce()
        assertEquals(ProactiveCheckOutcome.NO_HOOK, r.outcome)
        assertNull(r.pendingId)
        assertEquals(0, q.pendingCount())
    }

    @Test
    fun hook_enqueues_chitchat_message() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        val runner = ProactiveCheckRunner(gate(true), recallHook = { 42L }, queue = q, now = { t0 }, log = {})
        val r = runner.runOnce()
        assertEquals(ProactiveCheckOutcome.ENQUEUED, r.outcome)
        assertEquals(1, q.pendingCount())
        val msg = dao.items.single()
        assertEquals(PendingMessageType.CHITCHAT.code, msg.type)
        assertEquals(1, msg.priority)
        assertEquals("memory:42", msg.content)
        assertEquals(t0, msg.triggerTime)
        assertEquals(PendingMessageState.PENDING, msg.state)
        assertEquals(msg.id, r.pendingId)
    }

    @Test
    fun recall_called_only_when_gate_allows() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { t0 })
        var called = 0
        val blocked = ProactiveCheckRunner(gate(false), recallHook = { called++; 1L }, queue = q, now = { t0 }, log = {})
        blocked.runOnce()
        assertEquals(0, called) // 闸门先拦，不该召回
        val allowed = ProactiveCheckRunner(gate(true), recallHook = { called++; 1L }, queue = q, now = { t0 }, log = {})
        allowed.runOnce()
        assertEquals(1, called)
    }

    @Test
    fun memory_id_parse() {
        assertEquals(42L, ProactiveCheckRunner.memoryIdOf("memory:42"))
        assertNull(ProactiveCheckRunner.memoryIdOf("instruction:xx"))
        assertNull(ProactiveCheckRunner.memoryIdOf("memory:abc"))
    }
}


