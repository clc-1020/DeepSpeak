package com.companion.chat.domain.proactive

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProactivePromptBuilder 提示词构建单测。任务 3.5 / 5.2 */
class ProactivePromptBuilderTest {

    private fun msg(role: String, content: String, ts: Long = 0L) =
        MessageEntity(conversationId = 1, role = role, content = content, timestamp = ts)

    @Test
    fun system_includes_persona_and_rules() {
        val s = ProactivePromptBuilder.system("温柔、幽默")
        assertTrue(s.contains("温柔、幽默"))
        assertTrue(s.contains("主动"))
        assertTrue(s.contains("只输出这条消息本身"))
    }

    @Test
    fun system_without_persona_still_valid() {
        val s = ProactivePromptBuilder.system("")
        assertFalse(s.contains("人设："))
        assertTrue(s.contains("主动"))
    }

    // ---- 任务 5.2：mood 融入 system ----

    @Test
    fun system_default_mood_is_normal_tone() {
        val s = ProactivePromptBuilder.system("温柔")
        assertTrue(s.contains("你现在心情是"))
        assertTrue(s.contains("平常"))
        assertTrue(s.contains("自然平和"))
    }

    @Test
    fun system_low_mood_asks_gentle_caring_tone() {
        val s = ProactivePromptBuilder.system("温柔", "low")
        assertTrue(s.contains("有点低落"))
        assertTrue(s.contains("温和"))
        assertTrue(s.contains("关心"))
        assertTrue(s.contains("不追问"))
    }

    @Test
    fun system_happy_mood_asks_lively_tone() {
        val s = ProactivePromptBuilder.system("温柔", "happy")
        assertTrue(s.contains("心情不错"))
        assertTrue(s.contains("活泼"))
    }

    @Test
    fun system_unknown_mood_falls_back_to_normal() {
        val s = ProactivePromptBuilder.system("温柔", "weird-value")
        assertTrue(s.contains("平常"))
        assertFalse(s.contains("weird-value"))
    }

    @Test
    fun system_uppercase_mood_is_normalized() {
        val s = ProactivePromptBuilder.system("温柔", "HAPPY")
        assertTrue(s.contains("心情不错"))
    }

    // ---- user 段：只放上下文，不重复 mood ----

    @Test
    fun build_includes_recent_and_hook() {
        val recent = listOf(
            msg(MessageRole.USER, "今天好累"),
            msg(MessageRole.ASSISTANT, "辛苦了"),
            msg("latest_reminder", "2026-11-20,星期五") // 应被过滤
        )
        val u = ProactivePromptBuilder.build("面试的事", recent)
        assertTrue(u.contains("用户：今天好累"))
        assertTrue(u.contains("你：辛苦了"))
        assertTrue(u.contains("可以自然切入的话题：面试的事"))
        assertFalse(u.contains("latest_reminder"))
        assertFalse(u.contains("心情"))
    }

    @Test
    fun build_caps_recent_to_last_three() {
        val recent = (1..5).map { msg(MessageRole.USER, "第${it}条") }
        val u = ProactivePromptBuilder.build("", recent)
        // 只保留最后 3 条
        assertFalse(u.contains("第1条"))
        assertFalse(u.contains("第2条"))
        assertTrue(u.contains("第3条"))
        assertTrue(u.contains("第5条"))
    }

    @Test
    fun build_without_hook_omits_topic_line() {
        val u = ProactivePromptBuilder.build("", emptyList())
        assertFalse(u.contains("可以自然切入的话题"))
        assertTrue(u.contains("请生成你要主动发出的那条消息"))
    }

    // ---- P2（2026-11-21）：情绪既改语气，也改长度 ----

    @Test
    fun system_includes_emotion_and_length_hint() {
        val excited = ProactivePromptBuilder.system("温柔", "normal", mapOf("excited" to 0.8))
        assertTrue(excited.contains("兴奋"))
        assertTrue("兴奋应允许多说两句", excited.contains("2-3 句"))

        val low = ProactivePromptBuilder.system("温柔", "normal", mapOf("low" to 0.6))
        assertTrue(low.contains("低落"))
        assertTrue("低对应只说一句", low.contains("只说一句"))
    }

    @Test
    fun system_without_emotion_keeps_default_length() {
        val s = ProactivePromptBuilder.system("温柔")
        assertTrue(s.contains("长度 1-2 句"))
    }
}