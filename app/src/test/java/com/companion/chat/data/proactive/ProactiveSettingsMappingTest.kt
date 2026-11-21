package com.companion.chat.data.proactive

import com.companion.chat.domain.proactive.ProactiveBlockReason
import com.companion.chat.domain.proactive.ProactiveGateContext
import com.companion.chat.domain.proactive.ProactiveGateRules
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import com.companion.chat.domain.settings.DndSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 设置项 → 闸门配置映射单测（任务 7.1 / 7.2 的「设置是否真的生效」证明）。 */
class ProactiveSettingsMappingTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private val dndOff = DndSettings(enabled = false)
    private val dndQuiet = DndSettings(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60, suppressNotification = true)
    private val dndVoiceOnly = DndSettings(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60, suppressNotification = false)

    private fun ctx(cfg: ProactiveGateConfig, nowMillis: Long, sentToday: Int = 0) = ProactiveGateContext(
        nowMillis = nowMillis,
        category = ProactiveMessageCategory.SPONTANEOUS,
        spontaneousSentToday = sentToday,
        dailyLimit = cfg.dailyLimit,
        dndEnabled = cfg.dndEnabled,
        dndStartMinute = cfg.dndStartMinute,
        dndEndMinute = cfg.dndEndMinute,
        enabled = cfg.enabled
    )

    @Test
    fun master_switch_off_disables_gate() {
        val cfg = ProactiveSettingsMapping.gateConfig(proactiveEnabled = false, dailyLimit = 3, dnd = dndOff)
        assertFalse(cfg.enabled)
        val d = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "12:00")), zone)
        assertEquals(ProactiveBlockReason.MASTER_OFF, d.reason)
    }

    @Test
    fun daily_limit_passes_through_and_is_clamped() {
        assertEquals(3, ProactiveSettingsMapping.gateConfig(true, 3, dndOff).dailyLimit)
        assertEquals(0, ProactiveSettingsMapping.gateConfig(true, 0, dndOff).dailyLimit)
        assertEquals(10, ProactiveSettingsMapping.gateConfig(true, 99, dndOff).dailyLimit)
        assertEquals(0, ProactiveSettingsMapping.gateConfig(true, -5, dndOff).dailyLimit)
    }

    @Test
    fun daily_limit_zero_blocks_every_spontaneous_message() {
        val cfg = ProactiveSettingsMapping.gateConfig(true, 0, dndOff)
        val d = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "12:00"), sentToday = 0), zone)
        assertEquals(ProactiveBlockReason.DAILY_LIMIT, d.reason)
    }

    @Test
    fun dnd_off_never_blocks() {
        val cfg = ProactiveSettingsMapping.gateConfig(true, 3, dndOff)
        assertFalse(cfg.dndEnabled)
        val d = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "23:30")), zone)
        assertTrue(d.allowed)
    }

    @Test
    fun dnd_with_suppress_switch_on_blocks_notifications_inside_window() {
        val cfg = ProactiveSettingsMapping.gateConfig(true, 3, dndQuiet)
        assertTrue(cfg.dndEnabled)
        assertEquals(23 * 60, cfg.dndStartMinute)
        assertEquals(7 * 60, cfg.dndEndMinute)
        val quiet = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "23:30")), zone)
        assertEquals(ProactiveBlockReason.DO_NOT_DISTURB, quiet.reason)
        val day = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "12:00")), zone)
        assertTrue(day.allowed) // 区间外照常
    }

    @Test
    fun dnd_with_suppress_switch_off_only_silences_voice_not_gate() {
        // 独立开关关掉 → 闸门不拦（通知照发），语音静音由 ProactiveVoiceRules 负责
        val cfg = ProactiveSettingsMapping.gateConfig(true, 3, dndVoiceOnly)
        assertFalse(cfg.dndEnabled)
        val d = ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "23:30")), zone)
        assertTrue(d.allowed)
        // 关掉「区间内不发通知」= 允许陪聊在勿扰区间出声（弹通知），但语音仍由 ProactiveVoiceRules 静音
        assertTrue(dndVoiceOnly.allowChatInDnd)
    }

    @Test
    fun dnd_window_bounds_are_carried_over_verbatim() {
        val custom = DndSettings(enabled = true, startMinute = 12 * 60, endMinute = 14 * 60, suppressNotification = true)
        val cfg = ProactiveSettingsMapping.gateConfig(true, 3, custom)
        assertEquals(12 * 60, cfg.dndStartMinute)
        assertEquals(14 * 60, cfg.dndEndMinute)
        assertEquals(
            ProactiveBlockReason.DO_NOT_DISTURB,
            ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "13:00")), zone).reason
        )
        assertTrue(ProactiveGateRules.evaluate(ctx(cfg, at("2026-11-20", "15:00")), zone).allowed)
    }
}