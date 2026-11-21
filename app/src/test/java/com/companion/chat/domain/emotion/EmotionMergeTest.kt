package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪合并 + 护栏（P1）。
 *
 * 最关键的一条是「**取大不覆盖**」：模型偶尔报个 0.3 的开心，不能把她刚攒起来的 0.7 委屈抹掉。
 */
class EmotionMergeTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = kotlin.math.abs(a - b) < eps

    @Test
    fun takes_the_larger_of_old_and_reported() {
        val old = mapOf(Emotion.HURT to 0.6)
        // 模型报更小 → 保持 0.6
        assertEquals(0.6, EmotionMerge.merge(old, mapOf(Emotion.HURT to 0.4), true)[Emotion.HURT]!!, 1e-6)
        // 模型报更大 → 升到 0.8
        assertEquals(0.8, EmotionMerge.merge(old, mapOf(Emotion.HURT to 0.8), true)[Emotion.HURT]!!, 1e-6)
    }

    @Test
    fun unreported_emotions_keep_their_decayed_value() {
        val old = mapOf(Emotion.HAPPY to 0.5, Emotion.HURT to 0.5)
        val out = EmotionMerge.merge(old, mapOf(Emotion.PROUD to 0.6), true)
        assertEquals(0.5, out[Emotion.HAPPY]!!, 1e-6)
        assertEquals(0.5, out[Emotion.HURT]!!, 1e-6)
        assertEquals(0.6, out[Emotion.PROUD]!!, 1e-6)
    }

    @Test
    fun unknown_keys_are_dropped_and_single_value_is_capped() {
        val out = EmotionMerge.merge(emptyMap(), mapOf("jealous" to 0.8, Emotion.ANNOYED to 1.4), true)
        assertEquals(1, out.size)
        assertEquals(0.9, out[Emotion.ANNOYED]!!, 1e-6) // clamp 到 MAX_SINGLE
    }

    @Test
    fun negative_total_is_shrunk_to_limit() {
        // hurt 0.9 + annoyed 0.5 = 1.4 → 按比例缩到 0.9
        val out = EmotionMerge.merge(
            emptyMap(),
            mapOf(Emotion.HURT to 0.9, Emotion.ANNOYED to 0.5),
            true
        )
        val total = out.values.sum()
        assertEquals(EmotionMerge.MAX_NEGATIVE_TOTAL, total, 1e-6)
        assertTrue(near(out[Emotion.HURT]!!, 0.9 * 0.9 / 1.4, 1e-6))
        assertTrue(near(out[Emotion.ANNOYED]!!, 0.5 * 0.9 / 1.4, 1e-6))
    }

    @Test
    fun negative_without_trigger_is_discarded_but_positive_survives() {
        val out = EmotionMerge.merge(
            emptyMap(),
            mapOf(Emotion.HURT to 0.7, Emotion.HAPPY to 0.6),
            hasTrigger = false
        )
        assertTrue("负面必须有 trigger 才采信", out.keys.none { it in Emotion.NEGATIVE })
        assertEquals(0.6, out[Emotion.HAPPY]!!, 1e-6)
    }

    @Test
    fun tiny_values_are_cleaned_out() {
        // 0.1 < MIN_ALIVE(0.2) → 视为没有这个情绪
        assertTrue(EmotionMerge.merge(emptyMap(), mapOf(Emotion.PROUD to 0.1), true).isEmpty())
    }

    @Test
    fun trigger_is_trimmed_to_limit() {
        val long = "你".repeat(40)
        val t = EmotionMerge.trimTrigger(long)
        assertEquals(EmotionMerge.TRIGGER_MAX_CHARS + 1, t.length) // 24 字 + 「…」
        assertTrue(t.endsWith("…"))
    }
}