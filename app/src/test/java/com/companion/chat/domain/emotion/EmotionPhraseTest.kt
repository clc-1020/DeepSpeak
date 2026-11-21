package com.companion.chat.domain.emotion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入文案拼装（P1）。
 *
 * 两条必须钉死的产品语义：
 *  1. **委屈在 P1 只「软」**，不许撒娇、不许示弱（`ALLOW_AFFECTION = false`）
 *  2. 正负并存时**负面主导**（directive 取负面那条），否则会出现"笑着说气话"
 */
class EmotionPhraseTest {

    @Test
    fun single_dominant_emotion_renders_with_number_and_directive() {
        val block = EmotionPhrase.render(
            hers = mapOf(Emotion.HURT to 0.6),
            trigger = "你连着三次说在测试我"
        )!!
        assertTrue(block.contains("有点委屈（0.6）"))
        assertTrue(block.contains("你连着三次说在测试我"))
        assertTrue(block.contains("语气软一点"))
    }

    @Test
    fun hurt_does_not_show_affection_in_p1() {
        val block = EmotionPhrase.render(mapOf(Emotion.HURT to 0.9), "", allowAffection = false)!!
        assertFalse("P1 委屈不许撒娇", block.contains("撒娇"))
        assertFalse("P1 委屈不再提示弱", block.contains("示弱"))
    }

    @Test
    fun hurt_affection_only_when_switched_on() {
        val block = EmotionPhrase.render(mapOf(Emotion.HURT to 0.9), "", allowAffection = true)!!
        assertTrue(block.contains("撒娇"))
    }

    @Test
    fun two_emotions_merge_into_one_sentence() {
        val block = EmotionPhrase.render(
            mapOf(Emotion.HAPPY to 0.5, Emotion.PROUD to 0.5), ""
        )!!
        assertTrue(block.contains("有点开心又有点得意（0.5/0.5）"))
        assertTrue(block.contains("语气轻快") || block.contains("得意"))
    }

    @Test
    fun positive_and_negative_puts_negative_in_charge() {
        val block = EmotionPhrase.render(
            mapOf(Emotion.HAPPY to 0.5, Emotion.HURT to 0.6), ""
        )!!
        assertTrue("正负并存要用『但』连接", block.contains("但还有点"))
        assertTrue("directive 必须取负面那条", block.contains("语气软一点"))
    }

    @Test
    fun below_threshold_renders_nothing() {
        assertNull(EmotionPhrase.render(mapOf(Emotion.HAPPY to 0.1), "无关"))
        assertNull(EmotionPhrase.render(emptyMap(), ""))
    }

    @Test
    fun his_line_only_when_meaningful() {
        val normal = EmotionPhrase.render(emptyMap(), "", hisMood = "normal", hisIntensity = 0.0)
        assertNull("他平静且无她的情绪 → 整块不注入", normal)

        val low = EmotionPhrase.render(
            emptyMap(), "", hisMood = "low", hisIntensity = 0.7
        )!!
        assertTrue(low.contains("【他这会儿】有点低落（0.7）"))
        assertTrue(low.contains("别追问"))

        val weak = EmotionPhrase.render(mapOf(Emotion.HAPPY to 0.6), "", hisMood = "low", hisIntensity = 0.1)
        assertNotNull(weak)
        assertFalse("他强度太低就别提", weak!!.contains("他这会儿"))
    }

    @Test
    fun trigger_is_trimmed_and_empty_trigger_has_no_colon() {
        val longTrigger = EmotionPhrase.render(mapOf(Emotion.LOW to 0.6), "啊".repeat(40))!!
        assertTrue(longTrigger.contains("…"))
        val noTrigger = EmotionPhrase.render(mapOf(Emotion.LOW to 0.6), "")!!
        assertFalse(noTrigger.contains("：。" ))
    }
}