package com.companion.chat.domain

import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.dto.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ContextTrimmer 单元测试（JVM）。任务 1.4 */
class ContextTrimmerTest {

    private fun msg(role: String, content: String) = ChatMessageDto(role, content)

    @Test
    fun keeps_all_when_under_budget() {
        val messages = listOf(
            msg(MessageRole.SYSTEM, "人设"),
            msg(MessageRole.USER, "你好"),
            msg(MessageRole.ASSISTANT, "你好呀")
        )
        val result = ContextTrimmer.trim(messages, contextWindow = 1000)
        assertEquals(3, result.size)
    }

    @Test
    fun drops_oldest_when_over_budget() {
        // contextWindow=10, ratio=0.8 -> budget=8 字符
        val messages = listOf(
            msg(MessageRole.USER, "1111"),   // 最旧
            msg(MessageRole.ASSISTANT, "2222"),
            msg(MessageRole.USER, "3333")    // 最新
        )
        val result = ContextTrimmer.trim(messages, contextWindow = 10, budgetRatio = 0.8)
        // 预算 8，两个最新各 4 字正好 8，最旧的被丢掉
        assertEquals(2, result.size)
        assertEquals("2222", result[0].content)
        assertEquals("3333", result[1].content)
    }

    @Test
    fun always_keeps_system_prompt() {
        val messages = listOf(
            msg(MessageRole.SYSTEM, "你是温柔的女友"),
            msg(MessageRole.USER, "很长很长很长很长很长很长的用户消息"),
            msg(MessageRole.ASSISTANT, "很长很长很长很长很长很长的回复内容")
        )
        val result = ContextTrimmer.trim(messages, contextWindow = 6, budgetRatio = 0.8)
        assertTrue(result.any { it.role == MessageRole.SYSTEM })
        assertEquals("你是温柔的女友", result.first().content)
    }
}
