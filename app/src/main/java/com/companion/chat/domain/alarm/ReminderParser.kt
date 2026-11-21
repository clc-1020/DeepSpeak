package com.companion.chat.domain.alarm

import com.companion.chat.domain.memory.TimeAnchorConverter
import java.time.LocalDate
import java.time.ZoneId

/**
 * 从用户话里解析「提醒/叫醒」指令 → 触发时刻 + 任务描述。任务 3.3（纯函数，可 JVM 单测）
 *
 * 复用 [TimeAnchorConverter.parseComponents] 做相对时间解析（明天/下周X/7点/晚上8点半…），
 * 再拼成绝对时间戳。
 *
 * 规则：
 *  - **必须能解析出时间点（时+分）**，否则返回 null（无法设精确闹钟）。
 *  - 无明确日期 → 用参照日「今天」；若该时刻已过（<=now）→ 顺延到明天同一时刻
 *    （例：傍晚说「7点叫我起床」→ 明早 7:00）。
 *  - 任务描述 = 去掉日期/时间锚点后的剩余文本；为空则用「提醒」兜底。
 */
object ReminderParser {

    /** 「N 分钟后 / N 小时后 / 半小时后」相对时长（支持阿拉伯数字与常见中文数字）。任务 3.7 增补 */
    private val RELATIVE_OFFSET = Regex(
        "([0-9]{1,4}|[零一二两三四五六七八九十百]{1,5}|半)\\s*(?:个)?\\s*(分钟|分|小时|钟头)\\s*(?:以后|之后|后)"
    )

    data class AlarmDraft(
        /** 触发时刻（epoch millis）。 */
        val triggerAtMillis: Long,
        /** 任务描述，如「叫我起床」。 */
        val taskDesc: String
    )

    fun parse(
        text: String,
        reference: LocalDate = LocalDate.now(ZoneId.systemDefault()),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): AlarmDraft? {
        // 优先处理「N 分钟后」这类相对时长（只给相对量、没有钟点）
        parseRelativeOffset(text, nowMillis)?.let { return it }

        val c = TimeAnchorConverter.parseComponents(text, reference) ?: return null
        val hour = c.hour ?: return null
        val minute = c.minute ?: return null
        val date = c.date ?: reference
        var instant = date.atTime(hour, minute).atZone(zone)
        if (instant.toInstant().toEpochMilli() <= nowMillis) {
            // 无明确日期 → 今天已过点则顺延明天；
            // 明确日期但已过去 → 视为无效（防止「我记得昨天 7 点…」这类闲聊被误排成立刻响）。
            if (c.date == null) {
                instant = instant.plusDays(1)
            } else {
                return null
            }
        }
        val task = c.remainder.ifBlank { "提醒" }
        return AlarmDraft(instant.toInstant().toEpochMilli(), task)
    }

    /** 解析「2 分钟后 / 3 个小时后 / 半小时后」→ 触发时刻 = now + 时长。 */
    private fun parseRelativeOffset(text: String, nowMillis: Long): AlarmDraft? {
        val m = RELATIVE_OFFSET.find(text) ?: return null
        val millis = matchToMillis(m) ?: return null
        val task = text.removeRange(m.range)
            .trim()
            .trim(',', '，', '。', '、', ' ')
            .replace(Regex("^(提醒我|提醒一下|叫我|叫醒我|记得|别忘了|别忘记|到点)"), "")
            .trim()
            .ifBlank { "提醒" }
        return AlarmDraft(nowMillis + millis, task)
    }

    /**
     * 「N 分钟后 / N 小时后 / 半小时后」→ **相对时长（毫秒）**。纯函数，不依赖任何时间基准。
     *
     * 公开的理由（2026-11-20，真机事故）：追问链的 `start` 提示只走
     * [TimeAnchorConverter.parseComponents]（**只认「日期+时刻」**），用户说
     * 「1分钟后开始，每5分钟问一次」时解析失败 → 静默退回 `now + interval`，
     * 表现为「说好 1 分钟后，结果 5 分钟才来」，事后才在 DB 里发现 triggerTime 不对。
     *
     * 规则本来就写在**这里**（任务 3.7 的提醒链），再抄一份到 followup 里迟早分叉，
     * 所以把「文本 → 时长」这一段独立成公开入口，两边共用同一套正则与数字解析。
     *
     * @return 毫秒数；语句里没有相对时长（或数量 <= 0）时返回 null
     */
    fun relativeOffsetMillis(text: String): Long? =
        RELATIVE_OFFSET.find(text)?.let { matchToMillis(it) }

    /** 相对时长 → 绝对触发时刻（epoch millis）；语句里没有相对时长时返回 null。 */
    fun relativeTriggerAt(text: String, nowMillis: Long): Long? =
        relativeOffsetMillis(text)?.let { nowMillis + it }

    /** 正则命中 → 毫秒。数字解析失败/非正数返回 null。 */
    private fun matchToMillis(m: MatchResult): Long? {
        val rawNum = m.groupValues[1]
        val unit = m.groupValues[2]
        val amount = if (rawNum == "半") 0.5 else (parseSmallNumber(rawNum) ?: return null).toDouble()
        if (amount <= 0.0) return null
        val isHour = unit == "小时" || unit == "钟头"
        val millis = (amount * if (isHour) 3_600_000.0 else 60_000.0).toLong()
        return if (millis <= 0L) null else millis
    }

    /** 小型数字解析（0-99 + 百），够用于分钟/小时。 */
    private fun parseSmallNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        if (raw == "百") return 100
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (raw.contains('十')) {
            val parts = raw.split('十')
            val tensStr = parts.getOrNull(0).orEmpty()
            val tens = if (tensStr.isEmpty()) 1 else digits[tensStr.first()] ?: return null
            val onesStr = parts.getOrNull(1).orEmpty()
            val ones = if (onesStr.isEmpty()) 0 else digits[onesStr.first()] ?: return null
            return tens * 10 + ones
        }
        return raw.firstOrNull()?.let { digits[it] }
    }
}
