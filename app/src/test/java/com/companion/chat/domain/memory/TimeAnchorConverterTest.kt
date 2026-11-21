package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** TimeAnchorConverter 单测（固定参考日：2026-11-20 周五）。任务 2.4 */
class TimeAnchorConverterTest {

    private val ref: LocalDate = LocalDate.of(2026, 11, 20) // 周五

    private fun c(s: String) = TimeAnchorConverter.convert(s, ref)

    @Test
    fun tomorrow_with_time_acceptance_example() {
        assertEquals("2026-11-21 07:00 叫我起床", c("明天 7 点叫我起床"))
        assertEquals("2026-11-21 07:00 叫我起床", c("明天7点叫我起床"))
    }

    @Test
    fun next_week_wednesday_acceptance_example() {
        // 2026-11-20 是周五，下周三 = 2026-11-25（本周一 11-16 + 7 + 2）
        assertEquals("2026-11-25 开会", c("下周三开会"))
    }

    @Test
    fun relative_days() {
        assertEquals("2026-11-22 开会", c("后天开会"))
        assertEquals("2026-11-23 交报告", c("3天后交报告"))
        assertEquals("2026-11-23 交报告", c("三天后交报告"))
        assertEquals("2026-11-19 去了公司", c("昨天去了公司"))
        assertEquals("2026-11-23 面试", c("大后天面试"))
        assertEquals("2026-11-06 出差", c("两周前出差"))
    }

    @Test
    fun weekday_variants() {
        assertEquals("2026-11-20 开会", c("周五开会"))     // 周五=今天
        assertEquals("2026-11-23 开会", c("周一开会"))     // 本周一已过→下周一
        assertEquals("2026-11-22 休息", c("这周日休息"))
        assertEquals("2026-11-25 见面", c("下个星期三见面"))
    }

    @Test
    fun absolute_dates() {
        assertEquals("2026-11-25 开会", c("11月25日开会"))
        assertEquals("2026-12-01 签约", c("2026-12-01 签约"))
        assertEquals("2026-11-25 交材料", c("2026年11月25日交材料"))
    }

    @Test
    fun times_with_prefix() {
        assertEquals("2026-11-22 15:00 开会", c("后天下午3点开会"))
        assertEquals("2026-11-20 20:30 看电影", c("今天晚上8点半看电影"))
        assertEquals("12:00 吃饭", c("中午12点吃饭"))
        assertEquals("02:00 起床", c("凌晨2点起床"))
        assertEquals("09:30 开会", c("9:30开会"))
        assertEquals("15:00 出发", c("下午3点出发"))
    }

    @Test
    fun no_anchor_returns_input() {
        assertEquals("我喜欢猫", c("我喜欢猫"))
        assertEquals("", c(""))
    }

    @Test
    fun time_without_date_keeps_only_time() {
        assertEquals("07:00 叫我", c("7点叫我"))
    }

    @Test
    fun next_month_and_next_year() {
        assertEquals("2026-12-15 开会", c("下个月15号开会"))
        assertEquals(ref.plusMonths(1).toString() + " 体检", c("下个月体检"))
        assertEquals("2027-03-05 签约", c("明年3月5日签约"))
        assertEquals("2027-03-20 签约", c("明年3月签约")) // 无日：取明年该月的今天(20号)
    }

    @Test
    fun chinese_numbers_extended() {
        assertEquals(ref.plusDays(30).toString() + " 交", c("三十天后交"))
        assertEquals(ref.plusDays(56).toString() + " 交", c("五十六天后交"))
        assertEquals(ref.plusDays(105).toString() + " 交", c("一百零五天后交"))
    }

    @Test
    fun convert_in_zone_api_works() {
        val out = TimeAnchorConverter.convertInZone("明天开会", java.time.ZoneId.of("Asia/Shanghai"))
        assertTrue(out.contains("开会"))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(out))
    }
}
