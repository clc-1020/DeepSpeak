package com.companion.chat.domain.emotion

import org.junit.Assert.*
import org.junit.Test

/** 情绪记忆打分 / 冷却 / boost 纯函数测试（1.9.4.0）。 */
class EmotionalMemoryScoreTest {

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
        private const val NOW = 1_700_000_000_000L
        private const val TODAY = "2026-11-22"
        private const val YESTERDAY = "2026-11-21"
    }

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = kotlin.math.abs(a - b) < eps

    private fun inject(
        pivotal: Boolean,
        lastAt: Long,
        lastDay: String?,
        today: String = TODAY,
        now: Long = NOW,
        blacklisted: Boolean = false,
        coolingUntil: Long = 0L,
        dislikeCount: Int = 0
    ) = EmotionalMemoryScore.canInject(
        pivotal, lastAt, lastDay, today, now, blacklisted, coolingUntil, dislikeCount
    )

    // ---------------- score：衰减 ----------------

    @Test
    fun score_day_zero_is_intensity_plus_boost() {
        // 0 天：不衰减，等于 clamp(intensity + boost)
        assertTrue(near(EmotionalMemoryScore.score(0.5, 0.1, NOW, NOW), 0.6))
    }

    @Test
    fun score_30_days_halves() {
        val v = EmotionalMemoryScore.score(0.6, 0.0, NOW - 30 * DAY, NOW)
        assertTrue("30 天应减半≈0.3，实际 $v", near(v, 0.3))
    }

    @Test
    fun score_60_days_is_quarter() {
        val v = EmotionalMemoryScore.score(0.6, 0.0, NOW - 60 * DAY, NOW)
        assertTrue("60 天应剩四分之一≈0.15，实际 $v", near(v, 0.15))
    }

    @Test
    fun score_clamps_to_zero_after_long_time() {
        val v = EmotionalMemoryScore.score(0.6, 0.0, NOW - 10_000 * DAY, NOW)
        assertTrue("很久之后应≈0，实际 $v", near(v, 0.0, 1e-9))
        assertEquals(0.0, v, 1e-9)
    }

    @Test
    fun score_clock_rollback_does_not_decay() {
        // createTime 在未来（时钟回拨）：daysAgo=0，不衰减
        val v = EmotionalMemoryScore.score(0.6, 0.0, NOW + 30 * DAY, NOW)
        assertTrue("时钟回拨不减分，应≈0.6，实际 $v", near(v, 0.6))
    }

    @Test
    fun score_sanitizes_nan_and_negative_intensity() {
        assertEquals(0.0, EmotionalMemoryScore.score(Double.NaN, Double.NaN, NOW, NOW), 0.0)
        assertEquals(0.0, EmotionalMemoryScore.score(-0.5, 0.0, NOW, NOW), 0.0)
    }

    // ---------------- boost ----------------

    @Test
    fun boost_single_step() {
        assertTrue(near(EmotionalMemoryScore.applyBoost(0.0), 0.05))
    }

    @Test
    fun boost_caps_at_point_two() {
        assertEquals(EmotionalMemoryScore.BOOST_CAP, EmotionalMemoryScore.applyBoost(1.0), 0.0)
        assertEquals(EmotionalMemoryScore.BOOST_CAP, EmotionalMemoryScore.applyBoost(0.18), 0.0)
    }

    @Test
    fun boost_from_negative() {
        assertTrue(near(EmotionalMemoryScore.applyBoost(-0.1), -0.05))
    }

    @Test
    fun boost_nan_as_zero() {
        // NaN 按 0 处理，再加一步 → 0.05
        assertTrue(near(EmotionalMemoryScore.applyBoost(Double.NaN), 0.05))
    }

    // ---------------- dislike ----------------

    @Test
    fun dislike_single_step() {
        assertTrue(near(EmotionalMemoryScore.applyDislike(0.0), -0.1))
    }

    @Test
    fun dislike_floors_at_minus_point_two() {
        assertEquals(-EmotionalMemoryScore.BOOST_CAP, EmotionalMemoryScore.applyDislike(-1.0), 0.0)
        assertEquals(-EmotionalMemoryScore.BOOST_CAP, EmotionalMemoryScore.applyDislike(-0.15), 0.0)
    }

    @Test
    fun dislike_nan_as_zero() {
        // NaN 按 0 处理，再减一步 → -0.1
        assertTrue(near(EmotionalMemoryScore.applyDislike(Double.NaN), -0.1))
    }

    // ---------------- pivotal 冷却 ----------------

    @Test
    fun pivotal_same_day_blocks() {
        // 同日且只隔 1h：日期与时间两条都不满足
        assertFalse(inject(true, NOW - 1 * HOUR, TODAY))
    }

    @Test
    fun pivotal_12h_but_same_day_blocks() {
        // 隔 12h 时间满足，但仍是同一天 → 两条必须同时满足，故不可
        assertFalse(inject(true, NOW - 12 * HOUR, TODAY))
    }

    @Test
    fun pivotal_next_day_after_12h_passes() {
        // 隔日且 >=12h（13h）→ 允许
        assertTrue(inject(true, NOW - 13 * HOUR, YESTERDAY))
    }

    @Test
    fun pivotal_never_injected_passes() {
        assertTrue(inject(true, 0L, null))
        assertTrue(inject(true, -1L, null))
    }

    @Test
    fun pivotal_clock_rollback_passes() {
        // 上次注入时间在未来 → 时钟回拨视为通过（即便是同一天）
        assertTrue(inject(true, NOW + HOUR, TODAY))
    }

    // ---------------- 非 pivotal ----------------

    @Test
    fun normal_47h_blocks() {
        assertFalse(inject(false, NOW - 47 * HOUR, YESTERDAY))
    }

    @Test
    fun normal_49h_passes() {
        assertTrue(inject(false, NOW - 49 * HOUR, YESTERDAY))
    }

    @Test
    fun normal_same_day_49h_passes() {
        // 非 pivotal 不看日期：同日但 49h 也允许
        assertTrue(inject(false, NOW - 49 * HOUR, TODAY))
    }

    @Test
    fun blacklisted_blocks() {
        assertFalse(inject(false, 0L, null, blacklisted = true))
        assertFalse(inject(true, 0L, null, blacklisted = true))
    }

    @Test
    fun cooling_until_not_reached_blocks() {
        assertFalse(inject(false, 0L, null, coolingUntil = NOW + HOUR))
        // 已过冷却时间则放行
        assertTrue(inject(false, 0L, null, coolingUntil = NOW - HOUR))
    }

    // ---------------- 否定词表 ----------------

    @Test
    fun negative_words_all_hit() {
        for (w in listOf("别提", "不想提", "没说过", "你怎么又", "烦不烦", "别念叨", "别说了", "别再提")) {
            assertTrue("应命中「$w」", EmotionalMemoryScore.isNegativeUserText("我$w 这件事"))
        }
    }

    @Test
    fun normal_sentence_is_not_negative() {
        assertFalse(EmotionalMemoryScore.isNegativeUserText("我们聊聊今天开心的事"))
        assertFalse(EmotionalMemoryScore.isNegativeUserText("别 提")) // 不做推断，带空格不算命中
    }

    @Test
    fun empty_or_blank_is_not_negative() {
        assertFalse(EmotionalMemoryScore.isNegativeUserText(""))
        assertFalse(EmotionalMemoryScore.isNegativeUserText("   "))
    }

    // ---------------- mergeText ----------------

    @Test
    fun merge_once_returns_trimmed_base() {
        assertEquals("你好", EmotionalMemoryScore.mergeText("  你好  ", 1))
        assertEquals("你好", EmotionalMemoryScore.mergeText("  你好  ", 0))
    }

    @Test
    fun merge_three_times_appends_count() {
        assertEquals(
            "你好。（你不止一次这样，共3次）",
            EmotionalMemoryScore.mergeText("你好。", 3)
        )
        // 句尾「、」也去掉
        assertEquals(
            "你好。（你不止一次这样，共2次）",
            EmotionalMemoryScore.mergeText("你好、", 2)
        )
    }

    // ---------------- rankForInjection ----------------

    @Test
    fun rank_by_similarity_desc() {
        val ids = EmotionalMemoryScore.rankForInjection(
            listOf(
                Triple(1L, 0.9f, 0.0),
                Triple(2L, 0.5f, 0.0),
                Triple(3L, 0.7f, 0.0)
            )
        )
        assertEquals(listOf(1L, 3L, 2L), ids)
    }

    @Test
    fun rank_tie_breaks_by_boost_desc() {
        val ids = EmotionalMemoryScore.rankForInjection(
            listOf(
                Triple(1L, 0.8f, 0.05),
                Triple(2L, 0.8f, 0.15),
                Triple(3L, 0.8f, 0.10)
            )
        )
        assertEquals(listOf(2L, 3L, 1L), ids)
    }

    @Test
    fun rank_nan_similarity_goes_last() {
        val ids = EmotionalMemoryScore.rankForInjection(
            listOf(
                Triple(1L, Float.NaN, 0.2),
                Triple(2L, 0.3f, 0.0),
                Triple(3L, 0.6f, 0.0)
            )
        )
        assertEquals(listOf(3L, 2L, 1L), ids)
    }
}
