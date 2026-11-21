package com.companion.chat.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 顶栏铃铛（一键勿扰）的纯决策单测（2026-11-20）。
 *
 * 这个按钮以前是 `onDndClick = { /* TODO */ }`，点了没有任何反应。要断言的不只是「开关翻了」，
 * 还有三件容易做错的事：
 *  1. 在区间外点开启，**起点必须挪到现在**，否则区间没覆盖到此刻 = 依旧什么都没发生；
 *  2. 挪起点前必须**存快照**，关闭时还原 —— 否则用户点一下就把自己配的 23:00 永久改成 22:47；
 *  3. 已经落在区间里时**不要**动起点（那会把区间变长），只翻开关。
 */
class QuickDndTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 20)

    private fun at(hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // 默认区间：23:00 → 次日 07:00（跨午夜）
    private val start = 23 * 60
    private val end = 7 * 60

    @Test
    fun outside_window_turns_on_by_moving_start_to_now() {
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(10, 0),
            startMinute = start,
            endMinute = end,
            savedStartMinute = null,
            zone = zone
        )

        assertTrue("必须真的翻成开启", r.enabled)
        assertEquals("起点要挪到当前分钟（10:00 = 600）", 600, r.startMinute)
        assertEquals("原起点要存快照", start, r.savedStartMinute)
        assertFalse(r.clearSavedStart)
        assertTrue("文案要说明静音到什么时候：${r.toast}", r.toast.contains("明天 07:00"))
    }

    @Test
    fun outside_window_before_end_time_says_today() {
        // 用**不跨午夜**的窗口（23:00–23:30）才测得出「今天」分支：
        // 跨午夜窗口（23:00–07:00）在 05:00 是「区间内」，根本不会走到这里。
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(10, 0),
            startMinute = 23 * 60,
            endMinute = 23 * 60 + 30,
            savedStartMinute = null,
            zone = zone
        )
        assertTrue(r.toast.contains("今天 23:30"))
    }

    @Test
    fun already_inside_window_only_flips_the_switch() {
        // 23:30 本来就在 23:00–07:00 里：改起点会把区间拉长（23:30 → 明天 07:00 而不是 23:00 起）
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(23, 30),
            startMinute = start,
            endMinute = end,
            savedStartMinute = null,
            zone = zone
        )

        assertTrue(r.enabled)
        assertNull("区间内不许动起点", r.startMinute)
        assertNull("也不该写快照", r.savedStartMinute)
        assertTrue(r.toast.contains("现在就在勿扰时段内"))
    }

    @Test
    fun early_morning_is_inside_window() {
        // 06:00 属于跨午夜区间的后半段
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(6, 0),
            startMinute = start,
            endMinute = end,
            savedStartMinute = null,
            zone = zone
        )
        assertNull(r.startMinute)
    }

    @Test
    fun turning_off_restores_the_snapshot() {
        val r = QuickDnd.plan(
            currentEnabled = true,
            nowMillis = at(22, 47),
            startMinute = 22 * 60 + 47, // 上次铃铛写进去的临时起点
            endMinute = end,
            savedStartMinute = start,
            zone = zone
        )

        assertFalse(r.enabled)
        assertEquals("必须还原成用户原本的 23:00", start, r.startMinute)
        assertTrue("快照用完即弃", r.clearSavedStart)
        assertTrue(r.toast.contains("还原"))
    }

    @Test
    fun turning_off_without_snapshot_leaves_start_untouched() {
        // 用户从设置页自己开的勿扰：没有快照，关的时候不许改区间
        val r = QuickDnd.plan(
            currentEnabled = true,
            nowMillis = at(22, 47),
            startMinute = start,
            endMinute = end,
            savedStartMinute = null,
            zone = zone
        )

        assertFalse(r.enabled)
        assertNull(r.startMinute)
        assertFalse(r.clearSavedStart)
        assertTrue(r.toast.contains("勿扰已关闭"))
    }

    @Test
    fun second_toggle_on_keeps_the_original_snapshot() {
        // 已有快照时必须原样保留，不能被这次的起点覆盖（否则「关闭还原」还原到的是临时值）。
        // 注意不能拿「临时起点 == 现在」来测：那种状态下 inQuietHours 判定为区间内，走的是另一条分支。
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(10, 0),
            startMinute = 23 * 60,             // 用户当前配置的起点
            endMinute = 23 * 60 + 30,          // 不跨午夜，10:00 明确在区间外
            savedStartMinute = 20 * 60,        // 上一轮铃铛留下的原始起点 20:00
            zone = zone
        )

        assertEquals("快照必须保持上一轮的原值", 20 * 60, r.savedStartMinute)
        assertEquals(600, r.startMinute)
    }

    @Test
    fun empty_interval_edge_is_reported_not_silently_useless() {
        // 终点恰好等于当前分钟：区间会变成空 → 必须明确告知用户去设置里改，不能装作已生效
        val r = QuickDnd.plan(
            currentEnabled = false,
            nowMillis = at(7, 0),
            startMinute = start,
            endMinute = 7 * 60,
            savedStartMinute = null,
            zone = zone
        )

        assertTrue(r.enabled)
        assertEquals(7 * 60, r.startMinute)
        assertTrue("要提示调整：${r.toast}", r.toast.contains("请到设置里调整"))
    }

    @Test
    fun minute_of_day_is_simple_hour_times_sixty_plus_minute() {
        assertEquals(10 * 60, QuickDnd.minuteOfDay(at(10, 0), zone))
        assertEquals(23 * 60 + 59, QuickDnd.minuteOfDay(at(23, 59), zone))
        assertEquals(0, QuickDnd.minuteOfDay(at(0, 0), zone))
    }
}