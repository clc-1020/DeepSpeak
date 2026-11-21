package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** MemoryDecayService 单测（假 DAO，控制「今天」）。任务 2.5 */
class MemoryDecayServiceTest {

    private class FakeShort : MemoryShortDao {
        val items = ArrayList<MemoryShortEntity>()
        override suspend fun insert(memory: MemoryShortEntity): Long { items.add(memory); return items.size.toLong() }
        override suspend fun update(memory: MemoryShortEntity) {}
        override suspend fun delete(memory: MemoryShortEntity) { items.remove(memory) }
        override fun observeAll(): Flow<List<MemoryShortEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryShortEntity> = items.toList()
        override suspend fun getById(id: Long): MemoryShortEntity? = items.firstOrNull { it.id == id }
        override suspend fun updateWeight(id: Long, weight: Double) {}
        override suspend fun updateFadeScore(id: Long, fadeScore: Double) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(fadeScore = fadeScore)
        }
        override suspend fun incrementAllFadeScore(increment: Double) {
            for (i in items.indices) items[i] = items[i].copy(fadeScore = items[i].fadeScore + increment)
        }
        override suspend fun count(): Int = items.size
        override suspend fun deleteById(id: Long) { items.removeAll { it.id == id } }

        override suspend fun findLatestContaining(keyword: String): MemoryShortEntity? =
            items.lastOrNull { it.content.contains(keyword) }
        override suspend fun updateLastMentioned(id: Long, time: Long) {}
        override suspend fun applyFeedback(id: Long, weight: Double, dismissCount: Int, coolingUntil: Long?, blacklisted: Boolean) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(weight = weight, dismissCount = dismissCount, coolingUntil = coolingUntil, blacklisted = blacklisted)
        }
        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
        override suspend fun getAll(): List<AppStateEntity> = map.map { (k, v) -> AppStateEntity(k, v) }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
    }

    private fun seed(short: FakeShort, fade: Double, id: Long = 1L) {
        short.items.add(MemoryShortEntity(id = id, content = "记忆$id", timestamp = 0L, fadeScore = fade))
    }

    @Test
    fun first_run_only_initializes_cursor_no_decay() = runBlocking {
        val s = FakeShort(); val a = FakeAppState(); seed(s, 0.5)
        val svc = MemoryDecayService(s, a, today = { LocalDate.of(2026, 1, 1) })
        assertEquals(0, svc.runOnce())
        assertEquals(0.5, s.items[0].fadeScore, 1e-9) // 未衰减
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay().toString(), a.map[AppStateEntity.KEY_LAST_DECAY])
    }

    @Test
    fun decays_after_elapsed_days() = runBlocking {
        val s = FakeShort(); val a = FakeAppState(); seed(s, 0.5)
        var today = LocalDate.of(2026, 1, 1)
        val svc = MemoryDecayService(s, a, today = { today })
        svc.runOnce() // init cursor at day0
        today = LocalDate.of(2026, 1, 4) // +3 天
        assertEquals(1, svc.runOnce())
        assertEquals(0.8, s.items[0].fadeScore, 1e-9) // 0.5 + 3×0.1
        assertEquals(LocalDate.of(2026, 1, 4).toEpochDay().toString(), a.map[AppStateEntity.KEY_LAST_DECAY])
    }

    @Test
    fun same_day_is_idempotent() = runBlocking {
        val s = FakeShort(); val a = FakeAppState(); seed(s, 0.0)
        val today = LocalDate.of(2026, 1, 1)
        val svc = MemoryDecayService(s, a, today = { today })
        svc.runOnce()                     // init 游标 = 今天
        assertEquals(0, svc.runOnce())    // 同一天再跑 → 幂等
        assertEquals(0.0, s.items[0].fadeScore, 1e-9)
    }

    @Test
    fun never_deletes_memories() = runBlocking {
        val s = FakeShort(); val a = FakeAppState()
        seed(s, 0.0, 1L); seed(s, 0.0, 2L)
        var today = LocalDate.of(2026, 1, 1)
        val svc = MemoryDecayService(s, a, today = { today })
        svc.runOnce()
        today = LocalDate.of(2026, 3, 1) // 很久以后
        svc.runOnce()
        assertEquals(2, s.items.size) // 仍在库（可查看），只是 fadeScore 很高
        assertEquals(5.9, s.items[0].fadeScore, 1e-6) // (3月1-1月1)=59 天 ×0.1
    }

    @Test
    fun decay_runs_inside_transaction() = runBlocking {
        val s = FakeShort(); val a = FakeAppState(); seed(s, 0.0)
        var today = LocalDate.of(2026, 1, 1)
        var txCalls = 0
        val svc = MemoryDecayService(
            shortDao = s, appStateDao = a,
            today = { today },
            runInTransaction = { block -> txCalls++; block() }
        )
        svc.runOnce()                     // init 游标（不衰减、不开事务）
        assertEquals(0, txCalls)
        today = LocalDate.of(2026, 1, 3)  // +2 天 → 触发衰减并开事务
        svc.runOnce()
        assertEquals(1, txCalls)
    }
}
