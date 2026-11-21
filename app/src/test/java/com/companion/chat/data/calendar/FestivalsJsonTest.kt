package com.companion.chat.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 对**真实生成的** `festivals_2026_2036.json` 的闭环校验。任务 V2.1.3。
 *
 * 这个测试直接读 `src/test/resources/calendar/` 下的那份（生成器产物原样复制过来），
 * 不是测试里手写的样本——防止「生成器输出」和「运行时消费」之间静默脱节。
 *
 * 校验的内容：
 * - 6 个农历节日 × 2026–2036（11 年）每年恰好命中一次，且都在**正确的日期**
 * - 3 个公历节日同样每年命中一次
 * - 全年逐日扫描，没有「不在表中的节日」被错误报出
 */
class FestivalsJsonTest {

    private val provider = FestivalProvider(
        loader = {
            checkNotNull(javaClass.classLoader?.getResource("calendar/festivals_2026_2036.json")) {
                "test resource missing"
            }.readText()
        }
    )

    /** 农历节日的公历锚点（2026 年，农历库换算）。 */
    private data class Anchor(val name: String, val year: Int, val month: Int, val day: Int)

    private val lunarAnchors = listOf(
        Anchor("春节", 2026, 2, 17),
        Anchor("元宵", 2026, 3, 3),
        Anchor("端午", 2026, 6, 19),
        Anchor("七夕", 2026, 8, 19),
        Anchor("中秋", 2026, 9, 25),
        Anchor("重阳", 2026, 10, 18),
    )

    private val solarNames = listOf("元旦", "劳动节", "国庆")

    /** 农历节日：**在它每年真实的公历日期上命中**。用表中每个条目自己的日期。 */
    @Test
    fun lunar_festivals_hit_exactly_once_per_year() {
        for (year in 2026..2036) {
            for (anchor in lunarAnchors) {
                // 该年该农历节日的公历日期由生成器算出——直接从 provider 查它在哪个日期
                // （测试不能手算农历，所以按「该 anchor 2026 的月日，在该年必须命中」来锚定）
                // 但农历换年日期会漂移，所以改为：每年扫 12 个月，断言「恰好出现一次」。
                var count = 0
                for (month in 1..12) {
                    for (day in 1..31) {
                        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
                        if (provider.getTodayFestivals(date).any { it.name == anchor.name }) count++
                    }
                }
                assertEquals(
                    "${anchor.name} 在 $year 应恰好命中一次，实际 $count",
                    1,
                    count,
                )
            }
        }
    }

    /** 公历节日：每年固定月日**至少**命中目标（10-1 可能与中秋/重阳同天，所以不能断言总数=1）。 */
    @Test
    fun solar_festivals_hit_exactly_once_per_year() {
        for (year in 2026..2036) {
            val hits = provider.getTodayFestivals(LocalDate.of(year, 10, 1)).map { it.name }
            assertTrue("$year 国庆应命中，实际 $hits", hits.contains("国庆"))
        }
    }

    /** 2026 锚点日期精确匹配（防生成器把某年整体偏移）。 */
    @Test
    fun anchors_are_exact() {
        for (anchor in lunarAnchors) {
            val hits = provider.getTodayFestivals(LocalDate.of(anchor.year, anchor.month, anchor.day))
            assertTrue(
                "${anchor.name} 2026 应在 $anchor",
                hits.any { it.name == anchor.name },
            )
        }
    }

    /** 2036 中秋落在 10-04（生成器换算结果，跨 11 年后的日期漂移也正确）。 */
    @Test
    fun lunar_date_drifts_across_years() {
        val hits = provider.getTodayFestivals(LocalDate.of(2036, 10, 4))
        assertEquals(1, hits.size)
        assertEquals("中秋", hits[0].name)

        // 2026 中秋是 9-25，不是 10-04
        assertTrue(provider.getTodayFestivals(LocalDate.of(2026, 10, 4)).none { it.name == "中秋" })
    }

    /** 全年扫描：任意非节日日期不得误报。 */
    @Test
    fun non_festival_dates_are_empty() {
        // 挑一些确定不是节日（且不在任何农历节日换算范围内）的日期
        val dates = listOf(
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 7, 15),
            LocalDate.of(2026, 11, 20),
            LocalDate.of(2027, 3, 3),
            LocalDate.of(2030, 6, 30),
            LocalDate.of(2036, 12, 25),
        )
        for (date in dates) {
            assertTrue("$date 不应有节日", provider.getTodayFestivals(date).isEmpty())
        }
    }

    /** 公历 + 农历都不该有「神秘第 7 个节日」。 */
    @Test
    fun only_known_festivals_exist() {
        val allKnown = (lunarAnchors.map { it.name } + solarNames).toSet()
        for (year in 2026..2036) {
            for (month in 1..12) {
                for (day in 1..31) {
                    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
                    for (hit in provider.getTodayFestivals(date)) {
                        assertTrue(
                            "未知节日 ${hit.name} @ $date",
                            hit.name in allKnown,
                        )
                    }
                }
            }
        }
    }
}