package com.companion.chat.domain.proactive

import com.companion.chat.domain.emotion.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知标题按情绪换措辞（P2）：只在情绪明显时换，否则回落默认。 */
class EmotionNotificationTitleTest {

    @Test
    fun strong_emotions_map_to_distinct_titles() {
        assertEquals("有件事想跟你说", EmotionNotificationTitle.forEmotions(mapOf(Emotion.EXCITED to 0.7)))
        assertEquals("你在忙吗", EmotionNotificationTitle.forEmotions(mapOf(Emotion.MISSING to 0.6)))
        assertEquals("说两句", EmotionNotificationTitle.forEmotions(mapOf(Emotion.LOW to 0.8)))
        assertEquals("喂", EmotionNotificationTitle.forEmotions(mapOf(Emotion.ANNOYED to 0.6)))
    }

    @Test
    fun weak_emotion_or_empty_falls_back_to_default() {
        assertNull(EmotionNotificationTitle.forEmotions(emptyMap()))
        assertNull(EmotionNotificationTitle.forEmotions(mapOf(Emotion.HAPPY to 0.3)))
    }

    @Test
    fun strongest_emotion_wins() {
        assertEquals(
            "你在忙吗",
            EmotionNotificationTitle.forEmotions(mapOf(Emotion.HAPPY to 0.6, Emotion.MISSING to 0.8))
        )
    }
}