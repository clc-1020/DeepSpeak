package com.companion.chat.domain.proactive

import com.companion.chat.domain.emotion.Emotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 情绪 → 每日上限微调（P2）：兴奋/想你 +1，低落/烦躁 -1，且情绪**不能**绕过勿扰。 */
class EmotionProactivePolicyTest {

    @After
    fun tearDown() = EmotionProactivePolicy.resetCacheForTest()

    @Test
    fun excited_or_missing_raises_limit_by_one() {
        assertEquals(1, EmotionProactivePolicy.limitBonus(mapOf(Emotion.EXCITED to 0.7)))
        assertEquals(1, EmotionProactivePolicy.limitBonus(mapOf(Emotion.MISSING to 0.6)))
    }

    @Test
    fun low_or_annoyed_lowers_limit_by_one() {
        assertEquals(-1, EmotionProactivePolicy.limitBonus(mapOf(Emotion.LOW to 0.8)))
        assertEquals(-1, EmotionProactivePolicy.limitBonus(mapOf(Emotion.ANNOYED to 0.6)))
    }

    @Test
    fun positive_and_negative_together_does_not_lower() {
        // 开心 0.7 + 低落 0.6 → 有正面情绪，不该减量（她没到"不想说话"的地步）
        assertEquals(0, EmotionProactivePolicy.limitBonus(mapOf(Emotion.HAPPY to 0.7, Emotion.LOW to 0.6)))
    }

    @Test
    fun weak_emotions_do_not_change_limit() {
        assertEquals(0, EmotionProactivePolicy.limitBonus(mapOf(Emotion.EXCITED to 0.5)))
        assertEquals(0, EmotionProactivePolicy.limitBonus(emptyMap()))
    }

    @Test
    fun cache_reflects_last_update_and_low_can_go_negative() {
        EmotionProactivePolicy.updateCache(mapOf(Emotion.EXCITED to 0.9))
        assertEquals(1, EmotionProactivePolicy.currentBonus())
        EmotionProactivePolicy.updateCache(mapOf(Emotion.LOW to 0.9))
        assertEquals(-1, EmotionProactivePolicy.currentBonus())
    }
}