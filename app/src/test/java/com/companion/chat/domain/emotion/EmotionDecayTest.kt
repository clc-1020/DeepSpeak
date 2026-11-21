package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 情绪衰减（P1）。核心断言：半小时减半、低于 0.2 消失、逐项独立。 */
class EmotionDecayTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = kotlin.math.abs(a - b) < eps

    @Test
    fun thirty_minutes_halves() {
        val out = EmotionDecay.apply(mapOf(Emotion.HURT to 0.8), 30.0)
        assertTrue("0.8 半小时后应≈0.4，实际 ${out[Emotion.HURT]}", near(out[Emotion.HURT]!!, 0.4))
    }

    @Test
    fun fifty_nine_minutes_is_about_a_quarter() {
        val out = EmotionDecay.apply(mapOf(Emotion.HAPPY to 1.0), 59.0)
        assertTrue(near(out[Emotion.HAPPY]!!, 0.256, 0.01))
    }

    @Test
    fun below_min_alive_is_removed() {
        // 0.3 经过 60 分钟 → 0.075 < 0.2 → 整个键消失（= 回落到平静）
        assertTrue(EmotionDecay.apply(mapOf(Emotion.LOW to 0.3), 60.0).isEmpty())
    }

    @Test
    fun each_emotion_decays_independently() {
        val out = EmotionDecay.apply(
            mapOf(Emotion.HAPPY to 0.5, Emotion.PROUD to 0.5),
            30.0
        )
        assertEquals(2, out.size)
        assertTrue(near(out[Emotion.HAPPY]!!, 0.25))
        assertTrue(near(out[Emotion.PROUD]!!, 0.25))
    }

    @Test
    fun empty_or_non_positive_minutes_are_noop() {
        val m = mapOf(Emotion.HURT to 0.6)
        assertEquals(m, EmotionDecay.apply(m, 0.0))
        assertEquals(m, EmotionDecay.apply(m, -5.0))
        assertEquals(m, EmotionDecay.apply(m, Double.NaN))
        assertTrue(EmotionDecay.apply(emptyMap(), 100.0).isEmpty())
    }
}