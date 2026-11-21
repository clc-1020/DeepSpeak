package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MemoryShortEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短期记忆容量淘汰策略单测（2026-11-20）。
 *
 * 这是**会删用户数据**的策略，每条用例都对应一种误删/漏删：
 *  - 没攒满就删 → 天天丢记忆；
 *  - 不保护新记忆 → 刚说的事转眼被清；
 *  - 不保护活跃任务 → 正在监督的任务记忆被删掉；
 *  - 排序错（按 fadeScore 升序） → 先删掉最新的，把最旧的留着。
 */
class ShortMemoryCapacityTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun mem(
        id: Long,
        content: String = "记忆$id",
        fadeScore: Double = id.toDouble(),
        ageDays: Long = 30,
        blacklisted: Boolean = false
    ) = MemoryShortEntity(
        id = id,
        content = content,
        timestamp = now - ageDays * day,
        fadeScore = fadeScore,
        blacklisted = blacklisted
    )

    @Test
    fun below_threshold_never_evicts() {
        val all = (1L..49L).map { mem(it) }
        assertTrue(ShortMemoryCapacity.plan(all, emptyList(), now).isEmpty())
    }

    @Test
    fun at_threshold_evicts_exactly_twenty_least_sticky() {
        // fadeScore 越大越模糊；id 越大越模糊 → 应淘汰 50..31（返回顺序也是按"最该忘"在前）
        val all = (1L..50L).map { mem(it) }
        val evicted = ShortMemoryCapacity.plan(all, emptyList(), now)

        assertEquals(20, evicted.size)
        assertEquals((50L downTo 31L).toList(), evicted)
    }

    @Test
    fun fresh_memories_are_protected_by_window() {
        // 全部 50 条都是 1 小时前写的 → 不删
        val all = (1L..50L).map { mem(it, ageDays = 0) }
        assertTrue(ShortMemoryCapacity.plan(all, emptyList(), now).isEmpty())
    }

    @Test
    fun active_task_memories_are_protected() {
        // 最模糊的一条恰好属于正在监督的任务 → 不能删
        val active = mem(99, content = "喝水，每隔1分钟提醒我一次", fadeScore = 999.0)
        val all = listOf(active) + (1L..49L).map { mem(it) }

        val evicted = ShortMemoryCapacity.plan(all, listOf("喝水"), now)

        assertTrue("活跃任务记忆不许删", evicted.none { it == 99L })
        assertEquals("总数 50 → 仍淘汰 20 条普通记忆", 20, evicted.size)
    }

    @Test
    fun tie_on_fade_score_evicts_older_first() {
        // 50 条同分，id 越小越旧（ageDays = 51 - id）→ 先淘汰 1..20
        val all = (1L..50L).map { mem(it, fadeScore = 1.0, ageDays = 51L - it) }
        val evicted = ShortMemoryCapacity.plan(all, emptyList(), now)

        assertEquals(20, evicted.size)
        assertEquals((1L..20L).toList(), evicted)
    }

    @Test
    fun blacklisted_memories_are_evictable() {
        // 拉黑项永不召回，只占地方 → 允许淘汰
        val bl = mem(500, fadeScore = 1000.0, blacklisted = true)
        val all = (1L..49L).map { mem(it) } + bl
        val evicted = ShortMemoryCapacity.plan(all, emptyList(), now)
        assertTrue("拉黑项应参与淘汰", evicted.contains(500L))
    }

    @Test
    fun everything_protected_evicts_nothing() {
        // 50 条全在保护窗口内 → 空计划（不要返回 0 条以外的意外结果）
        val all = (1L..50L).map { mem(it, ageDays = 0) }
        val evicted = ShortMemoryCapacity.plan(all, listOf("背单词"), now)
        assertTrue(evicted.isEmpty())
    }
}