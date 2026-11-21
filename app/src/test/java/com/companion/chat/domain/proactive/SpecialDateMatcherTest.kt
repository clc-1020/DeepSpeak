package com.companion.chat.domain.proactive

import com.companion.chat.domain.calendar.Festival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

/**
 * 特殊日期匹配：日期解析 + 命中 + 忽略年份。任务 V2.1.3。
 */
class SpecialDateMatcherTest {

    private val matcher = SpecialDateMatcher()
    private val day = LocalDate.of(2026, 11, 20)

    // ---------- 日期解析（四种格式） ----------

    @Test
    fun parse_yyyy_mm_dd() {
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate("2026-11-20"))
    }

    @Test
    fun parse_mm_dd() {
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate("11-20"))
    }

    @Test
    fun parse_mm_slash() {
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate("11/20"))
    }

    @Test
    fun parse_chinese() {
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate("11月20日"))
    }

    @Test
    fun parse_trims_whitespace() {
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate("  2026-11-20  "))
        assertEquals(MonthDay.of(11, 20), SpecialDateMatcher.parseDate(" 11/20 "))
    }

    @Test
    fun parse_invalid_returns_null() {
        assertNull(SpecialDateMatcher.parseDate("not-a-date"))
        assertNull(SpecialDateMatcher.parseDate("2026-13-40"))   // 非法月日
        assertNull(SpecialDateMatcher.parseDate("2026/11/20"))   // 斜杠格式不带年份，不支持
        assertNull(SpecialDateMatcher.parseDate(""))             // 空串
        assertNull(SpecialDateMatcher.parseDate("   "))
    }

    /**
     * 严格校验：**不存在的完整日期必须返回 null，绝不能静默调成合法值**。
     *
     * 2026 不是闰年，`2026-02-29` 不存在。若用 `MonthDay.parse` + 默认 SMART 解析，
     * 会静默返回 `02-28`（用户存的闰日生日被悄悄挪一天，且无任何报错）。
     * 本测试钉死：必须 null。
     */
    @Test
    fun nonexistent_full_date_returns_null() {
        assertNull(SpecialDateMatcher.parseDate("2026-02-29"))
        assertNull(SpecialDateMatcher.parseDate("2024-13-01"))
        assertNull(SpecialDateMatcher.parseDate("2024-00-10"))
        assertNull(SpecialDateMatcher.parseDate("2024-04-31"))   // 4 月只有 30 天
    }

    /** 闰年 2-29 是合法完整日期 → 返回 02-29（对比上一条）。 */
    @Test
    fun leap_day_full_date_is_valid() {
        assertEquals(MonthDay.of(2, 29), SpecialDateMatcher.parseDate("2024-02-29"))
    }

    /** 纯月日 `02-29` 合法：表示「每年 2 月 29 日」，闰年才命中。 */
    @Test
    fun leap_day_without_year_is_valid() {
        assertEquals(MonthDay.of(2, 29), SpecialDateMatcher.parseDate("02-29"))
    }

    // ---------- 用户日期命中（按月日、忽略年份） ----------

    @Test
    fun user_date_matches_same_day() {
        val hits = matcher.match(
            today = day,
            userDates = listOf("生日" to MonthDay.of(11, 20)),
            festivals = emptyList(),
        )
        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.User("生日")), hits)
    }

    /** 计划书验收：2026 存的「生日 2026-11-20」，2027/2028 的 11-20 仍命中。 */
    @Test
    fun user_date_matches_other_years() {
        val md = MonthDay.of(11, 20)
        assertEquals(
            listOf<SpecialDateHit>(SpecialDateHit.User("生日")),
            matcher.match(LocalDate.of(2027, 11, 20), listOf("生日" to md), emptyList()),
        )
        assertEquals(
            listOf<SpecialDateHit>(SpecialDateHit.User("生日")),
            matcher.match(LocalDate.of(2028, 11, 20), listOf("生日" to md), emptyList()),
        )
    }

    @Test
    fun user_date_not_matching_is_empty() {
        assertTrue(
            matcher.match(
                today = day,
                userDates = listOf("生日" to MonthDay.of(12, 25)),
                festivals = emptyList(),
            ).isEmpty()
        )
    }

    /** 解析失败（MonthDay=null）的那条被忽略，不崩。 */
    @Test
    fun unparseable_user_date_is_ignored() {
        val hits = matcher.match(
            today = day,
            userDates = listOf("坏数据" to null, "好生日" to MonthDay.of(11, 20)),
            festivals = emptyList(),
        )
        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.User("好生日")), hits)
    }

    // ---------- 内置节日 ----------

    @Test
    fun festival_matches_its_exact_date() {
        val festival = Festival.lunar("中秋", "2026-09-25")
        // 同一天命中
        val hits = matcher.match(
            today = LocalDate.of(2026, 9, 25),
            userDates = emptyList(),
            festivals = listOf(festival),
        )
        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.Festival("中秋")), hits)

        // 换年份的同一月日 → 不命中（农历每年公历日期不同）
        assertTrue(
            matcher.match(
                today = LocalDate.of(2030, 9, 25),
                userDates = emptyList(),
                festivals = listOf(festival),
            ).isEmpty()
        )
    }

    @Test
    fun festival_not_matching_is_empty() {
        assertTrue(
            matcher.match(
                today = day,
                userDates = emptyList(),
                festivals = listOf(Festival.solar("国庆", 10, 1)),
            ).isEmpty()
        )
    }

    // ---------- 组合与顺序 ----------

    /** 用户生日 + 内置节日同一天，都返回。 */
    @Test
    fun user_and_festival_both_returned() {
        val hits = matcher.match(
            today = day,
            userDates = listOf("生日" to MonthDay.of(11, 20)),
            festivals = listOf(Festival.solar("节日", 11, 20)),
        )
        assertEquals(
            listOf<SpecialDateHit>(SpecialDateHit.User("生日"), SpecialDateHit.Festival("节日")),
            hits,
        )
    }

    /** 全部为空 → 空列表（不是异常）。 */
    @Test
    fun nothing_matches_returns_empty() {
        assertTrue(matcher.match(day, emptyList(), emptyList()).isEmpty())
    }
}