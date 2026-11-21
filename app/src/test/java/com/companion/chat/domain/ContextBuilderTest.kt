package com.companion.chat.domain

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ContextBuilder 单元测试（JVM）：人设/长期记忆稳定前缀 + latest_reminder 注入 + 裁剪协同。 */
class ContextBuilderTest {

    private fun msg(role: String, content: String, reminder: String? = null) = MessageEntity(
        id = 0L,
        conversationId = 1L,
        role = role,
        content = content,
        timestamp = 0L,
        reminder = reminder
    )

    @Test
    fun persona_prepended_as_system() {
        val result = ContextBuilder.build(
            persona = "你是温柔的猫娘",
            history = emptyList(),
            currentUserText = "你好",
            contextWindow = 65536
        )
        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result.first().role)
        assertEquals("你是温柔的猫娘", result.first().content)
        assertEquals(MessageRole.USER, result.last().role)
        assertEquals("你好", result.last().content)
    }

    @Test
    fun blank_persona_no_system() {
        val result = ContextBuilder.build(
            persona = "   ",
            history = emptyList(),
            currentUserText = "你好",
            contextWindow = 65536
        )
        assertEquals(1, result.size)
        assertEquals(MessageRole.USER, result.first().role)
    }

    @Test
    fun long_memory_joined_into_stable_prefix() {
        val result = ContextBuilder.build(
            persona = "人设",
            longMemoryDigest = "对花生过敏；养了一只猫叫团子",
            history = emptyList(),
            currentUserText = "在吗",
            contextWindow = 65536
        )
        val system = result.first()
        assertEquals(MessageRole.SYSTEM, system.role)
        assertTrue(system.content.startsWith("人设"))
        assertTrue(system.content.contains("长期记忆"))
        assertTrue(system.content.contains("对花生过敏"))
    }

    @Test
    fun persona_kept_when_history_trimmed() {
        val history = (1..50).map {
            msg(
                if (it % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                "测试内容".repeat(5) + it
            )
        }
        val result = ContextBuilder.build(
            persona = "人设",
            history = history,
            currentUserText = "本轮",
            contextWindow = 200
        )
        assertTrue(result.any { it.role == MessageRole.SYSTEM && it.content == "人设" })
        assertTrue(result.size < history.size + 2) // 历史被裁剪
    }

    @Test
    fun reminder_injected_before_current_user() {
        val result = ContextBuilder.build(
            persona = "人设",
            history = emptyList(),
            currentUserText = "今天好累",
            currentReminder = "2026-11-20,星期五,,App,中文",
            contextWindow = 65536
        )
        // 末尾两个：reminder → user
        val n = result.size
        assertEquals(MessageRole.REMINDER, result[n - 2].role)
        assertEquals("2026-11-20,星期五,,App,中文", result[n - 2].content)
        assertEquals(MessageRole.USER, result[n - 1].role)
    }

    @Test
    fun history_reminder_replayed_before_its_user() {
        val history = listOf(
            msg(MessageRole.USER, "早", reminder = "2026-11-19,星期四,,App,中文"),
            msg(MessageRole.ASSISTANT, "早呀")
        )
        val result = ContextBuilder.build(
            persona = "",
            history = history,
            currentUserText = "现在几点",
            currentReminder = "2026-11-20,星期五,,App,中文",
            contextWindow = 65536
        )
        // 期望顺序：reminder(19) → user(早) → assistant(早呀) → reminder(20) → user(本轮)
        val roles = result.map { it.role }
        assertEquals(
            listOf(
                MessageRole.REMINDER,
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.REMINDER,
                MessageRole.USER
            ),
            roles
        )
        assertEquals("2026-11-19,星期四,,App,中文", result[0].content)
        assertEquals("早", result[1].content)
    }

    @Test
    fun capability_note_appended_to_system_prefix() {
        val result = ContextBuilder.build(
            persona = "人设",
            history = emptyList(),
            currentUserText = "在吗",
            contextWindow = 65536,
            capabilityNote = ContextBuilder.CAPABILITIES
        )
        val system = result.first()
        assertEquals(MessageRole.SYSTEM, system.role)
        assertTrue(system.content.startsWith("人设"))
        assertTrue(system.content.contains("主动提醒"))
        assertTrue(system.content.contains("主动联系"))
    }

    @Test
    fun no_capability_note_when_not_passed() {
        val result = ContextBuilder.build(
            persona = "人设",
            history = emptyList(),
            currentUserText = "在吗",
            contextWindow = 65536
        )
        assertEquals("人设", result.first().content)
    }

    // ---- P3（2026-11-21）：关系定位放稳定前缀 ----

    @Test
    fun relationship_note_goes_into_stable_prefix() {
        val result = ContextBuilder.build(
            persona = "你是温柔的猫娘",
            history = emptyList(),
            currentUserText = "在吗",
            contextWindow = 65536,
            relationshipNote = "你们已经很亲近了，可以自然地说想他、撒点娇，但别腻、别黏。"
        )
        val system = result.first()
        assertEquals(MessageRole.SYSTEM, system.role)
        assertTrue(system.content.contains("【你们的关系】"))
        assertTrue(system.content.contains("亲近"))
    }

    @Test
    fun no_relationship_note_means_no_noise() {
        val result = ContextBuilder.build(
            persona = "你是温柔的猫娘",
            history = emptyList(),
            currentUserText = "在吗",
            contextWindow = 65536
        )
        assertTrue(result.first().content.none { it == '【' })
    }
}