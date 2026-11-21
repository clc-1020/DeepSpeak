package com.companion.chat.domain.emotion

/**
 * 热络度（P3.1，2026-11-21）。
 *
 * 和「关系深度」是**两个量**，别再混：
 * ```
 * 关系深度 depth  ：只升不降（消息数 + 认识天数）→ 决定"撒娇的资格"
 * 热络度 warmth  ：0~1，会随没聊天而衰减 → 决定"此刻多亲"
 * ```
 * 为什么拆开：只让 depth 掉级会出现"她把我忘了"（记忆还在，关系却回到生疏 = 明显 OOC）。
 * 而 warmth 掉了只是"有点生分"，记忆一条不动 —— 用户能接受，也修得回来。
 *
 * **半衰期 14 天**：两周不聊掉一半。下限 [FLOOR] = 0.05（不到 0，永远留一丝熟悉感）。
 */
object Warmth {

    const val HALF_LIFE_DAYS = 14.0

    /** 下限：再久不聊也留一点，避免"从零开始"的冷漠感。 */
    const val FLOOR = 0.05

    /** 一轮对话回暖多少（约 2~3 轮从 0.35 回到 0.6 以上）。 */
    const val PER_TURN = 0.12

    /** 撒娇需要的最低热络度（配合关系亲近）。 */
    const val AFFECTION_MIN = 0.5

    /** 低于这个值：说话要收着点，不许撒娇（写进注入块的第三行）。 */
    const val COLD = 0.5

    /** 按时间衰减到"此刻"的值。 */
    fun valueAt(stored: Double, minutesSinceUpdate: Double): Double {
        if (stored.isNaN()) return FLOOR
        if (minutesSinceUpdate <= 0 || minutesSinceUpdate.isNaN()) return stored.coerceIn(FLOOR, 1.0)
        val factor = Math.pow(0.5, minutesSinceUpdate / (HALF_LIFE_DAYS * 24 * 60))
        return (stored * factor).coerceIn(FLOOR, 1.0)
    }

    /** 聊完一轮之后的值（回暖，封顶 1.0）。 */
    fun afterTurn(current: Double): Double = (current + PER_TURN).coerceIn(FLOOR, 1.0)

    fun label(value: Double): String = when {
        value >= 0.75 -> "很热络"
        value >= 0.5 -> "还行"
        value >= 0.25 -> "有点生分"
        else -> "很久没聊了"
    }

    /**
     * 注入块的第三行（只在偏冷时出现；热络时不占一个字）。
     * 例：`【热络度】有点生分（0.35）：别太亲昵、别撒娇，先随便聊聊。`
     */
    fun line(value: Double): String? {
        if (value >= COLD) return null
        return "【热络度】${label(value)}（${EmotionPhrase.fmt(value)}）：别太亲昵、别撒娇，先随便聊聊。"
    }
}