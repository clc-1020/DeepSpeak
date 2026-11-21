package com.companion.chat.worker

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 环境整点检查的排程（V2.1.5，纯函数）。
 *
 * 环境检查是一个**每小时整点**的周期闹钟：`10:37` 排到 `11:00`，`11:00` 排到 `12:00`，
 * `23:30` 排到次日 `00:00`。
 *
 * @param zone 用户时区；默认系统时区。整点必须按**本地时间**算——若按 UTC 整点，
 *   UTC+8 用户会在 08:00 收到一次「北京时间 16:00」的环境检查，完全错位。
 */
object EnvironmentCheckScheduler {

    /**
     * 下一个整点（严格大于 now）。
     *
     * 边界：`11:00:00.000` 整 → 返回 `12:00`（不返回自身，否则闹钟到点立刻重排同刻，
     * 会忙循环）。`23:30` → 次日 `00:00`（`plusHours(1)` 自动进位，不会溢出到 24:00）。
     *
     * @return epoch millis
     */
    fun nextWholeHour(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return now.plusHours(1).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
    }
}