package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryFeedbackService 三态落库单测（假 DAO，控制 now + log 记录器）。任务 2.8 */
class MemoryFeedbackServiceTest {

    private class FakeShort : MemoryShortDao {
        val items = ArrayList<MemoryShortEntity>()
        override suspend fun insert(memory: MemoryShortEntity): Long { items.add(memory); return items.size.toLong() }
        override suspend fun update(memory: MemoryShortEntity) {
            val i = items.indexOfFirst { it.id == memory.id }; if (i >= 0) items[i] = memory
        }
        override suspend fun delete(memory: MemoryShortEntity) { items.removeAll { it.id == memory.id } }
        override fun observeAll(): Flow<List<MemoryShortEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryShortEntity> = items.toList()
        override suspend fun getById(id: Long): MemoryShortEntity? = items.firstOrNull { it.id == id }
        override suspend fun updateWeight(id: Long, weight: Double) {
            val i = items.indexOfFirst { it.id == id }; if (i >= 0) items[i] = items[i].copy(weight = weight)
        }
        override suspend fun updateFadeScore(id: Long, fadeScore: Double) {}
        override suspend fun incrementAllFadeScore(increment: Double) {}
        override suspend fun count(): Int = items.size
        override suspend fun deleteById(id: Long) { items.removeAll { it.id == id } }

        override suspend fun findLatestContaining(keyword: String): MemoryShortEntity? =
            items.lastOrNull { it.content.contains(keyword) }
        override suspend fun updateLastMentioned(id: Long, time: Long) {}
        override suspend fun applyFeedback(id: Long, weight: Double, dismissCount: Int, coolingUntil: Long?, blacklisted: Boolean) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(
                weight = weight, dismissCount = dismissCount, coolingUntil = coolingUntil, blacklisted = blacklisted
            )
        }
        override suspend fun deleteAll() { items.clear() }
    }

    private val day = 86_400_000L

    private fun mem(id: Long, weight: Double = 1.0) =
        MemoryShortEntity(id = id, content = "m$id", timestamp = 0L, weight = weight)

    // 单测里不接 Log（JVM 默认 android.util.Log 会抛 "not mocked"），统一传空日志。
    private fun svc(s: FakeShort, nowMillis: Long, logs: MutableList<String>? = null) =
        MemoryFeedbackService(
            shortDao = s,
            now = { nowMillis },
            log = { if (logs != null) logs.add(it) }
        )

    @Test
    fun reply_increases_weight() = runBlocking {
        val s = FakeShort(); s.items.add(mem(1, 1.0))
        svc(s, 1000L).onReply(1L)
        assertEquals(1.2, s.items.first { it.id == 1L }.weight, 1e-9)
    }

    @Test
    fun ignore_does_not_touch_memory_but_logs() = runBlocking {
        val s = FakeShort(); s.items.add(mem(1, 1.0))
        val logs = mutableListOf<String>()
        svc(s, 1000L, logs).onIgnore(1L)
        assertEquals(1.0, s.items.first { it.id == 1L }.weight, 1e-9)
        assertNull(s.items.first { it.id == 1L }.coolingUntil)
        assertFalse(s.items.first { it.id == 1L }.blacklisted)
        // 断言「忽略」确实被调用到（Claude 建议③：空方法无法确认被调用）
        assertTrue(logs.any { it.contains("忽略") && it.contains("1") })
    }

    @Test
    fun first_dismiss_sets_cooling_30_days() = runBlocking {
        val now = 1000L * day
        val s = FakeShort(); s.items.add(mem(1, 1.0))
        val blacklisted = svc(s, now).onDismiss(1L)
        assertFalse(blacklisted)
        val m = s.items.first { it.id == 1L }
        assertEquals(0.5, m.weight, 1e-9)
        assertEquals(1, m.dismissCount)
        assertEquals(now + 30 * day, m.coolingUntil)
        assertFalse(m.blacklisted)
    }

    @Test
    fun second_dismiss_blacklists() = runBlocking {
        val now = 2000L * day
        // 已经点过一次（冷却已过，可以再点）
        val s = FakeShort()
        s.items.add(MemoryShortEntity(id = 1, content = "m1", timestamp = 0L, weight = 0.5, dismissCount = 1))
        val blacklisted = svc(s, now).onDismiss(1L)
        assertTrue(blacklisted)
        val m = s.items.first { it.id == 1L }
        assertEquals(2, m.dismissCount)
        assertTrue(m.blacklisted)
        assertEquals(0.0, m.weight, 1e-9)
    }

    @Test
    fun dismiss_missing_memory_is_noop() = runBlocking {
        val s = FakeShort()
        assertFalse(svc(s, 1000L).onDismiss(999L))
        // 不崩、不新增
        assertTrue(s.items.isEmpty())
    }
}
