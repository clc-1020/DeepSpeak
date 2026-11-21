package com.companion.chat.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** ReminderParser 提醒时间解析单测。任务 3.3 */
class ReminderParserTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val ref = LocalDate.of(2026, 11, 20) // 周五

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun tomorrow_7_am_wake_up() {
        val d = ReminderParser.parse(
            "明天7点叫我起床", ref, zone, nowMillis = millis(ref, 6, 0)
        )
        assertNotNull(d)
        assertEquals(millis(LocalDate.of(2026, 11, 21), 7, 0), d!!.triggerAtMillis)
        assertEquals("叫我起床", d.taskDesc)
    }

    @Test
    fun evening_8_medicine_reminder() {
        val d = ReminderParser.parse(
            "晚上8点提醒我吃药", ref, zone, nowMillis = millis(ref, 12, 0)
        )
        assertNotNull(d)
        assertEquals(millis(ref, 20, 0), d!!.triggerAtMillis)
        assertEquals("提醒我吃药", d.taskDesc)
    }

    @Test
    fun bare_time_before_now_is_today() {
        // 现在 06:00，说「7点叫我起床」→ 今天 07:00
        val d = ReminderParser.parse("7点叫我起床", ref, zone, nowMillis = millis(ref, 6, 0))
        assertNotNull(d)
        assertEquals(millis(ref, 7, 0), d!!.triggerAtMillis)
    }

    @Test
    fun bare_time_after_now_rolls_to_tomorrow() {
        // 现在 09:00，说「7点叫我起床」→ 明天 07:00
        val d = ReminderParser.parse("7点叫我起床", ref, zone, nowMillis = millis(ref, 9, 0))
        assertNotNull(d)
        assertEquals(millis(LocalDate.of(2026, 11, 21), 7, 0), d!!.triggerAtMillis)
    }

    @Test
    fun half_past_and_afternoon_prefix() {
        val d = ReminderParser.parse("下午3点半提醒我开会", ref, zone, nowMillis = millis(ref, 9, 0))
        assertNotNull(d)
        assertEquals(millis(ref, 15, 30), d!!.triggerAtMillis)
        assertEquals("提醒我开会", d.taskDesc)
    }

    @Test
    fun no_time_returns_null() {
        // 只有日期无时刻 → 无法设精确闹钟
        assertNull(ReminderParser.parse("明天叫我起床", ref, zone, nowMillis = millis(ref, 9, 0)))
    }

    @Test
    fun no_anchor_returns_null() {
        assertNull(ReminderParser.parse("你好呀", ref, zone, nowMillis = millis(ref, 9, 0)))
        assertNull(ReminderParser.parse("", ref, zone, nowMillis = millis(ref, 9, 0)))
    }

    @Test
    fun explicit_date_in_past_returns_null() {
        // 明确日期（今天）且时刻已过 → 不排（防「我记得昨天7点…」这类闲聊被误排成立刻响）
        assertNull(ReminderParser.parse("今天7点叫我起床", ref, zone, nowMillis = millis(ref, 9, 0)))
    }

    // ---- 任务 3.7：相对时长「N 分钟后 / N 小时后 / 半小时后」----

    @Test
    fun relative_minutes_after() {
        val now = millis(ref, 9, 0)
        val d = ReminderParser.parse("提醒我2分钟后喝水", ref, zone, nowMillis = now)
        assertNotNull(d)
        assertEquals(now + 2 * 60_000L, d!!.triggerAtMillis)
        assertEquals("喝水", d.taskDesc)
    }

    @Test
    fun relative_half_hour_after() {
        val now = millis(ref, 9, 0)
        val d = ReminderParser.parse("半小时后提醒我关火", ref, zone, nowMillis = now)
        assertNotNull(d)
        assertEquals(now + 30 * 60_000L, d!!.triggerAtMillis)
        assertEquals("关火", d.taskDesc)
    }

    @Test
    fun relative_chinese_hours_after() {
        val now = millis(ref, 9, 0)
        val d = ReminderParser.parse("3个小时后叫我收衣服", ref, zone, nowMillis = now)
        assertNotNull(d)
        assertEquals(now + 3 * 3_600_000L, d!!.triggerAtMillis)
        assertEquals("收衣服", d.taskDesc)
    }

    // ---- 2026-11-20：把「文本 → 相对时长」公开出去（追问链的 start 提示也要用它）----

    @Test
    fun relative_offset_is_exposed_and_does_not_need_a_clock() {
        assertEquals(60_000L, ReminderParser.relativeOffsetMillis("1分钟后"))
        assertEquals(30 * 60_000L, ReminderParser.relativeOffsetMillis("半小时后"))
        assertEquals(2 * 3_600_000L, ReminderParser.relativeOffsetMillis("2小时后"))
        assertEquals(3 * 3_600_000L, ReminderParser.relativeOffsetMillis("3个小时后"))
        assertEquals(10 * 60_000L, ReminderParser.relativeOffsetMillis("十分钟以后"))
    }

    @Test
    fun relative_offset_returns_null_when_there_is_no_duration() {
        // 绝对时刻/纯钟点不能被相对解析吞掉，否则「今晚7点30分」会被当成相对时长
        assertNull(ReminderParser.relativeOffsetMillis("今晚7点30分"))
        assertNull(ReminderParser.relativeOffsetMillis("明天早上8点"))
        assertNull(ReminderParser.relativeOffsetMillis("不知道啥时候"))
        // 非正数一律当作没写
        assertNull(ReminderParser.relativeOffsetMillis("0分钟后"))
    }

    @Test
    fun relative_trigger_at_adds_duration_to_the_given_now() {
        val now = millis(ref, 9, 0)
        assertEquals(now + 60_000L, ReminderParser.relativeTriggerAt("1分钟后开始", now))
        assertEquals(now + 30 * 60_000L, ReminderParser.relativeTriggerAt("半小时后", now))
        assertNull(ReminderParser.relativeTriggerAt("今晚8点", now))
    }
}
