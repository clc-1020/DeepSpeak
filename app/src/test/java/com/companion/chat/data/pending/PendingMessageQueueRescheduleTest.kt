package com.companion.chat.data.pending

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列顺延单测：勿扰把提醒推到区间结束。任务 7.2 */
class PendingMessageQueueRescheduleTest {

    private class FakeDao : PendingMessageDao {
        val items = ArrayList<PendingMessageEntity>()
        private var seq = 0L

        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
            items.add(withId); return withId.id
        }

        override suspend fun nextDue(now: Long): PendingMessageEntity? =
            items.filter { it.state == PendingMessageState.PENDING && it.triggerTime <= now }
                .sortedWith(
                    compareByDescending<PendingMessageEntity> { it.priority }
                        .thenBy { it.triggerTime }
                        .thenBy { it.id }
                )
                .firstOrNull()

        override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }

        override suspend fun getByState(state: String): List<PendingMessageEntity> =
            items.filter { it.state == state }

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

    private val t0 = 1_000_000L

    @Test
    fun reschedule_pushes_message_out_of_due_window() = runBlocking {
        val dao = FakeDao()
        val queue = PendingMessageQueue(dao) { t0 }
        val id = queue.enqueue(PendingMessageType.INSTRUCTION, "instr:0:喝水", t0)
        assertTrue(queue.peekNext() != null)

        val later = t0 + 3_600_000L
        assertTrue(queue.reschedule(id, later))
        assertEquals(later, dao.items.first { it.id == id }.triggerTime)
        assertNull(queue.peekNext())                       // 已不在到点窗口
        assertEquals(1, queue.pendingCount())
        assertEquals(0, queue.availableCount())
    }

    @Test
    fun reschedule_rejects_non_pending_message() = runBlocking {
        val dao = FakeDao()
        val queue = PendingMessageQueue(dao) { t0 }
        val id = queue.enqueue(PendingMessageType.CHITCHAT, "text:x", t0)
        queue.claim(id) // → SENT
        assertFalse(queue.reschedule(id, t0 + 1000))
        assertEquals(t0, dao.items.first { it.id == id }.triggerTime) // 未被改
    }

    @Test
    fun reschedule_unknown_id_returns_false() = runBlocking {
        val queue = PendingMessageQueue(FakeDao()) { t0 }
        assertFalse(queue.reschedule(9999L, t0))
    }
}

