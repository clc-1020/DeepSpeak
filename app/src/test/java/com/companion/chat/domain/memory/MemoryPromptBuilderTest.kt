package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryPromptBuilder 单测。任务 2.2 */
class MemoryPromptBuilderTest {

    private fun msg(role: String, content: String, id: Long = 0) =
        MessageEntity(id = id, conversationId = 1, role = role, content = content, timestamp = id)

    @Test
    fun builds_transcript_in_order() {
        val t = MemoryPromptBuilder.buildTranscript(
            listOf(
                msg(MessageRole.USER, "明天要面试", 1),
                msg(MessageRole.ASSISTANT, "加油", 2)
            )
        )
        assertEquals("用户：明天要面试\n助手：加油", t)
    }

    @Test
    fun skips_system_reminder_and_blank() {
        val t = MemoryPromptBuilder.buildTranscript(
            listOf(
                msg(MessageRole.SYSTEM, "人设", 1),
                msg(MessageRole.REMINDER, "2026-11-20,Friday", 2),
                msg(MessageRole.USER, "   ", 3),
                msg(MessageRole.USER, "你好", 4)
            )
        )
        assertEquals("用户：你好", t)
    }

    @Test
    fun empty_when_no_usable_messages() {
        assertEquals("", MemoryPromptBuilder.buildTranscript(emptyList()))
    }

    @Test
    fun system_prompt_mentions_json_and_keys() {
        val s = MemoryPromptBuilder.SYSTEM
        assertTrue(s.contains("JSON"))
        assertTrue(s.contains("short"))
        assertTrue(s.contains("long"))
        assertTrue(s.contains("daily"))
    }
}
