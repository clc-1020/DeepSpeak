package com.companion.chat.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PendingMessageType 优先级映射单测。任务 3.1 */
class PendingMessageTypeTest {

    @Test
    fun priorities_match_plan() {
        assertEquals(5, PendingMessageType.INSTRUCTION.priority) // 指令提醒
        assertEquals(4, PendingMessageType.FOLLOW_UP.priority)   // 追问
        assertEquals(3, PendingMessageType.SPECIAL_DATE.priority) // 特殊日期
        assertEquals(2, PendingMessageType.ENVIRONMENT.priority)  // 环境
        assertEquals(1, PendingMessageType.CHITCHAT.priority)     // 闲聊
    }

    @Test
    fun from_code_roundtrip() {
        for (t in PendingMessageType.values()) {
            assertEquals(t, PendingMessageType.fromCode(t.code))
        }
        assertNull(PendingMessageType.fromCode("unknown"))
    }

    @Test
    fun priority_of_unknown_falls_back_to_chitchat() {
        assertEquals(1, PendingMessageType.priorityOf("unknown"))
        assertEquals(5, PendingMessageType.priorityOf("instruction"))
    }

    @Test
    fun codes_are_unique() {
        val codes = PendingMessageType.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
