package com.companion.chat.domain.calendar

import java.time.MonthDay

/**
 * 一个节日（内置的，来自构建期生成的 `festivals_2026_2036.json`）。任务 V2.1.3。
 *
 * **两种节日的匹配方式必须分开**（这是本类存在的理由）：
 * - 公历节日（元旦/劳动节/国庆）：每年同一天 → [date] 为 null，按 [monthDay] 匹配
 * - 农历节日（春节/元宵/…/重阳）：**每年公历日期都不同**（2026 元宵 3-3，2027 元宵 2-20）
 *   → 必须按 [date] **精确匹配**，绝不能按 [monthDay]
 *
 * 曾按 `monthDay` 统一匹配，结果是 `2026 元宵(3-3)` 会让 `2027-03-03` 误报元宵，
 * `2031 中秋(10-1)` 与 `2033 重阳(10-1)` 会让每年 `10-1` 一次报出三个节日。
 *
 * 注意：**用户自己存的生日/纪念日**是另一回事——那些按月日、忽略年份（见
 * [com.companion.chat.domain.proactive.SpecialDateMatcher]），因为它们本来就每年重复。
 */
data class Festival(
    val name: String,
    /** `"yyyy-MM-dd"`：农历节日为该年的确切公历日期；公历节日为 null。 */
    val date: String?,
    /**
     * 公历节日用它匹配；**农历节日为 null**（按 [date] 精确匹配，monthDay 无意义）。
     *
     * 刻意可空而不是「调试参考」：`null` 让「误把农历节日当公历匹配」的代码在编译期
     * 暴露（`monthDay == today` 会因类型不匹配报错），而不是静默产出错误结果。
     */
    val monthDay: MonthDay?,
) {

    /**
     * 这个节日是否落在 [target]。
     *
     * - 农历（[date] 非空）：`date == target` 精确比较
     * - 公历（[date] 为 null）：[monthDay] 非空，按它匹配，忽略年份
     */
    fun occursOn(target: java.time.LocalDate): Boolean =
        if (date != null) date == target.toString() else monthDay == MonthDay.from(target)

    companion object {
        /** 公历节日：每年同一月日，date 留 null。 */
        fun solar(name: String, month: Int, day: Int): Festival =
            Festival(name, null, MonthDay.of(month, day))

        /** 农历节日：date 为该年换算出的公历日期；monthDay 为 null（不可用于匹配）。 */
        fun lunar(name: String, date: String): Festival {
            val parts = date.split("-")
            require(parts.size == 3) { "invalid date: $date" }
            return Festival(name, date, monthDay = null)
        }
    }
}