package com.companion.chat.domain.settings

import com.companion.chat.domain.proactive.ProactiveGateRules
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 顶栏铃铛（一键勿扰）的纯决策（2026-11-20）。
 *
 * **背景**：这个按钮从 v1 起就是 `onDndClick = { /* TODO */ }`，点了没有任何反应 —— 用户实测报
 * 「右上角那个铃铛就是个摆设」。要让它有用，必须先回答一个语义问题：
 *
 * 现有勿扰是**区间制**（`dnd_enabled` + 22/23–07 的起止分钟）。白天点「开启勿扰」如果只翻开关，
 * 由于此刻不在区间内，**什么都不会发生** —— 那就是「点了没用」的另一种写法。
 *
 * 所以这里定：**铃铛 = 立刻静音到现在这段区间结束为止**。
 *  - 开：把 `startMinute` 改成「当前分钟」，`endMinute` 保持用户配置（自动跨午夜），
 *    并把被覆盖的原起点存进快照（[savedStartMinute]），用户再点一次能原样还原；
 *  - 已经落在区间内：只翻开关，不动区间（无意义地改起点会让区间变长）；
 *  - 关：开关关掉，若存在快照就还原，并清掉快照。
 *
 * 纯函数、零 Android 依赖：时间只从 `nowMillis` + `zone` 推导，可直接 JVM 单测。
 * 区间判定**复用** [ProactiveGateRules.inQuietHours]（跨午夜规则只有一份，不能在这里抄第二份）。
 *
 * @property enabled      要不要改成这个开关值
 * @property startMinute  非 null = 覆盖勿扰起点（null = 保持原值）
 * @property savedStartMinute 非 null = 写入快照（用于还原）
 * @property clearSavedStart   true = 删除快照
 * @property toast        给用户的即时反馈（开关改了但没反馈，等于还是「看不出来有没有生效」）
 */
object QuickDnd {

    data class Result(
        val enabled: Boolean,
        val startMinute: Int?,
        val savedStartMinute: Int?,
        val clearSavedStart: Boolean,
        val toast: String,
    )

    /**
     * @param currentEnabled   当前 `dnd_enabled`
     * @param nowMillis        现在（毫秒）：区间判定与「起点改到现在」都用它
     * @param startMinute      当前勿扰起点（当天第几分钟）
     * @param endMinute        当前勿扰终点（可小于起点 = 跨午夜）
     * @param savedStartMinute 上一次点铃铛时被覆盖的起点；无则 null
     */
    fun plan(
        currentEnabled: Boolean,
        nowMillis: Long,
        startMinute: Int,
        endMinute: Int,
        savedStartMinute: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val nowMinute = minuteOfDay(nowMillis, zone)

        // ① 关闭：有快照就还原，避免用户点的这一下永久改掉他配好的作息
        if (currentEnabled) {
            return if (savedStartMinute != null) {
                Result(
                    enabled = false,
                    startMinute = savedStartMinute,
                    savedStartMinute = null,
                    clearSavedStart = true,
                    toast = "勿扰已关闭（区间还原为 ${fmt(savedStartMinute)}–${fmt(endMinute)}）",
                )
            } else {
                Result(false, null, null, clearSavedStart = false, toast = "勿扰已关闭")
            }
        }

        // ② 此刻本来就在区间里：只翻开关，别动起点
        if (ProactiveGateRules.inQuietHours(nowMillis, startMinute, endMinute, zone)) {
            return Result(true, null, null, clearSavedStart = false, toast = "勿扰已开启：现在就在勿扰时段内")
        }

        // ③ 区间外：起点改到现在，到用户配的终点为止（终点 <= 现在 → 跨到明天）
        if (nowMinute == endMinute) {
            return Result(
                enabled = true,
                startMinute = nowMinute,
                savedStartMinute = savedStartMinute ?: startMinute,
                clearSavedStart = false,
                toast = "勿扰已开启，但结束时间与现在相同（${fmt(endMinute)}），请到设置里调整",
            )
        }
        val until = if (endMinute > nowMinute) "今天 ${fmt(endMinute)}" else "明天 ${fmt(endMinute)}"
        return Result(
            enabled = true,
            startMinute = nowMinute,
            // 已存在快照就别覆盖：第二次点开时不能把「临时起点」存成原始起点
            savedStartMinute = savedStartMinute ?: startMinute,
            clearSavedStart = false,
            toast = "勿扰已开启：到 $until 之前不打扰",
        )
    }

    /** 当天第几分钟（0..1439）。 */
    fun minuteOfDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    private fun fmt(minute: Int): String =
        String.format(Locale.US, "%02d:%02d", (minute / 60) % 24, minute % 60)
}