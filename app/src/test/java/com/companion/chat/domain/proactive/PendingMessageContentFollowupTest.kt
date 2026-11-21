package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2.2.4：`PendingMessageContent.Followup` 的编解码单测（纯 JVM，无 Android）。
 *
 * 覆盖计划书 §5 的 15–18：
 *  - 往返一致（含任务原文自带 `:` 的情形）
 *  - 非法/缺段输入一律回落 [PendingMessageContent.PlainText]，**不许**抛出、也不许造出半截 Followup
 *  - [PendingMessageContent.followupOrNull] 是 V2.2.5 通知按钮的取 id 入口
 *
 * 为什么非法输入也要钉住：这条 content 会落进 `pending_messages`，坏数据会被送达层
 * 当正文发出去（`"followup:7"` 变成用户屏幕上的一行乱码），不如回落成原文并在日志里可见。
 */
class PendingMessageContentFollowupTest {

    // 15. 编码格式 + 往返一致
    @Test
    fun followup_roundtrip_and_wire_format() {
        val content = PendingMessageContent.Followup(7L, 3, "背单词")
        assertEquals("followup:7:3:背单词", content.encode())
        assertEquals(content, PendingMessageContent.decode(content.encode()))
    }

    // 16. 任务原文含 ':' 也能完整还原（只切前两段）
    @Test
    fun followup_task_containing_colon_survives_roundtrip() {
        val content = PendingMessageContent.Followup(12L, 1, "背:单词（第2单元）")
        val decoded = PendingMessageContent.decode(content.encode())
        assertEquals(content, decoded)
        assertEquals("背:单词（第2单元）", (decoded as PendingMessageContent.Followup).task)
    }

    // 17. 非法输入全部回落 PlainText（四种：id 0 / attempt 0 / id 非数字 / 缺段）
    @Test
    fun invalid_followup_falls_back_to_plain_text() {
        listOf("followup:0:1:x", "followup:7:0:x", "followup:abc:1:x", "followup:7").forEach { raw ->
            val decoded = PendingMessageContent.decode(raw)
            assertTrue("应回落 PlainText：$raw，实际=$decoded", decoded is PendingMessageContent.PlainText)
            assertEquals(raw, (decoded as PendingMessageContent.PlainText).text)
        }

        // 负数 id / 负数 attempt 同样非法（校验用的是 <= 0，不只 == 0）
        assertTrue(PendingMessageContent.decode("followup:-3:1:x") is PendingMessageContent.PlainText)
        assertTrue(PendingMessageContent.decode("followup:7:-1:x") is PendingMessageContent.PlainText)
    }

    // 18. followupOrNull：追问返回实例，非追问返回 null
    @Test
    fun followup_or_null_returns_instance_only_for_followup() {
        val hit = PendingMessageContent.followupOrNull("followup:7:3:背单词")
        assertEquals(PendingMessageContent.Followup(7L, 3, "背单词"), hit)
        assertNull(PendingMessageContent.followupOrNull("text:hi"))
        assertNull(PendingMessageContent.followupOrNull("followup:7:0:x"))
    }
}