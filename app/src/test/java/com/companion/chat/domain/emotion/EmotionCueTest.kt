package com.companion.chat.domain.emotion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刺激闸门（P1）。零成本，决定「这轮值不值得调模型判情绪」。
 *
 * 原则：**宁可多调（花几分钱），也不漏判**（她该有反应却没反应 = 最明显的"假"）。
 */
class EmotionCueTest {

    @Test
    fun strong_punctuation_is_a_stimulus() {
        assertTrue(EmotionCue.isStimulus("你怎么这样！！"))
        assertTrue(EmotionCue.isStimulus("真的？？？"))
    }

    @Test
    fun emotional_keywords_are_stimuli() {
        assertTrue(EmotionCue.isStimulus("你太厉害了吧"))
        assertTrue(EmotionCue.isStimulus("我好累"))
        assertTrue(EmotionCue.isStimulus("谢谢你"))
        assertTrue(EmotionCue.isStimulus("哼"))
        assertTrue(EmotionCue.isStimulus("我走了"))
    }

    @Test
    fun neutral_acknowledgements_are_not_stimuli() {
        assertFalse(EmotionCue.isStimulus("嗯"))
        assertFalse(EmotionCue.isStimulus("哦"))
        assertFalse(EmotionCue.isStimulus("好"))
        assertFalse(EmotionCue.isStimulus("嗯嗯"))
    }

    @Test
    fun blank_is_not_a_stimulus() {
        assertFalse(EmotionCue.isStimulus(""))
        assertFalse(EmotionCue.isStimulus("   "))
    }

    @Test
    fun length_jump_is_a_stimulus() {
        // 上一条 3 字 → 这条 40 字（>1.8 倍且 >=8 字）
        assertTrue(EmotionCue.isStimulus("我今天真的特别特别想跟你说一件事，你别嫌我话多啊", prevLen = 3))
        assertFalse("没有上一条长度信息时不按突变判定", EmotionCue.isStimulus("我很好", prevLen = 0))
    }

    @Test
    fun emoji_is_a_stimulus() {
        assertTrue(EmotionCue.isStimulus("在吗😭"))
    }
}