package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 闸门总开关（任务 7.1）：关闭后自发消息一律拦截，且优先于其它判据。任务 7.2 勿扰读用户设置同理。 */
class ProactiveGateMasterSwitchTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun ctx(
        enabled: Boolean,
        nowMillis: Long = at("2026-11-20", "12:00"),
        dndEnabled: Boolean = false
    ) = ProactiveGateContext(
        nowMillis = nowMillis,
        category = ProactiveMessageCategory.SPONTANEOUS,
        lastInteractionMillis = null,
        spontaneousSentToday = 0,
        dailyLimit = 3,
        hookAvailable = true,
        dndEnabled = dndEnabled,
        dndStartMinute = 23 * 60,
        dndEndMinute = 7 * 60,
        enabled = enabled
    )

    @Test
    fun master_off_blocks_everything() {
        val d = ProactiveGateRules.evaluate(ctx(enabled = false), zone)
        assertFalse(d.allowed)
        assertEquals(ProactiveBlockReason.MASTER_OFF, d.reason)
    }

    @Test
    fun master_off_wins_over_dnd_and_other_reasons() {
        val d = ProactiveGateRules.evaluate(
            ctx(enabled = false, nowMillis = at("2026-11-20", "23:30"), dndEnabled = true),
            zone
        )
        assertEquals(ProactiveBlockReason.MASTER_OFF, d.reason)
    }

    @Test
    fun master_on_allows_normal_case() {
        assertTrue(ProactiveGateRules.evaluate(ctx(enabled = true), zone).allowed)
    }

    @Test
    fun master_on_but_dnd_quiet_still_blocks_spontaneous() {
        val d = ProactiveGateRules.evaluate(
            ctx(enabled = true, nowMillis = at("2026-11-20", "23:30"), dndEnabled = true),
            zone
        )
        assertEquals(ProactiveBlockReason.DO_NOT_DISTURB, d.reason)
    }

    @Test
    fun enabled_defaults_to_true_for_backward_compatibility() {
        val legacy = ProactiveGateContext(nowMillis = at("2026-11-20", "12:00"))
        assertTrue(ProactiveGateRules.evaluate(legacy, zone).allowed)
    }
}