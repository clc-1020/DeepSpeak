package com.companion.chat.domain.emotion

/**
 * 「想你」的产生规则（P2，2026-11-21）。
 *
 * **只在主动消息路径调用**（用户拍板 D2）—— 聊天时她不该莫名黏人。
 *
 * 曲线（单调、有上限，绝不到 1.0）：
 * ```
 * < 2h    → null（不产生）
 * 2~6h    → 0.30 → 0.50   （每小时 +0.05）
 * 6~24h   → 0.50 → 0.70   （每小时 +0.011）
 * >= 24h  → 0.70 封顶
 * ```
 * 为什么封顶在 0.7：再高她的每条消息都会带"你很久没来了"的味道，像讨债。
 */
object MissingEmotion {

    const val FLOOR_HOURS = 2.0
    const val CAP = 0.70

    /** @return 目标强度；[hoursAway] < [floorHours] 返回 null（不产生） */
    fun target(hoursAway: Double, floorHours: Double = FLOOR_HOURS): Double? {
        if (hoursAway.isNaN() || hoursAway < floorHours) return null
        val v = when {
            hoursAway < 6.0 -> 0.30 + (hoursAway - floorHours) * 0.05
            hoursAway < 24.0 -> 0.50 + (hoursAway - 6.0) * (0.20 / 18.0)
            else -> CAP
        }
        // 刚过门槛时给一个 0.3 的地板，避免出现 0.1 这种"几乎察觉不到"的想你
        return v.coerceAtMost(CAP).coerceAtLeast(0.30)
    }

    /** 触发语（写进情绪块的 trigger），让"想你"有具体来由。 */
    const val TRIGGER = "你有一阵没来了"
}