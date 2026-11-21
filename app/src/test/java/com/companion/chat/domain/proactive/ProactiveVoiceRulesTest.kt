package com.companion.chat.domain.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ProactiveVoiceRules 主动语音开关 + 勿扰纯规则单测。任务 4.3 */
class ProactiveVoiceRulesTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 20)
    private fun at(hour: Int, minute: Int): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun voice_disabled_never_speaks() {
        assertFalse(
            ProactiveVoiceRules.shouldSpeak(
                nowMillis = at(12, 0), voiceEnabled = false,
                dndEnabled = false, zone = zone
            )
        )
    }

    @Test
    fun voice_enabled_no_dnd_speaks() {
        assertTrue(
            ProactiveVoiceRules.shouldSpeak(
                nowMillis = at(12, 0), voiceEnabled = true,
                dndEnabled = false, zone = zone
            )
        )
    }

    @Test
    fun voice_enabled_dnd_quiet_hours_silent() {
        assertFalse(
            ProactiveVoiceRules.shouldSpeak(
                nowMillis = at(2, 0), voiceEnabled = true,
                dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60, zone = zone
            )
        )
    }

    @Test
    fun voice_enabled_dnd_outside_speaks() {
        assertTrue(
            ProactiveVoiceRules.shouldSpeak(
                nowMillis = at(12, 0), voiceEnabled = true,
                dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60, zone = zone
            )
        )
    }

    @Test
    fun dnd_end_boundary_is_outside() {
        // 07:00 为结束开区间 → 已可念
        assertTrue(
            ProactiveVoiceRules.shouldSpeak(
                nowMillis = at(7, 0), voiceEnabled = true,
                dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60, zone = zone
            )
        )
    }
}