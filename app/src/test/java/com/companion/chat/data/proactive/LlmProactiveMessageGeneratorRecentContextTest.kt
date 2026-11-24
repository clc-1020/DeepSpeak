package com.companion.chat.data.proactive

import com.companion.chat.domain.proactive.PendingMessageContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试（1.13.2）：「定时提醒念成另一段对话 A 的内容」。
 *
 * 背景：`LlmProactiveMessageGenerator` 之前无条件把「最近活跃会话」的最近 3 条当上下文喂给模型。
 * 用户在对话 A 里发过消息后，A 成为最近活跃会话；此时到点的**指令提醒 / 追问**会被生成为 A 的延续，
 * 播报/通知出来的是 A 的内容而不是提醒本身。
 *
 * 修复：指令提醒 / 追问**不叠加**最近对话上下文；其余类型保留。
 */
class LlmProactiveMessageGeneratorRecentContextTest {

    @Test
    fun instructionReminderExcludesRecentContext() {
        assertFalse(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Instruction("提醒我喝水", memoryId = 1L)
            )
        )
        // memoryId 为 null 的降级指令同样排除
        assertFalse(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Instruction("叫我起床", memoryId = null)
            )
        )
    }

    @Test
    fun followupExcludesRecentContext() {
        assertFalse(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Followup(reminderId = 1L, attempt = 1, task = "背单词")
            )
        )
    }

    @Test
    fun spontaneousTopicsKeepRecentContext() {
        assertTrue(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Memory(1L)
            )
        )
        assertTrue(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.LongMemory(1L)
            )
        )
        assertTrue(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Festival("春节")
            )
        )
        assertTrue(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.Environment("rain_80")
            )
        )
        assertTrue(
            LlmProactiveMessageGenerator.shouldIncludeRecentContext(
                PendingMessageContent.PlainText("随便聊聊")
            )
        )
    }
}
