package com.companion.chat.domain.daily

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 每日任务时间计算纯函数测试（2026-11-23）。 */
class DailyTaskScheduleTest {

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 23)

    private fun at(hour: Int, minute: Int, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun next_today_when_still_in_future() {
        // 现在 06:00，任务 07:00 → 今天 07:00
        assertEquals(at(7, 0), DailyTaskSchedule.nextOccurrence(7, 0, at(6, 0), zone))
    }

    @Test
    fun next_tomorrow_when_today_already_passed() {
        // 现在 08:00，任务 07:00 → 明天 07:00
        assertEquals(at(7, 0, day.plusDays(1)), DailyTaskSchedule.nextOccurrence(7, 0, at(8, 0), zone))
    }

    @Test
    fun next_tomorrow_when_exactly_now() {
        // 现在正好 07:00（闹钟回收那一刻），按严格 > 判定 → 明天 07:00
        assertEquals(at(7, 0, day.plusDays(1)), DailyTaskSchedule.nextOccurrence(7, 0, at(7, 0), zone))
    }

    @Test
    fun next_tomorrow_when_one_ms_after() {
        assertEquals(
            at(7, 0, day.plusDays(1)),
            DailyTaskSchedule.nextOccurrence(7, 0, at(7, 0) + 1, zone)
        )
    }

    @Test
    fun next_wraps_across_midnight() {
        // 现在 23:50，任务 00:10 → 明天 00:10
        assertEquals(
            at(0, 10, day.plusDays(1)),
            DailyTaskSchedule.nextOccurrence(0, 10, at(23, 50), zone)
        )
    }

    @Test
    fun invalid_time_is_clamped() {
        // hour 99 → 23, minute -5 → 0
        val out = DailyTaskSchedule.nextOccurrence(99, -5, at(0, 0), zone)
        assertEquals(at(23, 0), out)
    }

    @Test
    fun title_for_uses_first_line_and_truncates() {
        assertEquals("提醒我起床", DailyTaskSchedule.titleFor("提醒我起床"))
        assertEquals("第一行", DailyTaskSchedule.titleFor("第一行\n第二行"))
        assertEquals("每日任务", DailyTaskSchedule.titleFor("   "))
        assertEquals(12, DailyTaskSchedule.titleFor("一二三四五六七八九十十一十二十三").length)
    }
}
