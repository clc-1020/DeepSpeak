package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 颜文字（P1）：阈值/概率档/正负并存不加/末尾去重/TTS 剥离。 */
class EmotionKaomojiTest {

    /** 永远返回 0 的 Random → 概率判定必命中（测"应该加"的分支）。 */
    private val alwaysHit = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    @Test
    fun appends_when_dominant_emotion_is_strong() {
        val out = EmotionKaomoji.append("好呀，那我陪你", mapOf(Emotion.HAPPY to 0.6), alwaysHit)
        assertTrue("应追加颜文字，实际：$out", out.length > "好呀，那我陪你".length)
        assertTrue(out.trim().endsWith(")") || out.trim().endsWith("ﾉ") || out.trim().endsWith(")"))
        assertTrue(EmotionKaomoji.ALL.any { out.contains(it) })
        assertTrue("颜文字前应有一个半角空格", out.contains(" (") || out.contains(" ヽ") || out.contains(" (*") || out.contains(" (´"))
    }

    @Test
    fun does_not_append_below_threshold() {
        val text = "嗯，知道了"
        assertEquals(text, EmotionKaomoji.append(text, mapOf(Emotion.HAPPY to 0.4), alwaysHit))
    }

    @Test
    fun does_not_append_when_positive_and_negative_coexist() {
        val text = "算了，我不说了"
        val out = EmotionKaomoji.append(
            text,
            mapOf(Emotion.HAPPY to 0.5, Emotion.HURT to 0.5),
            alwaysHit
        )
        assertEquals("正负并存时不许加（会像假笑）", text, out)
    }

    @Test
    fun uses_the_strongest_emotion_palette() {
        val out = EmotionKaomoji.append(
            "行吧",
            mapOf(Emotion.HAPPY to 0.5, Emotion.PROUD to 0.8),
            alwaysHit
        )
        assertTrue(
            "应使用最强者（得意）的表：$out",
            EmotionKaomoji.palette(Emotion.PROUD).any { out.contains(it) }
        )
    }

    @Test
    fun does_not_duplicate_when_text_already_ends_with_kaomoji() {
        val existing = "好呀 (◕‿◕)"
        assertEquals(existing, EmotionKaomoji.append(existing, mapOf(Emotion.HAPPY to 0.8), alwaysHit))
    }

    @Test
    fun probability_tiers() {
        assertEquals(0.0, EmotionKaomoji.probability(0.4), 1e-9)
        assertEquals(0.6, EmotionKaomoji.probability(0.5), 1e-9)
        assertEquals(0.6, EmotionKaomoji.probability(0.69), 1e-9)
        assertEquals(0.9, EmotionKaomoji.probability(0.7), 1e-9)
    }

    /** 强度分档：0.5~0.7 取温和档、≥0.7 取强烈档，两档不重叠。 */
    @Test
    fun picks_mild_pool_below_high_and_strong_pool_at_high() {
        val mild = EmotionKaomoji.pool(Emotion.HAPPY, 0.6)
        val strong = EmotionKaomoji.pool(Emotion.HAPPY, 0.85)
        assertTrue("温和档不应为空", mild.isNotEmpty())
        assertTrue("强烈档不应为空", strong.isNotEmpty())
        assertTrue("两档不能重叠：$mild / $strong", mild.intersect(strong.toSet()).isEmpty())

        val outMild = EmotionKaomoji.append("好呀", mapOf(Emotion.HAPPY to 0.6), alwaysHit)
        assertTrue("0.6 应用温和档：$outMild", mild.any { outMild.endsWith(it) })
        val outStrong = EmotionKaomoji.append("好呀", mapOf(Emotion.HAPPY to 0.85), alwaysHit)
        assertTrue("0.85 应用强烈档：$outStrong", strong.any { outStrong.endsWith(it) })
    }

    /** 数据完整性：每种情绪温和/强烈各 3 个，全库 48 个且无重复（重名会让 strip/去重失效）。 */
    @Test
    fun every_emotion_has_three_per_tier_and_globally_unique() {
        for (e in Emotion.ALL) {
            assertEquals("$e 温和档应为 3 个", 3, EmotionKaomoji.pool(e, 0.6).size)
            assertEquals("$e 强烈档应为 3 个", 3, EmotionKaomoji.pool(e, 0.9).size)
        }
        assertEquals("全库应为 8 情绪 × 6 = 48", 48, EmotionKaomoji.ALL.size)
        assertEquals("颜文字不能有重复", EmotionKaomoji.ALL.size, EmotionKaomoji.ALL.toSet().size)
    }

    @Test
    fun strip_removes_kaomoji_for_tts() {
        val spoken = EmotionKaomoji.strip("好呀 (◕‿◕)，那我等你 (´･_･`)")
        assertFalse(spoken.contains("(◕‿◕)"))
        assertFalse(spoken.contains("(´･_･`)"))
        assertTrue(spoken.startsWith("好呀"))
        assertTrue(spoken.contains("那我等你"))
    }
}