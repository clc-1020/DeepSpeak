package com.companion.chat.domain.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周期任务排程健康判定单测（2026-11-21）。
 *
 * 背景事故：用户把系统时间改到 2027-02-14 时排程，改回 2026-11-20 后
 * WorkManager 认为下次运行在 2027-02-15 —— 主动消息静默停摆 3 个月。
 *
 * 用例全部围绕「重排的收益 vs 频繁重置周期的代价」：
 * 漏判 = 继续静默停摆；误判 = 周期永远被重置成「现在 + 周期」，同样不发。
 */
class WorkScheduleHealthTest {

    private val h = 60L * 60 * 1000
    private val period = 2 * h            // 主动检查 2h
    private val now = 1_800_000_000_000L  // 某个抽象时刻

    @Test
    fun never_scheduled_needs_reschedule() {
        assertTrue(WorkScheduleHealth.needsReschedule(null, now, period))
        assertTrue("0 视为无效记录", WorkScheduleHealth.needsReschedule(0L, now, period))
    }

    /**
     * 本次事故原型：上次排程记在 2027-02-14（未来），现在回到 2026-11-21 → 时钟回拨 → 必须重排。
     */
    @Test
    fun clock_rolled_back_needs_reschedule() {
        val last = now + 90L * 24 * h // 记录值比现在晚 90 天（正是事故形态）
        assertTrue(WorkScheduleHealth.needsReschedule(last, now, period))
    }

    @Test
    fun recent_schedule_keeps_period() {
        // 1 小时前刚排过（2h 周期内）→ 不动，保住周期不被重置
        assertFalse(WorkScheduleHealth.needsReschedule(now - h, now, period))
        // 5 小时前（< 3 周期 = 6h）→ 仍不动
        assertFalse(WorkScheduleHealth.needsReschedule(now - 5 * h, now, period))
    }

    @Test
    fun long_silence_needs_reschedule() {
        // 超过 3 个周期（6h）没重排 → 认为被系统吞了，重排
        assertTrue(WorkScheduleHealth.needsReschedule(now - 7 * h, now, period))
    }

    @Test
    fun exactly_three_periods_is_still_ok() {
        // 边界：正好 3 个周期，不算过期（用 > 而非 >=，避免边界抖动反复重排）
        assertFalse(WorkScheduleHealth.needsReschedule(now - 3 * period, now, period))
    }

    @Test
    fun invalid_period_does_not_crash() {
        // 周期为 0/负：只做「回拨」与「无记录」判断，不因除零/负数误判
        assertFalse(WorkScheduleHealth.needsReschedule(now - h, now, 0L))
        assertTrue(WorkScheduleHealth.needsReschedule(null, now, 0L))
    }
}