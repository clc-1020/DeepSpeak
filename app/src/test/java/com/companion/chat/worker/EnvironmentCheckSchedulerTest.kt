package com.companion.chat.worker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 环境整点排程单测（V2.1.5）。 */
class EnvironmentCheckSchedulerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 20)

    private fun at(hour: Int, minute: Int, second: Int = 0, nanos: Int = 0): Long =
        day.atTime(LocalTime.of(hour, minute, second, nanos)).atZone(zone).toInstant().toEpochMilli()

    private fun next(hour: Int, minute: Int, second: Int = 0, nanos: Int = 0): Long =
        EnvironmentCheckScheduler.nextWholeHour(at(hour, minute, second, nanos), zone)

    @Test
    fun next_whole_hour_10_37() {
        assertEquals(at(11, 0), next(10, 37))
    }

    @Test
    fun exactly_on_hour_moves_to_next() {
        // 11:00:00.000 整 → 12:00（不返回自身，防止闹钟到点立刻重排同刻忙循环）
        assertEquals(at(12, 0), next(11, 0))
    }

    @Test
    fun exact_second_still_next_hour() {
        assertEquals(at(12, 0), next(11, 0, second = 1))
    }

    @Test
    fun late_night_rolls_to_next_day() {
        // 23:30 → 次日 00:00（跨日）
        val expected = day.plusDays(1).atTime(0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, next(23, 30))
    }

    @Test
    fun rolls_across_midnight_from_2300_exact() {
        val expected = day.plusDays(1).atTime(0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, next(23, 0))
    }

    @Test
    fun early_morning() {
        assertEquals(at(1, 0), next(0, 15))
    }
}