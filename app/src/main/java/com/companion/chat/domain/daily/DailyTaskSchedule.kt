package com.companion.chat.domain.daily

import java.time.Instant
import java.time.ZoneId

/**
 * 每日任务的时间计算（纯函数，可 JVM 单测）。
 */
object DailyTaskSchedule {

    private const val TITLE_MAX = 12

    /**
     * 下一次「每天 [hour]:[minute]」的触发时刻（epoch millis）。
     *
     * - 今天的这个点还没到（严格大于 [nowMillis]）→ 就排今天；
     * - 否则（今天已过 / 正好等于现在）→ 排明天。
     *
     * 用严格的 `>` ：闹钟在触发那一刻（now 会略大于等于触发点）回收时，算出来自然是明天，
     * 正好实现「响完自动排下一天」。
     */
    fun nextOccurrence(
        hour: Int,
        minute: Int,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val todayAt = nowZoned.toLocalDate().atTime(h, m).atZone(zone)
        val todayMillis = todayAt.toInstant().toEpochMilli()
        return if (todayMillis > nowMillis) {
            todayMillis
        } else {
            todayAt.plusDays(1).toInstant().toEpochMilli()
        }
    }

    /** 会话标题：取任务文字首行、去空白、超长截断；空则回退「每日任务」。 */
    fun titleFor(text: String): String {
        val base = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { "每日任务" }
        return if (base.length <= TITLE_MAX) base else base.substring(0, TITLE_MAX)
    }
}
