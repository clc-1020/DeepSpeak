package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ProactiveTestTrigger 测试模式触发单测。任务 3.6 */
class ProactiveTestTriggerTest {

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
        override suspend fun countAvailable(now: Long): Int = 0
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

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMs = LocalDate.of(2026, 11, 20).atTime(LocalTime.of(20, 30))
        .atZone(zone).toInstant().toEpochMilli()

    private fun trigger(
        dao: FakeDao,
        recall: suspend () -> Long?,
        deliveredId: MutableList<Long> = mutableListOf(),
        outcome: ProactiveDeliveryOutcome = ProactiveDeliveryOutcome.DELIVERED
    ) = ProactiveTestTrigger(
        queue = PendingMessageQueue(dao, now = { nowMs }),
        deliverById = { id -> deliveredId.add(id); outcome },
        recallHook = recall,
        now = { nowMs },
        zone = zone
    )

    @Test
    fun no_memory_uses_time_hook_and_delivers_by_new_id() = runBlocking {
        val dao = FakeDao()
        val delivered = mutableListOf<Long>()
        val ok = trigger(dao, recall = { null }, deliveredId = delivered).triggerNow()
        assertTrue(ok)
        val msg = dao.items.single()
        assertEquals(PendingMessageType.CHITCHAT.code, msg.type)
        val decoded = PendingMessageContent.decode(msg.content)
        assertTrue(decoded is PendingMessageContent.PlainText)
        assertTrue((decoded as PendingMessageContent.PlainText).text.contains("现在"))
        assertTrue((decoded.text.contains("20点") || decoded.text.contains("20点30分")))
        assertEquals(listOf(msg.id), delivered)
    }

    @Test
    fun memory_hook_uses_memory_content() = runBlocking {
        val dao = FakeDao()
        val delivered = mutableListOf<Long>()
        trigger(dao, recall = { 42L }, deliveredId = delivered).triggerNow()
        val msg = dao.items.single()
        assertEquals("memory:42", msg.content)
        assertEquals(listOf(msg.id), delivered)
    }

    @Test
    fun generation_failure_returns_false() = runBlocking {
        val dao = FakeDao()
        val ok = trigger(dao, recall = { 1L }, outcome = ProactiveDeliveryOutcome.GENERATION_FAILED).triggerNow()
        assertFalse(ok)
    }

    @Test
    fun recall_exception_falls_back_to_time_hook() = runBlocking {
        val dao = FakeDao()
        val ok = trigger(dao, recall = { throw IllegalStateException("boom") }).triggerNow()
        assertTrue(ok)
        val decoded = PendingMessageContent.decode(dao.items.single().content)
        assertTrue(decoded is PendingMessageContent.PlainText)
    }
}

