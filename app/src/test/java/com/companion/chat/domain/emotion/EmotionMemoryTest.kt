package com.companion.chat.domain.emotion

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 情绪写回长期记忆（P3）：只在亲近 + 强度 ≥0.6 + 有 trigger；烦躁/低落永不写。 */
class EmotionMemoryTest {

    @Test
    fun picks_strong_warm_emotion_in_close_stage() {
        val picked = EmotionMemory.pick(
            mapOf(Emotion.HAPPY to 0.8, Emotion.PROUD to 0.4),
            RelationshipStage.CLOSE,
            "你连夸我厉害可爱"
        )
        assertNotNull(picked)
        assertTrue(picked!!.first == Emotion.HAPPY)
        assertTrue(picked.second >= 0.8)
    }

    @Test
    fun nothing_written_outside_close_stage() {
        assertNull(
            EmotionMemory.pick(
                mapOf(Emotion.HAPPY to 0.9),
                RelationshipStage.FAMILIAR,
                "你夸我"
            )
        )
    }

    @Test
    fun weak_or_triggerless_emotion_is_skipped() {
        assertNull(EmotionMemory.pick(mapOf(Emotion.HAPPY to 0.5), RelationshipStage.CLOSE, "夸我"))
        assertNull(EmotionMemory.pick(mapOf(Emotion.HAPPY to 0.9), RelationshipStage.CLOSE, "  "))
    }

    @Test
    fun annoyed_and_low_are_never_written() {
        assertNull(EmotionMemory.pick(mapOf(Emotion.ANNOYED to 0.9), RelationshipStage.CLOSE, "他说在测试我"))
        assertNull(EmotionMemory.pick(mapOf(Emotion.LOW to 0.9), RelationshipStage.CLOSE, "他说在测试我"))
    }

    @Test
    fun format_is_first_person_and_reads_naturally() {
        val s = EmotionMemory.format(Emotion.HAPPY, "你连夸我厉害可爱。", "2026-11-21")
        assertTrue(s.startsWith("2026-11-21 "))
        assertTrue(s.contains("你连夸我厉害可爱"))
        assertTrue(s.endsWith("我当时开心。"))
    }
}