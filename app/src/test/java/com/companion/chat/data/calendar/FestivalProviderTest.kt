package com.companion.chat.data.calendar

import com.companion.chat.domain.calendar.Festival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

/**
 * 节日表 JSON 加载单测。任务 V2.1.3。
 *
 * 用 [FestivalProvider] 的 `loader` 注入路径喂真实生成的 JSON 字符串（测试目录不建 assets，
 * 沿用本项目「文件内容当参数传」的惯例）。
 */
class FestivalProviderTest {

    /** 生成器输出的真实结构的简化版。 */
    private val sampleJson = """
        {
          "lunar": [
            {"name": "春节", "dates": ["2026-02-17", "2027-02-06"]},
            {"name": "中秋", "dates": ["2026-09-25"]}
          ],
          "solar": [
            {"name": "国庆", "month": 10, "day": 1}
          ]
        }
    """.trimIndent()

    private fun provider(json: String = sampleJson) = FestivalProvider(loader = { json })

    @Test
    fun parses_lunar_and_solar() {
        val p = provider()
        val date = LocalDate.of(2026, 2, 17)
        val hits = p.getTodayFestivals(date)

        assertEquals(1, hits.size)
        assertEquals("春节", hits[0].name)
        assertEquals("2026-02-17", hits[0].date)
        // C3：农历节日 monthDay 必须为 null（防止被误当公历按月日匹配）
        assertEquals(null, hits[0].monthDay)
    }

    /** 公历节日 monthDay 非空（它是匹配依据）。 */
    @Test
    fun solar_has_month_day() {
        val p = provider()
        val hits = p.getTodayFestivals(LocalDate.of(2026, 10, 1))
        assertEquals(1, hits.size)
        assertEquals("国庆", hits[0].name)
        assertEquals(MonthDay.of(10, 1), hits[0].monthDay)
    }

    @Test
    fun solar_matches_any_year() {
        val p = provider()
        val date = LocalDate.of(2035, 10, 1) // 国庆，任意年份
        val hits = p.getTodayFestivals(date)

        assertEquals(1, hits.size)
        assertEquals("国庆", hits[0].name)
        assertTrue(hits[0].date == null) // 公历节日 date 为 null
    }

    /** 农历节日**必须精确日期**：2026 中秋的月日 9-25 换到 2036 年不再是中秋（农历每年公历不同）。 */
    @Test
    fun lunar_matches_exact_date_not_month_day() {
        val p = provider()
        // 2026 中秋 9-25
        val hits26 = p.getTodayFestivals(LocalDate.of(2026, 9, 25))
        assertEquals(1, hits26.size)
        assertEquals("中秋", hits26[0].name)

        // 同一月日换年份：2036-09-25 不是中秋（2036 中秋是 10-04）→ 命中 0
        assertTrue(p.getTodayFestivals(LocalDate.of(2036, 9, 25)).isEmpty())
    }

    @Test
    fun no_festival_returns_empty() {
        val p = provider()
        assertTrue(p.getTodayFestivals(LocalDate.of(2026, 3, 3)).isEmpty())
    }

    @Test
    fun malformed_json_throws_illegal_state() {
        // asset 是打包进 APK 的，坏了是构建事故 → 抛异常，不静默
        val p = provider(json = "not json")
        try {
            p.getTodayFestivals(LocalDate.of(2026, 2, 17))
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // 预期
        }
    }
}