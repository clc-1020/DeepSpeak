package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「没有短期记忆时说什么」的兜底选择单测（2026-11-21）。
 *
 * 真机事故：短期记忆被清空（0 条）→ 主动检查每轮 NO_HOOK → 12 小时一条关心都没发，
 * 用户以为功能坏了。兜底要解决的就是「记忆空了也得能说句话」，同时不能变成复读机：
 *  - 同一条长期记忆 7 天内不提第二次；
 *  - 纯关心每天最多 1 次、且距上条主动消息 ≥3 小时。
 */
class ProactiveFallbackTopicsTest {

    private val h = 60L * 60 * 1000
    private val day = 24 * h
    private val now = 1_800_000_000_000L

    private fun pick(id: Long, text: String = "记忆$id") = ProactiveFallbackTopics.LongPick(id, text)

    // ---------- 长期记忆 ----------

    @Test
    fun long_memory_picked_when_never_used() {
        val got = ProactiveFallbackTopics.pickLongMemory(
            listOf(pick(1), pick(2)), emptyMap(), now
        )
        assertEquals(1L, got?.id)
    }

    @Test
    fun long_memory_in_cooldown_is_skipped() {
        // 1 号 2 天前刚用过 → 跳到 2 号
        val got = ProactiveFallbackTopics.pickLongMemory(
            listOf(pick(1), pick(2)),
            mapOf(1L to now - 2 * day),
            now
        )
        assertEquals(2L, got?.id)
    }

    @Test
    fun long_memory_after_cooldown_is_usable_again() {
        val got = ProactiveFallbackTopics.pickLongMemory(
            listOf(pick(1)),
            mapOf(1L to now - 8 * day),
            now
        )
        assertEquals(1L, got?.id)
    }

    @Test
    fun long_memory_all_in_cooldown_returns_null() {
        val got = ProactiveFallbackTopics.pickLongMemory(
            listOf(pick(1), pick(2)),
            mapOf(1L to now - day, 2L to now - 3 * day),
            now
        )
        assertNull(got)
    }

    @Test
    fun long_memory_with_no_candidates_returns_null() {
        assertNull(ProactiveFallbackTopics.pickLongMemory(emptyList(), emptyMap(), now))
    }

    @Test
    fun clock_rollback_makes_long_memory_usable() {
        // 记录时刻在未来（时钟被改过）→ 不该永久堵死
        val got = ProactiveFallbackTopics.pickLongMemory(
            listOf(pick(1)),
            mapOf(1L to now + 90 * day),
            now
        )
        assertEquals(1L, got?.id)
    }

    // ---------- 纯关心 ----------

    @Test
    fun kindness_allowed_when_never_sent() {
        assertTrue(ProactiveFallbackTopics.kindnessAllowed(null, "2026-11-21", null, now))
    }

    @Test
    fun kindness_blocked_when_already_sent_today() {
        assertFalse(
            ProactiveFallbackTopics.kindnessAllowed("2026-11-21", "2026-11-21", null, now)
        )
    }

    @Test
    fun kindness_blocked_within_three_hours_of_last_message() {
        assertFalse(
            ProactiveFallbackTopics.kindnessAllowed(
                "2026-11-20", "2026-11-21", now - 2 * h, now
            )
        )
    }

    @Test
    fun kindness_allowed_after_three_hours() {
        assertTrue(
            ProactiveFallbackTopics.kindnessAllowed(
                "2026-11-20", "2026-11-21", now - 4 * h, now
            )
        )
    }

    @Test
    fun kindness_allowed_when_clock_rolled_back() {
        assertTrue(
            ProactiveFallbackTopics.kindnessAllowed(
                "2026-11-20", "2026-11-21", now + 10 * day, now
            )
        )
    }

    @Test
    fun hooks_never_ask_model_to_ramble_or_lecture() {
        assertTrue(ProactiveFallbackTopics.KINDNESS_HOOK.contains("不要编造"))
        assertTrue(ProactiveFallbackTopics.longMemoryHook("对花生过敏").contains("不要硬提"))
        assertTrue(ProactiveFallbackTopics.longMemoryHook("对花生过敏").contains("对花生过敏"))
    }
}