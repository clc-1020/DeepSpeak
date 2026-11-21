package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.memory.ScoredMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MemoryRecallService 召回链路单测（假 DAO + 假 selector）。任务 2.6 */
class MemoryRecallServiceTest {

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
        override suspend fun updateWeight(id: Long, weight: Double) {}
        override suspend fun updateFadeScore(id: Long, fadeScore: Double) {}
        override suspend fun incrementAllFadeScore(increment: Double) {}
        override suspend fun count(): Int = items.size
        override suspend fun deleteById(id: Long) { items.removeAll { it.id == id } }

        override suspend fun findLatestContaining(keyword: String): MemoryShortEntity? =
            items.lastOrNull { it.content.contains(keyword) }
        override suspend fun updateLastMentioned(id: Long, time: Long) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(lastMentioned = time)
        }
        override suspend fun applyFeedback(id: Long, weight: Double, dismissCount: Int, coolingUntil: Long?, blacklisted: Boolean) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(weight = weight, dismissCount = dismissCount, coolingUntil = coolingUntil, blacklisted = blacklisted)
        }
        override suspend fun deleteAll() { items.clear() }
    }

    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "", model = "")

    private fun mem(id: Long, content: String) =
        MemoryShortEntity(id = id, content = content, timestamp = 1000L, weight = 1.0, fadeScore = 0.0)

    @Test
    fun returns_selector_choice_and_updates_last_mentioned() = runBlocking {
        val s = FakeShort(); s.items.add(mem(1, "A")); s.items.add(mem(2, "B"))
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, cands -> cands[1].id }, // 选第 2 条
            now = { 1000L }
        )
        assertEquals(2L, svc.recall(cfg))
        assertEquals(1000L, s.items.first { it.id == 2L }.lastMentioned)
        assertNull(s.items.first { it.id == 1L }.lastMentioned)
    }

    @Test
    fun single_candidate_bypasses_selector() = runBlocking {
        val s = FakeShort(); s.items.add(mem(7, "only"))
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, _ -> throw AssertionError("单条候选不应调用选择器") },
            now = { 2000L }
        )
        assertEquals(7L, svc.recall(cfg))
        assertEquals(2000L, s.items[0].lastMentioned)
    }

    @Test
    fun empty_candidates_returns_null() = runBlocking {
        val s = FakeShort()
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, _ -> throw AssertionError("无候选不应调用选择器") },
            now = { 1000L }
        )
        assertNull(svc.recall(cfg))
    }

    @Test
    fun selector_null_returns_null_without_update() = runBlocking {
        val s = FakeShort(); s.items.add(mem(1, "A")); s.items.add(mem(2, "B"))
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, _ -> null }, // 模型认为都不合适
            now = { 1000L }
        )
        assertNull(svc.recall(cfg))
        assertNull(s.items[0].lastMentioned)
        assertNull(s.items[1].lastMentioned)
    }

    // ---------- 任务 2.8：冷却 / 拉黑记忆不进候选 ----------

    @Test
    fun blacklisted_memory_not_recalled() = runBlocking {
        val s = FakeShort()
        s.items.add(MemoryShortEntity(id = 1, content = "banned", timestamp = 1000L, blacklisted = true))
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, _ -> throw AssertionError("拉黑记忆不应进入候选") },
            now = { 1000L }
        )
        assertNull(svc.recall(cfg))
    }

    @Test
    fun cooling_memory_not_recalled() = runBlocking {
        val s = FakeShort()
        s.items.add(
            MemoryShortEntity(id = 1, content = "cooling", timestamp = 1000L, coolingUntil = 5000L)
        )
        val svc = MemoryRecallService(
            shortDao = s,
            selector = { _, _ -> throw AssertionError("冷却中记忆不应进入候选") },
            now = { 1000L } // 1000 < 5000 → 仍冷却
        )
        assertNull(svc.recall(cfg))
    }
}
