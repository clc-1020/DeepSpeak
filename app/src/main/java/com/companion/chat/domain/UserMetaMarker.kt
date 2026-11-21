package com.companion.chat.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 生成 DeepSeek 用户元数据标记 latest_reminder。任务 1.2 增补（纯函数，可单测）
 *
 * **格式恒为 5 段**：`<date>,<weekday>,<location>,<platform>,<language>`
 * 例：`2026-11-20,星期五,佛山市 12~18°C 降水概率98%,App,中文`
 *
 * ⚠️ **必须是 5 段**（V2.1.8 现场教训）：曾把天气作为**第 6 段**追加，真机上那条请求
 * 直接失败（HTTP 400，界面只显示「重试」）——同会话此前 5 段的请求全部成功。
 * 官方 `latest_reminder` 的字段结构是固定的，多一段会被服务端拒绝。
 * 因此天气**并入第 3 段**（location 是自由文本，语义上就是「用户所在地的补充信息」），
 * 段数不变。改动本函数时先想清楚：任何新增信息都只能塞进这 5 段里。
 *
 * 天气段（可选，V2.1.8）：
 * - **有**天气 → 拼在 location 后用空格分隔，模型据此回答「今天热不热」「要不要带伞」
 * - **拿不到**天气（无定位/网络失败/缓存缺失）→ location 只有城市名或为空，与旧行为一致，
 *   模型被告知「注记里没有天气就是这会儿拿不到，不要编」
 *
 * 注意：
 * - 必须紧挨在其后的 user 消息之前，不能插在 tool_use 与 tool_result 之间。
 * - 星期用目标语言书写（中文写「星期五」，英文写 Friday）。
 * - 各字段内的英文逗号/换行会被替换为空格，保证字段数不被内容打乱。
 */
object UserMetaMarker {

    fun build(
        date: LocalDate = LocalDate.now(),
        location: String = "",
        platform: String = "App",
        language: String = "中文",
        zone: ZoneId = ZoneId.systemDefault(),
        weather: String? = null
    ): String {
        // zone 目前仅用于对齐调用方语义（date 已由调用方按本地时区取好），保留参数方便将来扩展
        @Suppress("UNUSED_VARIABLE")
        val z = zone
        return listOf(
            date.toString(),
            weekday(date, language),
            place(location, weather),
            sanitize(platform),
            sanitize(language)
        ).joinToString(",")
    }

    /**
     * 第 3 段：地点 + 可选天气，空格分隔。两者都没有时返回空串（保留空的第 3 段，段数不变）。
     *
     * 天气不单独占段（见类注释的 400 教训）。
     */
    private fun place(location: String, weather: String?): String {
        val loc = sanitize(location)
        val w = weather?.let { sanitize(it) }
        return when {
            loc.isEmpty() && w.isNullOrEmpty() -> ""
            loc.isEmpty() -> w.orEmpty()
            w.isNullOrEmpty() -> loc
            else -> "$loc $w"
        }
    }

    /** 星期：中文 → 星期一…星期日；语言以 en 开头 → Monday…Sunday。 */
    fun weekday(date: LocalDate, language: String): String {
        val dow = date.dayOfWeek
        return if (language.trim().lowercase().startsWith("en")) {
            ENGLISH[dow] ?: dow.name
        } else {
            CHINESE[dow] ?: dow.name
        }
    }

    private fun sanitize(s: String): String =
        s.trim().replace(",", " ").replace("，", " ").replace("\n", " ").replace("\r", " ")

    private val CHINESE = mapOf(
        DayOfWeek.MONDAY to "星期一",
        DayOfWeek.TUESDAY to "星期二",
        DayOfWeek.WEDNESDAY to "星期三",
        DayOfWeek.THURSDAY to "星期四",
        DayOfWeek.FRIDAY to "星期五",
        DayOfWeek.SATURDAY to "星期六",
        DayOfWeek.SUNDAY to "星期日"
    )

    private val ENGLISH = mapOf(
        DayOfWeek.MONDAY to "Monday",
        DayOfWeek.TUESDAY to "Tuesday",
        DayOfWeek.WEDNESDAY to "Wednesday",
        DayOfWeek.THURSDAY to "Thursday",
        DayOfWeek.FRIDAY to "Friday",
        DayOfWeek.SATURDAY to "Saturday",
        DayOfWeek.SUNDAY to "Sunday"
    )
}
