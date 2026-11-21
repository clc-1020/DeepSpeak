package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MemoryShortEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryRecallRanker 规则算分排序单测。任务 2.6 */
class MemoryRecallRankerTest {

    private val day = 86_400_000L

    private fun mem(
        id: Long,
        content: String,
        ts: Long,
        weight: Double = 1.0,
        fade: Double = 0.0,
        lastMentioned: Long? = null,
        coolingUntil: Long? = null,
        blacklisted: Boolean = false
    ) = MemoryShortEntity(
        id = id, content = content, timestamp = ts,
        weight = weight, fadeScore = fade, lastMentioned = lastMentioned,
        coolingUntil = coolingUntil, blacklisted = blacklisted
    )

    @Test
    fun newer_ranks_first_same_conditions() {
        val now = 100L * day
        val older = mem(1, "old", now - 20 * day)
        val newer = mem(2, "new", now - 1 * day)
        val r = MemoryRecallRanker.rank(listOf(older, newer), now)
        assertEquals(listOf(2L, 1L), r.map { it.id })
    }

    @Test
    fun long_unmentioned_climbs_via_bonus() {
        val now = 100L * day
        val recentMentioned = mem(1, "A", now - 1 * day, lastMentioned = now) // 新鲜高但刚提过（0 加分）
        val neverMentioned = mem(2, "B", now - 30 * day, lastMentioned = null) // 旧，但从未提过（满加分）
        val r = MemoryRecallRanker.rank(listOf(recentMentioned, neverMentioned), now)
        assertEquals(2L, r[0].id) // B 久未提及加分后反超
    }

    @Test
    fun faded_memory_filtered_out() {
        val now = 100L * day
        val faded = mem(1, "faded", now - 60 * day, fade = 10.0, lastMentioned = now)
        val good = mem(2, "good", now, fade = 0.0)
        val r = MemoryRecallRanker.rank(listOf(faded, good), now)
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun topk_limits_result() {
        val now = 100L * day
        val list = (1L..5L).map { mem(it, "m$it", now - it * day) }
        assertEquals(3, MemoryRecallRanker.rank(list, now, topK = 3).size)
    }

    // ---------- 任务 2.8：冷却 / 拉黑过滤 ----------

    @Test
    fun cooling_memory_is_skipped() {
        val now = 100L * day
        val cooling = mem(1, "cooling", now, coolingUntil = now + 10 * day) // 冷却中
        val normal = mem(2, "normal", now)
        val r = MemoryRecallRanker.rank(listOf(cooling, normal), now)
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun expired_cooling_is_recallable_again() {
        val now = 100L * day
        val recovered = mem(1, "recovered", now, coolingUntil = now - 1 * day) // 冷却已过
        val r = MemoryRecallRanker.rank(listOf(recovered), now)
        assertEquals(listOf(1L), r.map { it.id })
    }

    @Test
    fun blacklisted_memory_is_always_skipped() {
        val now = 100L * day
        val banned = mem(1, "banned", now, blacklisted = true)
        val normal = mem(2, "normal", now)
        val r = MemoryRecallRanker.rank(listOf(banned, normal), now)
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun all_suppressed_yields_empty() {
        val now = 100L * day
        val cooling = mem(1, "cooling", now, coolingUntil = now + day)
        val banned = mem(2, "banned", now, blacklisted = true)
        assertTrue(MemoryRecallRanker.rank(listOf(cooling, banned), now).isEmpty())
    }
}
