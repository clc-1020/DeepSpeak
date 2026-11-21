package com.companion.chat.domain.emotion

/**
 * 情绪合并 + 护栏（纯函数，2026-11-21）。
 *
 * 核心规则：**取大，不覆盖**
 * ```
 * new[k] = clamp( max(旧值衰减后[k], 模型报的值[k]) )
 * 模型没报的键 = 旧值衰减后[k]
 * ```
 *
 * 为什么不直接用模型值覆盖：模型偶尔抽风报个 0.3 的开心，会把刚攒起来的 0.7 委屈一键抹掉；
 * 取大 + 自然衰减，情绪只能"慢慢退场"，不会被一次误判翻盘。
 *
 * 三道护栏：
 *  1. 单值 ≤ [MAX_SINGLE]（0.9）—— 留出余地，情绪再强也还能正常说话
 *  2. 负面总和 ≤ [MAX_NEGATIVE_TOTAL]（0.9），超了按比例缩 —— 防"三种负面叠加成闹脾气"
 *  3. 负面**必须有 trigger**，没有就整批丢弃 —— 防凭空生气
 */
object EmotionMerge {

    const val MAX_SINGLE = 0.9
    const val MAX_NEGATIVE_TOTAL = 0.9

    /** trigger 写进注入块的长度上限（超了截断加「…」）。 */
    const val TRIGGER_MAX_CHARS = 24

    /**
     * @param oldDecayed 旧情绪**已衰减后**的值
     * @param reported   模型本次报的变化项（已归一化；也可传原始键，这里会再归一一次）
     * @param hasTrigger 本次是否给出了非空 trigger
     */
    fun merge(
        oldDecayed: Map<String, Double>,
        reported: Map<String, Double>,
        hasTrigger: Boolean,
    ): Map<String, Double> {
        val normalized = Emotion.normalizeMap(reported).filterKeys { hasTrigger || it !in Emotion.NEGATIVE }

        val merged = LinkedHashMap<String, Double>()
        oldDecayed.forEach { (k, v) -> merged[k] = v.coerceIn(0.0, MAX_SINGLE) }
        normalized.forEach { (k, v) ->
            merged[k] = maxOf(merged[k] ?: 0.0, v).coerceIn(0.0, MAX_SINGLE)
        }

        // 三项都低于存活线 → 视为平静（空 map），别留一堆 0.05 的噪音
        merged.entries.removeAll { it.value < EmotionDecay.MIN_ALIVE }
        return shrinkNegative(merged)
    }

    /** 负面总和超限 → 负面项等比缩到 0.9（正面项不受影响）。 */
    fun shrinkNegative(emotions: Map<String, Double>): Map<String, Double> {
        val negativeTotal = emotions.filterKeys { it in Emotion.NEGATIVE }.values.sum()
        if (negativeTotal <= MAX_NEGATIVE_TOTAL || negativeTotal <= 0.0) return emotions

        val ratio = MAX_NEGATIVE_TOTAL / negativeTotal
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in emotions) {
            out[k] = if (k in Emotion.NEGATIVE) v * ratio else v
        }
        return out
    }

    /** trigger 过长的截断（保留前 24 字 + 「…」）。 */
    fun trimTrigger(trigger: String): String {
        val t = trigger.trim()
        if (t.length <= TRIGGER_MAX_CHARS) return t
        return t.take(TRIGGER_MAX_CHARS) + "…"
    }
}