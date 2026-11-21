package com.companion.chat.domain.emotion

/**
 * 情绪衰减（纯函数，2026-11-21）。
 *
 * `v(t) = v0 × 0.5 ^ (Δ分钟 / 30)`，低于 [MIN_ALIVE] 直接删键（= 这项情绪消失了）。
 *
 * 为什么必须衰减：
 *  - 否则「昨晚的委屈」今早还在，她说话一直短，像记仇；
 *  - 单标签状态机要靠"显式重置"才不卡死，向量靠衰减自然退场，不需要写重置逻辑。
 *
 * 注意是**逐项独立**衰减，不是按总和衰减 —— 开心 0.5 + 得意 0.5 各自减半，不会互相吃掉。
 */
object EmotionDecay {

    const val HALF_LIFE_MINUTES = 30.0

    /** 低于这个值视为「没这个情绪了」。 */
    const val MIN_ALIVE = 0.2

    /**
     * @param minutes 距离上次更新过了多少分钟；<=0 或 NaN 时**原样返回**（不做衰减）
     * @return 衰减后的 map；全部低于 [MIN_ALIVE] 时返回空 map
     */
    fun apply(emotions: Map<String, Double>, minutes: Double): Map<String, Double> {
        if (emotions.isEmpty()) return emptyMap()
        if (minutes.isNaN() || minutes <= 0.0) return emotions

        val factor = Math.pow(0.5, minutes / HALF_LIFE_MINUTES)
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in emotions) {
            if (v.isNaN() || v <= 0.0) continue
            val decayed = v * factor
            if (decayed >= MIN_ALIVE) out[k] = decayed
        }
        return out
    }

    /** 便捷：用时间戳算分钟数并衰减。 */
    fun applyAt(emotions: Map<String, Double>, sinceMillis: Long, nowMillis: Long): Map<String, Double> {
        if (sinceMillis <= 0L || nowMillis <= sinceMillis) return emotions
        return apply(emotions, (nowMillis - sinceMillis).toDouble() / 60_000.0)
    }
}