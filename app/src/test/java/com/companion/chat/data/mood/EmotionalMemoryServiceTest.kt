package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.9.4.0：情绪记忆拟人化服务（写入合并 / pivotal / 淘汰 / 注入冷却 / 回忆与厌恶记账）。 */
class EmotionalMemoryServiceTest {

    private class FakeLong : MemoryLongDao {
        val rows = mutableListOf<MemoryLongEntity>()
        private var nextId = 1L
        override suspend fun insert(memory: MemoryLongEntity): Long {
            val id = nextId++
            rows += memory.copy(id = id)
            return id
        }
        override suspend fun update(memory: MemoryLongEntity) {
            val i = rows.indexOfFirst { it.id == memory.id }
            if (i >= 0) rows[i] = memory
        }
        override suspend fun delete(memory: MemoryLongEntity) { rows.removeAll { it.id == memory.id } }
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = rows.toList()
        override suspend fun getById(id: Long): MemoryLongEntity? = rows.firstOrNull { it.id == id }
        override suspend fun getByCategory(category: String): List<MemoryLongEntity> =
            rows.filter { it.category == category }.sortedByDescending { it.createTime }
        override suspend fun deleteAll() { rows.clear() }
    }

    private class FakeApp : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
        override suspend fun getAll(): List<AppStateEntity> = map.map { (k, v) -> AppStateEntity(k, v) }
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
    }

    private var nowMs = 1_800_000_000_000L
    private var day = "2026-11-22"

    private fun svc(long: FakeLong, app: FakeApp = FakeApp()) = EmotionalMemoryService(
        longDao = long, appStateDao = app, now = { nowMs }, today = { day }
    )

    @Test
    fun write_creates_emotional_row_with_intensity() = runBlocking {
        val long = FakeLong()
        assertTrue(svc(long).writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22"))
        val row = long.rows.single()
        assertEquals("relationship", row.category)
        assertEquals(0.7, row.intensity, 1e-9)
        assertFalse(row.pivotal)
        assertEquals(1, row.occurrenceCount)
    }

    @Test
    fun below_min_intensity_is_not_written() = runBlocking {
        val long = FakeLong()
        assertFalse(svc(long).writeOrMerge("happy", 0.59, "你夸我", "2026-11-22"))
        assertTrue(long.rows.isEmpty())
    }

    @Test
    fun blank_trigger_is_not_written() = runBlocking {
        val long = FakeLong()
        assertFalse(svc(long).writeOrMerge("happy", 0.9, "  ", "2026-11-22"))
        assertTrue(long.rows.isEmpty())
    }

    @Test
    fun high_intensity_becomes_pivotal() = runBlocking {
        val long = FakeLong()
        svc(long).writeOrMerge("happy", 0.9, "你连夸我三次", "2026-11-22")
        assertTrue(long.rows.single().pivotal)
    }

    @Test
    fun same_emotion_similar_trigger_merges_and_counts() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        s.writeOrMerge("happy", 0.8, "你夸我厉害", "2026-11-23")
        val row = long.rows.single()
        assertEquals(2, row.occurrenceCount)
        assertEquals(0.8, row.intensity, 1e-9)
        assertTrue("正文要带次数", row.content.contains("共2次"))
    }

    @Test
    fun same_emotion_stronger_pivotal_replaces_old() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("hurt", 0.86, "你说在测试我", "2026-11-22")
        val first = long.rows.single()
        assertTrue(first.pivotal)
        s.writeOrMerge("hurt", 0.95, "你又说在测试我", "2026-11-23")
        val pivots = long.rows.filter { it.pivotal }
        assertEquals("同情绪只留 1 条 pivotal", 1, pivots.size)
        assertEquals(0.95, pivots.single().intensity, 1e-9)
    }

    @Test
    fun normal_limit_evicts_lowest_score() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        // 5 条不同 trigger 的普通情绪记忆（强度 <0.85）
        repeat(5) { i -> s.writeOrMerge("happy", 0.6 + i * 0.01, "事件编号$i 具体内容", "2026-11-2$i") }
        assertEquals("普通上限 4 条", 4, long.rows.size)
    }

    @Test
    fun injectable_respects_normal_48h_cooldown() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        val id = long.rows.single().id
        // 首次可注入
        assertTrue(s.injectable("happy").any { it.first == id })
        s.recall(id)
        // 立刻再注入 → 48h 冷却挡住
        assertTrue("48h 内不可再注入", s.injectable("happy").none { it.first == id })
        // 49h 后可注入
        nowMs += 49L * 60 * 60 * 1000
        assertTrue(s.injectable("happy").any { it.first == id })
    }

    @Test
    fun pivotal_cooldown_requires_both_12h_and_new_day() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.9, "你连夸我三次", "2026-11-22")
        val id = long.rows.single().id
        s.recall(id)
        // 同日 + 刚注入 → 不够 12h 也同日，双重拦截
        assertTrue(s.injectable("happy").none { it.first == id })
        // 过 13h，但日期还没变 → 仍被"每日一次"挡住
        nowMs += 13L * 60 * 60 * 1000
        assertTrue("同日即使满 12h 也不再提", s.injectable("happy").none { it.first == id })
        // 换一天 → 放行
        day = "2026-11-23"
        assertTrue(s.injectable("happy").any { it.first == id })
    }

    @Test
    fun emotion_match_wins_over_non_matching() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.6, "你夸我厉害", "2026-11-22")
        s.writeOrMerge("hurt", 0.6, "你说在测试我", "2026-11-22")
        val happyId = long.rows.first { it.content.contains("开心") }.id
        val picked = s.injectable("happy", limit = 1)
        assertEquals("情绪匹配的优先", happyId, picked.single().first)
    }

    @Test
    fun dislike_cools_and_second_time_blocks_permanently() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        val id = long.rows.single().id
        s.recall(id)
        assertTrue("命中否定词要处理", s.onDislike("别提这个了"))
        val after = long.rows.single()
        assertEquals(1, after.dislikeCount)
        // 先 recall +0.05，再 dislike -0.1 → -0.05
        assertEquals(-0.05, after.boost, 1e-9)
        assertTrue("7 天冷却生效", after.coolingUntil > nowMs)
        assertTrue("冷却期内不注入", s.injectable("happy").none { it.first == id })

        // 冷却过期后再嫌一次 → 永久
        nowMs = after.coolingUntil + 1
        s.recall(id)
        assertTrue(s.onDislike("你怎么又提"))
        assertTrue("第二次永久不再注入", s.injectable("happy").none { it.first == id })
    }

    @Test
    fun non_negative_text_does_not_penalize() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        val id = long.rows.single().id
        s.recall(id)
        assertFalse(s.onDislike("今天天气不错"))
        assertEquals(0.05, long.rows.single().boost, 1e-9)
    }

    @Test
    fun recall_boosts_only_once_per_day() = runBlocking {
        val long = FakeLong()
        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        val id = long.rows.single().id
        s.recall(id)
        assertEquals(0.05, long.rows.single().boost, 1e-9)
        s.recall(id)
        assertEquals("同日第二次不再加分", 0.05, long.rows.single().boost, 1e-9)
        day = "2026-11-23"
        s.recall(id)
        assertEquals(0.10, long.rows.single().boost, 1e-9)
    }

    @Test
    fun dismiss_by_id_only_touches_emotional_rows() = runBlocking {
        val long = FakeLong()
        long.insert(MemoryLongEntity(content = "对花生过敏", category = "allergy", createTime = nowMs))
        val allergyId = long.rows.single().id
        assertFalse("非情绪记忆不处理", svc(long).dismissById(allergyId))

        val s = svc(long)
        s.writeOrMerge("happy", 0.7, "你夸我厉害", "2026-11-22")
        val emoId = long.rows.first { it.category == "relationship" }.id
        assertTrue(s.dismissById(emoId))
        assertEquals(1, long.rows.first { it.id == emoId }.dislikeCount)
    }

    @Test
    fun non_emotional_rows_never_become_injectable() = runBlocking {
        val long = FakeLong()
        long.insert(MemoryLongEntity(content = "对花生过敏", category = "allergy", createTime = nowMs))
        assertTrue(svc(long).injectable("happy").isEmpty())
    }
}