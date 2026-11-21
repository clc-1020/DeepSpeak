package com.companion.chat.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** DndSettings / DndPolicy 勿扰判据单测。任务 7.2 */
class DndPolicyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private val night = DndSettings(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60)

    @Test
    fun disabled_means_never_quiet() {
        val off = night.copy(enabled = false)
        assertFalse(DndPolicy.isQuietHours(off, at("2026-11-20", "23:30"), zone))
        assertFalse(DndPolicy.blocksNotification(off, at("2026-11-20", "23:30"), zone))
        assertFalse(DndPolicy.silencesVoice(off, at("2026-11-20", "23:30"), zone))
        assertNull(DndPolicy.quietHoursEndMillis(off, at("2026-11-20", "23:30"), zone))
    }

    @Test
    fun quiet_window_wraps_midnight() {
        assertTrue(DndPolicy.isQuietHours(night, at("2026-11-20", "23:30"), zone))
        assertTrue(DndPolicy.isQuietHours(night, at("2026-11-20", "03:00"), zone))
        assertTrue(DndPolicy.isQuietHours(night, at("2026-11-20", "06:59"), zone))
        assertFalse(DndPolicy.isQuietHours(night, at("2026-11-20", "07:00"), zone)) // end 开区间
        assertFalse(DndPolicy.isQuietHours(night, at("2026-11-20", "22:59"), zone))
        assertFalse(DndPolicy.isQuietHours(night, at("2026-11-20", "12:00"), zone))
    }

    @Test
    fun same_day_window_still_works() {
        val noon = DndSettings(enabled = true, startMinute = 12 * 60, endMinute = 14 * 60)
        assertTrue(DndPolicy.isQuietHours(noon, at("2026-11-20", "13:00"), zone))
        assertFalse(DndPolicy.isQuietHours(noon, at("2026-11-20", "14:00"), zone))
        assertFalse(DndPolicy.isQuietHours(noon, at("2026-11-20", "11:59"), zone))
    }

    @Test
    fun empty_window_is_never_quiet() {
        val empty = DndSettings(enabled = true, startMinute = 23 * 60, endMinute = 23 * 60)
        assertFalse(DndPolicy.isQuietHours(empty, at("2026-11-20", "23:00"), zone))
    }

    @Test
    fun notification_block_follows_the_independent_switch() {
        val quietButNotify = night.copy(suppressNotification = false)
        val now = at("2026-11-20", "23:30")
        // 区间内：语音一律静音，但通知按独立开关决定
        assertTrue(DndPolicy.silencesVoice(quietButNotify, now, zone))
        assertFalse(DndPolicy.blocksNotification(quietButNotify, now, zone))

        assertTrue(DndPolicy.silencesVoice(night, now, zone))
        assertTrue(DndPolicy.blocksNotification(night, now, zone))
    }

    /**
     * 回归（BUG-1）：勿扰**默认不压**用户自己设的提醒。
     *
     * 闹钟的本质是准时——用户设「6:30 叫我吃早餐」被勿扰顺延到 7:00 才响，等于核心功能失效。
     * 所以 `suppressInstruction` 默认 false；只有用户明确打开才顺延。
     */
    @Test
    fun instruction_is_not_suppressed_by_default() {
        val now = at("2026-11-20", "06:30") // 落在 23:00–07:00 区间内
        // 默认：AI 消息被压，但用户自己的提醒照发
        assertTrue(DndPolicy.blocksNotification(night, now, zone))
        assertFalse(DndPolicy.blocksInstruction(night, now, zone))
        // 明确打开「压掉我的提醒」才顺延
        val strict = night.copy(suppressInstruction = true)
        assertTrue(DndPolicy.blocksInstruction(strict, now, zone))
    }

    @Test
    fun voice_and_notification_are_both_false_outside_window() {
        val now = at("2026-11-20", "12:00")
        assertFalse(DndPolicy.silencesVoice(night, now, zone))
        assertFalse(DndPolicy.blocksNotification(night, now, zone))
        assertNull(DndPolicy.quietHoursEndMillis(night, now, zone))
    }

    @Test
    fun quiet_hours_end_is_same_day_when_before_midnight() {
        // 03:00 在 23:00–07:00 区间内 → 结束是「今天 07:00」
        val end = DndPolicy.quietHoursEndMillis(night, at("2026-11-20", "03:00"), zone)
        assertEquals(at("2026-11-20", "07:00"), end)
    }

    @Test
    fun quiet_hours_end_rolls_to_next_day_after_midnight_start() {
        // 23:30 在区间内 → 今天 07:00 已过 → 取「明天 07:00」
        val end = DndPolicy.quietHoursEndMillis(night, at("2026-11-20", "23:30"), zone)
        assertEquals(at("2026-11-21", "07:00"), end)
        assertTrue(end!! > at("2026-11-20", "23:30"))
    }

    @Test
    fun minute_normalization_clamps_to_valid_range() {
        assertEquals(0, DndPolicy.normalizeMinute(-30))
        assertEquals(1439, DndPolicy.normalizeMinute(9999))
        assertEquals(600, DndPolicy.normalizeMinute(600))
    }

    @Test
    fun disabled_settings_default_values() {
        val d = DndSettings.DISABLED
        assertFalse(d.enabled)
        assertEquals(23 * 60, d.startMinute)
        assertEquals(7 * 60, d.endMinute)
        assertTrue(d.suppressNotification)
    }
}