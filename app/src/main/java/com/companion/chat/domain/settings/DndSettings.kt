package com.companion.chat.domain.settings

import com.companion.chat.domain.proactive.ProactiveGateRules
import java.time.Instant
import java.time.ZoneId

/**
 * 勿扰（Do Not Disturb）设置（任务 7.2）。纯数据，可 JVM 单测。
 *
 * @param enabled 勿扰总开关
 * @param startMinute 开始（当天第几分钟，0..1439）
 * @param endMinute 结束（当天第几分钟；可小于 start 表示跨午夜，如 23:00–07:00）
 * @param suppressNotification 区间内**是否也不发 AI 消息**（独立开关，默认 true=彻底安静）
 * @param suppressInstruction 区间内是否**连用户自己设的提醒也压掉**（默认 **false=不压**）
 *
 * ⚠️ [suppressInstruction] 默认必须是 false：闹钟的本质是准时。用户设「6:30 叫我吃早餐」，
 * 若被勿扰顺延到 7:00 才响，等于核心功能失效（BUG-1）。想彻底安静的人自己打开它。
 *
 * 语音侧的语义（任务 4.3）保持不变：区间内主动消息**一律不自动念**，与这些开关无关。
 */
data class DndSettings(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    val suppressNotification: Boolean = true,
    val suppressInstruction: Boolean = false
) {
    /**
     * 系统勿扰期间是否**允许**陪聊出声（= 关掉「区间内不发通知」的另一种说法）。
     * 由 [suppressNotification] 派生，避免两个字段各存一份真相。
     */
    val allowChatInDnd: Boolean get() = !suppressNotification

    companion object {
        val DISABLED = DndSettings()
        const val MINUTE_MIN = 0
        const val MINUTE_MAX = 23 * 60 + 59
    }
}

/**
 * 勿扰判定（任务 7.2）。纯逻辑，不碰 Android。
 *
 * 三个消费方共用同一套判据，避免三处各写一遍时间比较：
 *  1. 语音：`ProactiveVoiceRules`（区间内不念，与通知开关无关）
 *  2. 闸门：`DefaultProactiveGate`（`suppressNotification=true` 时连自发消息都不发）
 *  3. 送达：`ProactiveDeliveryRunner`（`suppressNotification=true` 时把指令提醒**顺延**到区间结束）
 */
object DndPolicy {

    fun normalizeMinute(minute: Int): Int =
        minute.coerceIn(DndSettings.MINUTE_MIN, DndSettings.MINUTE_MAX)

    /** 当前是否处在勿扰区间内（开关关闭 → 永远 false）。 */
    fun isQuietHours(
        settings: DndSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!settings.enabled) return false
        return ProactiveGateRules.inQuietHours(
            nowMillis = nowMillis,
            startMinute = normalizeMinute(settings.startMinute),
            endMinute = normalizeMinute(settings.endMinute),
            zone = zone
        )
    }

    /** 区间内是否静音语音（任务 4.3 的语义，开关只看勿扰总开关）。 */
    fun silencesVoice(
        settings: DndSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = isQuietHours(settings, nowMillis, zone)

    /**
     * 区间内是否**连通知都不发**（独立开关）。只作用于 AI 主动消息（自发/追问/节日/环境）。
     */
    fun blocksNotification(
        settings: DndSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = settings.suppressNotification && isQuietHours(settings, nowMillis, zone)

    /**
     * 区间内是否**压掉用户自己设的指令提醒**（BUG-1，独立开关，默认关）。
     *
     * 与 [blocksNotification] 分开：闹钟必须准时，默认不压；只有用户明确打开
     * [DndSettings.suppressInstruction] 才顺延。
     */
    fun blocksInstruction(
        settings: DndSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = settings.suppressInstruction && isQuietHours(settings, nowMillis, zone)

    /**
     * 勿扰结束时刻（毫秒）；不在区间内返回 null。
     *
     * 用于把指令提醒顺延到区间结束再发（`ProactiveDeliveryRunner` + 延迟 Worker）。
     * 跨午夜时取「今天或明天的 endMinute」中第一个晚于 now 的时刻。
     */
    fun quietHoursEndMillis(
        settings: DndSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (!isQuietHours(settings, nowMillis, zone)) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val todayEnd = now.toLocalDate()
            .atStartOfDay(zone)
            .plusMinutes(normalizeMinute(settings.endMinute).toLong())
        val end = if (todayEnd.toInstant().toEpochMilli() > nowMillis) todayEnd else todayEnd.plusDays(1)
        return end.toInstant().toEpochMilli()
    }
}