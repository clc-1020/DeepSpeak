package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryScoring 召回打分单测。任务 2.5 / 2.6 */
class MemoryScoringTest {

    private val day = 86_400_000L

    @Test
    fun freshness_now_is_one() {
        assertEquals(1.0, MemoryScoring.freshness(0L, 0L), 1e-9)
    }

    @Test
    fun freshness_half_life_is_half() {
        assertEquals(0.5, MemoryScoring.freshnessByAgeDays(7.0, 7.0), 1e-9)
        assertEquals(0.25, MemoryScoring.freshnessByAgeDays(14.0, 7.0), 1e-9)
    }

    @Test
    fun freshness_future_is_one() {
        assertEquals(1.0, MemoryScoring.freshness(1000L, 0L), 1e-9)
    }

    @Test
    fun score_formula() {
        assertEquals(0.3, MemoryScoring.score(1.0, 0.8, 0.5), 1e-9)
    }

    @Test
    fun newer_has_higher_score() {
        val now = 100L * day
        val fresh = MemoryScoring.scoreOf(1.0, now, 0.0, now)
        val old = MemoryScoring.scoreOf(1.0, now - 10 * day, 0.0, now)
        assertTrue(fresh > old)
    }

    @Test
    fun thirty_day_old_low_weight_not_recallable() {
        val now = 1000L * day
        // 30 天前、fadeScore=3.0（30×0.1）：score≈1×0.051−3.0<0
        val score = MemoryScoring.scoreOf(1.0, now - 30 * day, 3.0, now)
        assertTrue(score < 0)
        assertFalse(MemoryScoring.isRecallable(score))
    }

    @Test
    fun thirty_day_old_high_weight_recallable() {
        val now = 1000L * day
        // 权重极高（100）时即使很旧也能召回到
        val score = MemoryScoring.scoreOf(100.0, now - 30 * day, 3.0, now)
        assertTrue(score > 0)
        assertTrue(MemoryScoring.isRecallable(score))
    }

    @Test
    fun topk_excludes_nonrecallable_and_sorts_desc() {
        val top = MemoryScoring.topK(listOf(1L to 0.9, 2L to -1.0, 3L to 2.0, 4L to 0.1), 2)
        assertEquals(listOf(3L, 1L), top)
    }

    // ---------- 任务 2.6：久未提及加分 ----------

    @Test
    fun days_since_mentioned_null_is_cap() {
        assertEquals(MemoryScoring.RECENCY_BOOST_CAP_DAYS, MemoryScoring.daysSinceMentioned(null, 1000L), 1e-9)
    }

    @Test
    fun days_since_mentioned_capped() {
        val now = 100L * day
        assertEquals(MemoryScoring.RECENCY_BOOST_CAP_DAYS, MemoryScoring.daysSinceMentioned(now - 200 * day, now), 1e-9)
    }

    @Test
    fun days_since_mentioned_zero_for_future() {
        val now = 100L * day
        assertEquals(0.0, MemoryScoring.daysSinceMentioned(now + 5 * day, now), 1e-9)
    }

    @Test
    fun recall_score_boosts_long_unmentioned() {
        val a = MemoryScoring.recallScore(1.0, 0.5, 0.0, 0.0)
        val b = MemoryScoring.recallScore(1.0, 0.5, 0.0, 30.0)
        assertTrue(b > a)
    }
}
