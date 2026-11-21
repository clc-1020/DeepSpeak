package com.companion.chat.domain.memory

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 时间锚点转换（纯函数，可 JVM 单测）。任务 2.4
 *
 * 把记忆文本里的**相对时间**换成**绝对日期**、**时间点**换成 HH:mm，方便存储与后续判断
 * （例：「明天 7 点叫我起床」→「2026-11-21 07:00 叫我起床」；「下周三开会」→「2026-11-25 开会」）。
 *
 * 支持：
 *  - 相对日：今天/明天/后天/大后天/昨天/前天
 *  - N 天/周后(前)：3天后、两周前
 *  - 星期：下周X / 这周X / 本周X / 裸「周X」(取今天起最近)
 *  - 绝对日期：2026年11月25日、11月25日/号、2026-11-25
 *  - 时间：7点 / 7点半 / 7点30分 / 下午3点 / 晚上8点半 / 7:30
 *
 * 无任何锚点时原样返回。转换失败（异常）由调用方兜底。
 */
object TimeAnchorConverter {

    private data class DateHit(val range: IntRange, val date: LocalDate)
    private data class TimeHit(val range: IntRange, val hour: Int, val minute: Int)

    /** 结构化锚点解析结果：日期（可空）+ 时间（可空）+ 去掉锚点后的剩余文本。任务 3.3 复用。 */
    data class AnchorComponents(
        val date: LocalDate?,
        val hour: Int?,
        val minute: Int?,
        val remainder: String
    )

    /** 相对日词（顺序敏感：长词在前，避免「后天」先于「大后天」）。 */
    private val DAY_WORDS = listOf(
        Regex("大后天") to 3,
        Regex("后天") to 2,
        Regex("明天|明日") to 1,
        // 「明晚/明早」是 明天 的口语变体（2026-11-20 补）：不单列的话 dateHit 取不到日期，
        // `明晚8点` 会退化成「今天 20:00」，甚至因为已过点而被判无效。
        Regex("明晚|明早") to 1,
        Regex("今天|今日|今晚|今夜") to 0,
        Regex("昨天|昨日|昨晚") to -1,
        Regex("前天") to -2
    )

    /**
     * 结构化解析：返回 [AnchorComponents]（日期/时间/剩余文本），无任何时间锚点返回 null。
     * 供 [convert] 及提醒闹钟（任务 3.3）等服务复用，避免重复正则。
     *
     * @param reference 「今天」参照日（应为 App 时区当天）。
     */
    fun parseComponents(
        input: String,
        reference: LocalDate = LocalDate.now(ZoneId.systemDefault())
    ): AnchorComponents? {
        if (input.isBlank()) return null
        val dateHit = findRelativeMonth(input, reference)
            ?: findAbsoluteDate(input, reference)
            ?: findWeekday(input, reference)
            ?: findNDays(input, reference)
            ?: findNWeeks(input, reference)
            ?: findDayWord(input, reference)
        val timeHit = findTime(input)
        if (dateHit == null && timeHit == null) return null

        val cut = BooleanArray(input.length)
        dateHit?.range?.forEach { if (it in input.indices) cut[it] = true }
        timeHit?.range?.forEach { if (it in input.indices) cut[it] = true }
        val remainder = buildString {
            for (i in input.indices) if (!cut[i]) append(input[i])
        }.replace(Regex("\\s+"), " ").trim().trim(',', '，', '、', ' ').trim()

        return AnchorComponents(dateHit?.date, timeHit?.hour, timeHit?.minute, remainder)
    }

    /**
     * @param reference 「今天」参照日。**应为 App 时区/用户所在地时区的当天**（与 latest_reminder 一致）。
     *                  默认取系统时区的今天；跨时区场景应由调用方显式传入（见 [todayIn] / [convertInZone]）。
     */
    fun convert(input: String, reference: LocalDate = LocalDate.now(ZoneId.systemDefault())): String {
        if (input.isBlank()) return input
        val c = parseComponents(input, reference) ?: return input
        val parts = ArrayList<String>(3)
        c.date?.let { parts.add(it.toString()) }
        if (c.hour != null && c.minute != null) {
            parts.add(String.format(Locale.US, "%02d:%02d", c.hour, c.minute))
        }
        if (c.remainder.isNotBlank()) parts.add(c.remainder)
        return parts.joinToString(" ")
    }

    /** 取指定时区的今天（供跨时区场景显式使用）。 */
    fun todayIn(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    /** 按指定时区转换（跨时区/多地用户建议用这个，而不是依赖系统默认）。 */
    fun convertInZone(input: String, zone: ZoneId = ZoneId.systemDefault()): String =
        convert(input, todayIn(zone))

    /** 下个月X日 / 下个月 / 这个月X日 / 明年X月Y日 / 明年X月 / 明年。 */
    private fun findRelativeMonth(s: String, ref: LocalDate): DateHit? {
        // 下个月X日/号
        Regex("下(?:个)?月\\s*([0-9]{1,2})[日号]").find(s)?.let { m ->
            val base = ref.plusMonths(1)
            val d = m.groupValues[1].toInt().coerceIn(1, base.lengthOfMonth())
            return DateHit(m.range, LocalDate.of(base.year, base.monthValue, d))
        }
        // 下个月（无具体日）→ 一个月后的今天
        Regex("下(?:个)?月(?!\\s*[0-9一二三四五六七八九十])").find(s)?.let { m ->
            return DateHit(m.range, ref.plusMonths(1))
        }
        // 这个/本月X日
        Regex("(?:这|本)(?:个)?月\\s*([0-9]{1,2})[日号]").find(s)?.let { m ->
            val d = m.groupValues[1].toInt().coerceIn(1, ref.lengthOfMonth())
            return DateHit(m.range, LocalDate.of(ref.year, ref.monthValue, d))
        }
        // 明年X月Y日
        Regex("明年\\s*([0-9]{1,2})月\\s*([0-9]{1,2})[日号]").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
            return runCatching { LocalDate.of(ref.year + 1, mo, 1) }.getOrNull()?.let {
                DateHit(m.range, LocalDate.of(ref.year + 1, mo, d.coerceIn(1, it.lengthOfMonth())))
            }
        }
        // 明年X月（无日）→ 明年该月今天(日 clamp)
        Regex("明年\\s*([0-9]{1,2})月(?!\\s*[0-9])").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt()
            return runCatching { LocalDate.of(ref.year + 1, mo, 1) }.getOrNull()?.let {
                DateHit(m.range, LocalDate.of(ref.year + 1, mo, ref.dayOfMonth.coerceIn(1, it.lengthOfMonth())))
            }
        }
        // 明年（无更多）→ 一年后今天
        Regex("明年").find(s)?.let { m -> return DateHit(m.range, ref.plusYears(1)) }
        return null
    }

    private fun findDayWord(s: String, ref: LocalDate): DateHit? {
        var best: DateHit? = null
        for ((re, off) in DAY_WORDS) {
            val m = re.find(s) ?: continue
            val hit = DateHit(m.range, ref.plusDays(off.toLong()))
            if (best == null || m.range.first < best.range.first) best = hit
        }
        return best
    }

    private fun findNDays(s: String, ref: LocalDate): DateHit? {
        val re = Regex("([0-9]{1,3}|[零一二两三四五六七八九十百]{1,5})\\s*天\\s*(以后|之后|后|前)")
        val m = re.find(s) ?: return null
        val n = parseNumber(m.groupValues[1]) ?: return null
        val sign = if (m.groupValues[2] == "前") -1 else 1
        return DateHit(m.range, ref.plusDays((sign * n).toLong()))
    }

    private fun findNWeeks(s: String, ref: LocalDate): DateHit? {
        val re = Regex("([0-9]{1,3}|[零一二两三四五六七八九十百]{1,5})\\s*(?:个)?\\s*(?:周|星期|礼拜)\\s*(以后|之后|后|前)")
        val m = re.find(s) ?: return null
        val n = parseNumber(m.groupValues[1]) ?: return null
        val sign = if (m.groupValues[2] == "前") -1 else 1
        return DateHit(m.range, ref.plusWeeks((sign * n).toLong()))
    }

    private fun findWeekday(s: String, ref: LocalDate): DateHit? {
        val thisMonday = ref.minusDays((ref.dayOfWeek.value - 1).toLong())
        Regex("下(?:个)?(?:周|星期|礼拜)([一二三四五六日天])").find(s)?.let { m ->
            val w = weekdayIndex(m.groupValues[1]) ?: return@let
            return DateHit(m.range, thisMonday.plusWeeks(1).plusDays((w - 1).toLong()))
        }
        Regex("(?:这|本)(?:个)?(?:周|星期|礼拜)([一二三四五六日天])").find(s)?.let { m ->
            val w = weekdayIndex(m.groupValues[1]) ?: return@let
            return DateHit(m.range, thisMonday.plusDays((w - 1).toLong()))
        }
        Regex("(?:周|星期|礼拜)([一二三四五六日天])").find(s)?.let { m ->
            val w = weekdayIndex(m.groupValues[1]) ?: return@let
            var d = thisMonday.plusDays((w - 1).toLong())
            if (d.isBefore(ref)) d = d.plusWeeks(1) // 裸周X：取今天起最近的该星期
            return DateHit(m.range, d)
        }
        return null
    }

    private fun findAbsoluteDate(s: String, ref: LocalDate): DateHit? {
        Regex("([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})日?").find(s)?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            return runCatching { LocalDate.of(y, mo, d) }.getOrNull()?.let { DateHit(m.range, it) }
        }
        Regex("([0-9]{1,2})月([0-9]{1,2})[日号]").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
            return runCatching { LocalDate.of(ref.year, mo, d) }.getOrNull()?.let { DateHit(m.range, it) }
        }
        return null
    }

    private fun findTime(s: String): TimeHit? {
        val re = Regex(
            // 「今晚/明晚/今早/明早」这几个口语前缀必须单列（2026-11-20 修）：
            // 原来只有「晚上/早上」，`今晚8点` 里的「今晚」匹配不上，正则从「8点」处才命中 →
            // 前缀为空 → 不 +12 → 解析成**早上 8 点**。而 FollowupPrompt 的示例恰恰写的是
            // 「今晚8点」，等于把用户往坑里带（说 8 点、排成早 8 点，或判定已过直接退回 fallback）。
            "(上午|早上|早晨|凌晨|今早|明早|中午|下午|傍晚|晚上|今晚|今夜|明晚|夜里|夜间)?\\s*" +
                "([0-9]{1,2}|[零一二两三四五六七八九十]{1,3})\\s*" +
                "(?:点|时|[:：])\\s*" +
                "([0-9]{1,2}|[零一二两三四五六七八九十]{1,2}|半)?\\s*分?"
        )
        val m = re.find(s) ?: return null
        val prefix = m.groupValues[1]
        var hour = parseNumber(m.groupValues[2]) ?: return null
        val g3 = m.groupValues[3]
        val minute = when {
            g3.isEmpty() -> 0
            g3 == "半" -> 30
            else -> parseNumber(g3) ?: 0
        }
        val pm = prefix in setOf("下午", "傍晚", "晚上", "今晚", "今夜", "明晚", "夜里", "夜间")
        if (pm && hour in 1..11) hour += 12
        if (prefix == "中午" && hour in 1..6) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null
        return TimeHit(m.range, hour, minute)
    }

    private fun weekdayIndex(c: String): Int? = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "日", "天" -> 7; else -> null
    }

    /** 解析阿拉伯或中文数字（0-99）。 */
    private fun parseNumber(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.all { it in '0'..'9' }) return s.toIntOrNull()
        val map = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s == "十") return 10
        // 含「百」：一百 / 两百 / 三百零五 / 一百二十
        if (s.contains('百')) {
            val bIdx = s.indexOf('百')
            val hundreds = if (bIdx == 0) 1 else (map[s[bIdx - 1]] ?: return null)
            val rest = s.substring(bIdx + 1).removePrefix("零")
            val restVal = if (rest.isEmpty()) 0 else (parseNumber(rest) ?: return null)
            return hundreds * 100 + restVal
        }
        val idx = s.indexOf('十')
        if (idx >= 0) {
            val tens = if (idx == 0) 1 else (map[s[idx - 1]] ?: return null)
            val ones = if (idx == s.length - 1) 0 else (map[s[idx + 1]] ?: return null)
            return tens * 10 + ones
        }
        if (s.length == 1) return map[s[0]]
        return null
    }
}
