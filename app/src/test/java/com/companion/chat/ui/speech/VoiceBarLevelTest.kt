package com.companion.chat.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波形拉伸纯函数单测（V2.3.4 契约 3）。
 *
 * AudioRecord 本身无法 JVM 测，但「线性 RMS 画出来贴底不动」这个坑是纯数学问题，
 * 用数值把结论钉住：正常说话 3–9% 必须拉到 0.24–0.36 的可见高度。
 */
class VoiceBarLevelTest {

    private val delta = 1e-4f

    @Test
    fun silence_keeps_floor_not_zero() {
        // 静音时留 8% 高度，波形不塌成一条线
        assertEquals(0.08f, voiceBarLevel(0), delta)
    }

    @Test
    fun normal_speech_range_is_stretched_visibly() {
        // 正常说话 RMS 映射后 3–9%；线性只有 0.03–0.09，拉伸后必须 > 0.2
        assertEquals(0.2394f, voiceBarLevel(3), delta)
        assertEquals(0.3560f, voiceBarLevel(9), delta)
        assertTrue(voiceBarLevel(3) > 0.2f)
        assertTrue(voiceBarLevel(9) > 0.3f)
    }

    @Test
    fun full_scale_reaches_one() {
        assertEquals(1f, voiceBarLevel(100), delta)
        assertEquals(0.7305f, voiceBarLevel(50), delta)
    }

    @Test
    fun out_of_range_input_is_clamped() {
        assertEquals(0.08f, voiceBarLevel(-20), delta)
        assertEquals(1f, voiceBarLevel(250), delta)
    }

    @Test
    fun monotonic_over_whole_range() {
        var previous = -1f
        for (v in 0..100) {
            val current = voiceBarLevel(v)
            assertTrue("音量 $v 应单调不减", current >= previous)
            assertTrue("音量 $v 应在 0..1 内", current in 0f..1f)
            previous = current
        }
    }
}