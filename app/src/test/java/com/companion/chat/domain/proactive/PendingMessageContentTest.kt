package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PendingMessageContent 编解码单测。任务 3.2（审查改进①） */
class PendingMessageContentTest {

    @Test
    fun memory_roundtrip() {
        val c = PendingMessageContent.Memory(42)
        assertEquals("memory:42", c.encode())
        assertEquals(c, PendingMessageContent.decode("memory:42"))
        assertEquals(42L, PendingMessageContent.memoryIdOrNull("memory:42"))
    }

    @Test
    fun plain_text_roundtrip() {
        val c = PendingMessageContent.PlainText("今天也要加油")
        assertEquals("text:今天也要加油", c.encode())
        assertEquals(c, PendingMessageContent.decode("text:今天也要加油"))
        assertNull(PendingMessageContent.memoryIdOrNull("text:今天也要加油"))
    }

    @Test
    fun plain_text_preserving_memory_lookalike() {
        // 文本本身以 "memory:" 开头也不会被误解为 Memory
        val c = PendingMessageContent.PlainText("memory:不是id")
        val decoded = PendingMessageContent.decode(c.encode())
        assertTrue(decoded is PendingMessageContent.PlainText)
        assertEquals("memory:不是id", (decoded as PendingMessageContent.PlainText).text)
        assertNull(PendingMessageContent.memoryIdOrNull(c.encode()))
    }

    @Test
    fun legacy_raw_treated_as_plain_text() {
        val decoded = PendingMessageContent.decode("随便一段没有前缀的旧文本")
        assertTrue(decoded is PendingMessageContent.PlainText)
        assertEquals("随便一段没有前缀的旧文本", (decoded as PendingMessageContent.PlainText).text)
    }

    @Test
    fun malformed_memory_falls_back_to_plain_text() {
        val decoded = PendingMessageContent.decode("memory:abc") // id 非数字
        assertTrue(decoded is PendingMessageContent.PlainText)
        assertNull(PendingMessageContent.memoryIdOrNull("memory:abc"))
    }

    // ---------- Environment（V2.1.4） ----------

    @Test
    fun environment_roundtrip() {
        val c = PendingMessageContent.Environment("rain")
        assertEquals("env:rain", c.encode())
        assertEquals(c, PendingMessageContent.decode("env:rain"))
    }

    @Test
    fun environment_without_prefix_is_plain_text() {
        val decoded = PendingMessageContent.decode("rain")
        assertTrue(decoded is PendingMessageContent.PlainText)
    }

    /** 空 stableId 不当 Environment（防止造出 `Environment("")` 这种坏值）。 */
    @Test
    fun environment_empty_stable_id_falls_back_to_plain_text() {
        val decoded = PendingMessageContent.decode("env:")
        assertTrue(decoded is PendingMessageContent.PlainText)
    }

    // ---------- Festival（V2.1.7） ----------

    @Test
    fun festival_roundtrip() {
        val c = PendingMessageContent.Festival("春节")
        assertEquals("fest:春节", c.encode())
        assertEquals(c, PendingMessageContent.decode("fest:春节"))
        assertNull(PendingMessageContent.memoryIdOrNull("fest:春节"))
    }

    /** 空名字兜底成 PlainText，与 env 分支同契约；不改 decode 的 else 兜底。 */
    @Test
    fun festival_empty_name_falls_back_to_plain_text() {
        val decoded = PendingMessageContent.decode("fest:")
        assertTrue(decoded is PendingMessageContent.PlainText)
        assertEquals("fest:", (decoded as PendingMessageContent.PlainText).text)
    }
}
