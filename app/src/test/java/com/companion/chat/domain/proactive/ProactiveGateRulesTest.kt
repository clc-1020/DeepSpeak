package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ProactiveGateRules 决策闸门纯规则单测。任务 3.4 */
class ProactiveGateRulesTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 20)

    /** 当天某时刻的 epoch millis（上海时区）。 */
    private fun at(hour: Int, minute: Int): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private val hourMs = 3_600_000L

    // ---------- 冷却 ----------

    @Test
    fun cooldown_blocks_within_1h() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(nowMillis = now, lastInteractionMillis = now - 30 * 60_000L)
        assertEquals(ProactiveBlockReason.COOLDOWN, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    @Test
    fun cooldown_allows_after_1h() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(nowMillis = now, lastInteractionMillis = now - 2 * hourMs)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    /**
     * 回归：「冷却窗口必须小于 2h 检查周期」。
     * 早期冷却也是 2h，只要用户恰好在检查前一小时聊过天，这一轮就白跑，
     * 连续两轮就被吞掉 4 小时，表现为「2 小时一条根本不发」。
     */
    @Test
    fun cooldown_window_is_shorter_than_check_period() {
        assertTrue(
            "冷却 " + ProactiveGateRules.COOLDOWN_MILLIS + "ms 必须小于 2h 检查周期",
            ProactiveGateRules.COOLDOWN_MILLIS < 2 * hourMs
        )
    }

    @Test
    fun no_last_interaction_not_blocked_by_cooldown() {
        val ctx = ProactiveGateContext(nowMillis = at(12, 0), lastInteractionMillis = null)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    // ---------- 每日上限 ----------

    @Test
    fun daily_limit_blocks_at_3() {
        val ctx = ProactiveGateContext(nowMillis = at(12, 0), spontaneousSentToday = 3, dailyLimit = 3)
        assertEquals(ProactiveBlockReason.DAILY_LIMIT, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    @Test
    fun daily_limit_allows_below_limit() {
        val ctx = ProactiveGateContext(nowMillis = at(12, 0), spontaneousSentToday = 2, dailyLimit = 3)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    // ---------- 回复率降频 ----------

    @Test
    fun low_reply_rate_blocks_if_already_sent_today() {
        val ctx = ProactiveGateContext(
            nowMillis = at(12, 0), replyRate7d = 0.1, replyRateThreshold = 0.2,
            sentAnySpontaneousToday = true
        )
        assertEquals(ProactiveBlockReason.LOW_REPLY_RATE, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    @Test
    fun low_reply_rate_allows_first_of_day() {
        // 低回复率但今天还没发过 → 允许这一条（降频“一天一次”）
        val ctx = ProactiveGateContext(
            nowMillis = at(12, 0), replyRate7d = 0.1, sentAnySpontaneousToday = false
        )
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun null_reply_rate_not_blocked() {
        val ctx = ProactiveGateContext(nowMillis = at(12, 0), replyRate7d = null, sentAnySpontaneousToday = true)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    // ---------- 无钩子 ----------

    @Test
    fun no_hook_blocks() {
        val ctx = ProactiveGateContext(nowMillis = at(12, 0), hookAvailable = false)
        assertEquals(ProactiveBlockReason.NO_HOOK, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    // ---------- 勿扰 ----------

    @Test
    fun dnd_blocks_in_quiet_hours() {
        val now = at(2, 0) // 02:00 在 23:00-07:00 区间
        val ctx = ProactiveGateContext(nowMillis = now, dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60)
        assertEquals(ProactiveBlockReason.DO_NOT_DISTURB, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    @Test
    fun dnd_allows_outside_quiet_hours() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(nowMillis = now, dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun dnd_disabled_never_blocks() {
        val now = at(2, 0)
        val ctx = ProactiveGateContext(nowMillis = now, dndEnabled = false)
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun quiet_hours_wrap_midnight_helper() {
        assertTrue(ProactiveGateRules.inQuietHours(at(2, 0), 23 * 60, 7 * 60, zone))
        assertTrue(ProactiveGateRules.inQuietHours(at(23, 30), 23 * 60, 7 * 60, zone))
        assertFalse(ProactiveGateRules.inQuietHours(at(12, 0), 23 * 60, 7 * 60, zone))
        assertFalse(ProactiveGateRules.inQuietHours(at(7, 0), 23 * 60, 7 * 60, zone)) // end 开区间
        // 非跨午夜区间 13:00-14:00
        assertTrue(ProactiveGateRules.inQuietHours(at(13, 30), 13 * 60, 14 * 60, zone))
        assertFalse(ProactiveGateRules.inQuietHours(at(15, 0), 13 * 60, 14 * 60, zone))
    }

    // ---------- 指令提醒 / 追问：仅受勿扰 ----------

    @Test
    fun instruction_bypasses_cooldown_and_limit() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.INSTRUCTION,
            lastInteractionMillis = now - 1 * hourMs, // 冷却中
            spontaneousSentToday = 99,                // 超上限
            hookAvailable = false                     // 无钩子
        )
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun instruction_still_blocked_by_dnd() {
        val now = at(2, 0)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.INSTRUCTION,
            dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60
        )
        assertEquals(ProactiveBlockReason.DO_NOT_DISTURB, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    @Test
    fun followup_bypasses_like_instruction() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.FOLLOWUP,
            lastInteractionMillis = now - 1 * hourMs,
            spontaneousSentToday = 99, hookAvailable = false
        )
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    // ---------- 类别贯通（BUG-2） ----------

    /**
     * 计划 V2.1.4 验收：「环境钩子消息绕过冷却时间（但受勿扰 / 一天上限约束）」。
     * 早前闸门把一切当 SPONTANEOUS，环境消息会被冷却拦掉。
     */
    @Test
    fun environment_bypasses_cooldown_but_respects_daily_limit() {
        val now = at(12, 0)
        val cooling = ProactiveGateContext(
            nowMillis = now,
            lastInteractionMillis = now - 10 * 60_000L, // 刚聊过 10 分钟，闲聊必被拦
            category = ProactiveMessageCategory.ENVIRONMENT,
            spontaneousSentToday = 0,
            dailyLimit = 8
        )
        assertTrue(ProactiveGateRules.evaluate(cooling, zone).allowed)
        assertEquals(
            ProactiveBlockReason.DAILY_LIMIT,
            ProactiveGateRules.evaluate(cooling.copy(spontaneousSentToday = 8), zone).reason
        )
    }

    // ---------- 熬夜提醒（V2.1.4） ----------

    /** code → LATE_NIGHT_ALERT 映射。 */
    @Test
    fun late_night_maps_to_late_night_alert() {
        assertEquals(
            ProactiveMessageCategory.LATE_NIGHT_ALERT,
            ProactiveMessageCategory.ofPendingType("late_night")
        )
    }

    /**
     * 熬夜提醒豁免勿扰（计划书 §3.7）。
     *
     * 它天然发生在 23 点后，而默认勿扰正是 23:00–07:00——若按 ENVIRONMENT 处理，
     * 这个功能会被自己的勿扰规则 100% 拦死。
     */
    @Test
    fun late_night_bypasses_dnd() {
        val now = at(23, 30) // 勿扰区间内
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.LATE_NIGHT_ALERT,
            dndEnabled = true,
            dndStartMinute = 23 * 60,
            dndEndMinute = 7 * 60,
            spontaneousSentToday = 0
        )
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    /** 熬夜提醒仍受每日上限约束。 */
    @Test
    fun late_night_respects_daily_limit() {
        val now = at(23, 30)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.LATE_NIGHT_ALERT,
            dndEnabled = true,
            dndStartMinute = 23 * 60,
            dndEndMinute = 7 * 60,
            spontaneousSentToday = 8,
            dailyLimit = 8
        )
        assertEquals(
            ProactiveBlockReason.DAILY_LIMIT,
            ProactiveGateRules.evaluate(ctx, zone).reason
        )
    }

    /** 对照：环境钩子在勿扰区间**仍被拦**（熬夜豁免是 LATE_NIGHT_ALERT 独有）。 */
    @Test
    fun environment_still_blocked_by_dnd() {
        val now = at(23, 30)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            category = ProactiveMessageCategory.ENVIRONMENT,
            dndEnabled = true,
            dndStartMinute = 23 * 60,
            dndEndMinute = 7 * 60
        )
        assertEquals(
            ProactiveBlockReason.DO_NOT_DISTURB,
            ProactiveGateRules.evaluate(ctx, zone).reason
        )
    }

    @Test
    fun special_date_bypasses_cooldown_but_respects_daily_limit() {
        val now = at(12, 0)
        val cooling = ProactiveGateContext(
            nowMillis = now,
            lastInteractionMillis = now - 5 * 60_000L,
            category = ProactiveMessageCategory.SPECIAL_DATE,
            spontaneousSentToday = 2,
            dailyLimit = 8
        )
        assertTrue(ProactiveGateRules.evaluate(cooling, zone).allowed)
        assertEquals(
            ProactiveBlockReason.DAILY_LIMIT,
            ProactiveGateRules.evaluate(cooling.copy(spontaneousSentToday = 8), zone).reason
        )
    }

    @Test
    fun followup_bypasses_cooldown_and_daily_limit() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            lastInteractionMillis = now - 60_000L,
            category = ProactiveMessageCategory.FOLLOWUP,
            spontaneousSentToday = 99,
            dailyLimit = 8
        )
        assertTrue(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun spontaneous_still_blocked_by_cooldown() {
        val now = at(12, 0)
        val ctx = ProactiveGateContext(
            nowMillis = now,
            lastInteractionMillis = now - 10 * 60_000L,
            category = ProactiveMessageCategory.SPONTANEOUS
        )
        assertEquals(ProactiveBlockReason.COOLDOWN, ProactiveGateRules.evaluate(ctx, zone).reason)
    }

    // ---------- 验收综合 ----------

    @Test
    fun acceptance_chatted_recently_blocked() {
        // 计划验收：刚聊过（<1h）→ 主动消息被拦截。冷却窗口已从 2h 收窄到 1h（必须小于 2h 检查周期）
        val now = at(20, 0)
        val ctx = ProactiveGateContext(nowMillis = now, lastInteractionMillis = now - 20 * 60_000L)
        assertFalse(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }

    @Test
    fun acceptance_sent_3_today_then_blocked() {
        // 计划验收：一天发满 3 条后，后续被拦截
        val ctx = ProactiveGateContext(nowMillis = at(20, 0), spontaneousSentToday = 3, dailyLimit = 3)
        assertFalse(ProactiveGateRules.evaluate(ctx, zone).allowed)
    }
}
