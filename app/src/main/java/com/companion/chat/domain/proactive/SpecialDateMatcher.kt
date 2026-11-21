package com.companion.chat.domain.proactive

import com.companion.chat.domain.calendar.Festival
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * 特殊日期匹配：把用户存的日期字符串和内置节日揉成「今天该不该发祝福」。任务 V2.1.3。
 *
 * **纯函数，不碰 DAO**：输入输出全在参数里，主机可 100% 单测。
 *
 * 匹配规则：**按月日、忽略年份**。用户 2026 年存了「生日 2026-11-20」，到 2027/2028 的
 * 11-20 仍命中；2026-02-29 这种闰日存在后，平年不命中、闰年命中。
 */
class SpecialDateMatcher {

    /**
     * @param today 今天（注入以便测试）
     * @param userDates 已解析的用户日期列表：`Pair(名称, 月日?)`。**name 不能为 null**
     *   （[SpecialDateHookService] 保证跳过无名的）；月日解析失败为 null 表示该条忽略
     * @param festivals 内置节日（今天命中的即可，调用方自行过滤）
     * @return 命中的特殊日期（用户 + 内置），可能为空
     */
    fun match(
        today: LocalDate,
        userDates: List<Pair<String, MonthDay?>>,
        festivals: List<Festival>,
    ): List<SpecialDateHit> {
        val out = ArrayList<SpecialDateHit>(userDates.size + festivals.size)

        for ((name, monthDay) in userDates) {
            val md = monthDay ?: continue // 解析失败 → 忽略，不崩
            if (md == MonthDay.from(today)) {
                out += SpecialDateHit.User(name)
            }
        }

        for (festival in festivals) {
            // 必须走 occursOn：农历节日按精确日期，公历按 monthDay。不能直接比 monthDay，
            // 否则 2026 元宵(3-3) 会让 2027-03-03 误报元宵。
            if (festival.occursOn(today)) {
                out += SpecialDateHit.Festival(festival.name)
            }
        }
        return out
    }

    companion object {
        /**
         * 完整日期（含年份）专用：**STRICT + uuuu**。
         *
         * 两个细节都不能省：
         * - `uuuu` 而不是 `yyyy`：`yyyy` 是 year-of-era，在 `ResolverStyle.STRICT` 下缺 era
         *   会直接抛异常
         * - `STRICT` 而不是默认的 `SMART`：SMART 会把不存在的日期**静默调整**——
         *   `LocalDate.parse("2026-02-29")` 会返回 `2026-02-28`（2026 不是闰年），
         *   用户存的闰日生日被悄悄挪了一天，且没有任何报错
         *
         * 实测（JDK17）：SMART → 2026-02-29 得 02-28；STRICT → 正确抛 DateTimeParseException。
         */
        private val FULL_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

        /** `yyyy-MM-dd` 的形状识别（4-2-2 位数字）。 */
        private val FULL_DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")

        private val MM_DD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
        private val MM_SLASH: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
        private val M_CHINESE: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

        /** 不含年份的三种格式（月日语义，`02-29` 合法——它表示「每年 2 月 29 日」）。 */
        private val MONTH_DAY_FORMATS = listOf(MM_DD, MM_SLASH, M_CHINESE)

        /**
         * 解析用户记忆里的日期。
         *
         * 支持四种格式：
         * 1. `yyyy-MM-dd` → **先按完整日期严格校验，再取月日**
         * 2. `MM-dd`
         * 3. `MM/dd`
         * 4. `M月d日`（中文）
         *
         * 第 1 种必须单独走 [LocalDate] 严格解析，不能交给 `MonthDay.parse`：
         * 后者对 `2026-02-29` 会静默回退成 `02-28`（见 [FULL_DATE] 的说明）。
         * 严格校验的代价是「存了非法完整日期的那条会被判无效」——这是对的，
         * 因为 `2026-02-29` 本身就不存在，与其猜成 2-28 不如忽略。
         *
         * 后三种是纯月日语义：`02-29` 合法（每年 2 月 29 日，闰年才命中）。
         *
         * @return 月日；无法解析 → null（**不抛异常**，计划书验收项）
         */
        fun parseDate(raw: String): MonthDay? {
            val s = raw.trim()
            if (s.isEmpty()) return null

            if (FULL_DATE_SHAPE.matches(s)) {
                return try {
                    MonthDay.from(LocalDate.parse(s, FULL_DATE))
                } catch (e: DateTimeParseException) {
                    null // 不存在的完整日期（闰日越界 / 13 月 / 00 日）→ 该条无效
                }
            }

            for (formatter in MONTH_DAY_FORMATS) {
                try {
                    return MonthDay.parse(s, formatter)
                } catch (e: DateTimeParseException) {
                    // 尝试下一种
                }
            }
            return null
        }
    }
}

/** 一次特殊日期命中的结果。任务 V2.1.3。 */
sealed class SpecialDateHit {
    /**
     * 两个子类的公共名字。V2.1.7 的 `recallFestival` 直接取 `firstOrNull()?.name`，
     * 不能只匹配 `is Festival`——否则用户自己存的生日/纪念日永远发不出去。
     * 加在基类上不改两个 data class 的构造参数名，`copy(name=)` / 既有构造点全部兼容。
     */
    abstract val name: String

    /** 用户存的（生日/纪念日）。 */
    data class User(override val name: String) : SpecialDateHit()

    /** 内置节日。 */
    data class Festival(override val name: String) : SpecialDateHit()
}