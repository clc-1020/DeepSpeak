package com.companion.chat.domain.memory

import kotlin.math.pow

/**
 * 记忆召回打分（纯函数，可 JVM 单测）。任务 2.5 / 2.6
 *
 * 得分 = 权重 × 时间新鲜度 − 淡忘分（`weight × freshness − fadeScore`）。
 *  - 新鲜度按「半衰期」指数衰减（越新越接近 1，越旧越趋近 0），默认半衰期 7 天。
 *  - 淡忘分 fadeScore 由每日衰减任务累加（任务 2.5），越大越难召回但不删除。
 *  - 得分 ≤ 阈值（默认 0）视为召不回。
 */
object MemoryScoring {

    /** 新鲜度半衰期（天）。 */
    const val HALF_LIFE_DAYS: Double = 7.0

    /** 召回最低得分阈值（≤ 该值不召回）。 */
    const val RECALL_MIN_SCORE: Double = 0.0

    private const val MILLIS_PER_DAY = 86_400_000.0

    /** 由记忆时间戳算新鲜度：现在=1.0，越旧按半衰期指数衰减到 0。未来时间按 1.0 处理。 */
    fun freshness(timestampMillis: Long, nowMillis: Long, halfLifeDays: Double = HALF_LIFE_DAYS): Double {
        val ageDays = (nowMillis - timestampMillis) / MILLIS_PER_DAY
        return freshnessByAgeDays(ageDays, halfLifeDays)
    }

    fun freshnessByAgeDays(ageDays: Double, halfLifeDays: Double = HALF_LIFE_DAYS): Double {
        if (ageDays <= 0.0) return 1.0
        if (halfLifeDays <= 0.0) return 0.0
        return 0.5.pow(ageDays / halfLifeDays)
    }

    /** 召回得分。freshness 由 [freshness] 先算好传入。 */
    fun score(weight: Double, freshness: Double, fadeScore: Double): Double =
        weight * freshness - fadeScore

    /** 便捷：直接从权重/时间戳/淡忘分算得分。 */
    fun scoreOf(
        weight: Double,
        timestampMillis: Long,
        fadeScore: Double,
        nowMillis: Long,
        halfLifeDays: Double = HALF_LIFE_DAYS
    ): Double = score(weight, freshness(timestampMillis, nowMillis, halfLifeDays), fadeScore)

    // ---------- 任务 2.6：久未提及加分 + 完整召回算分 ----------

    /** 每「久未提及」一天的加分（任务 2.6）。系数偏保守，避免老记忆靠加分「霸榜」。 */
    const val RECENCY_BOOST_PER_DAY: Double = 0.03

    /** 久未提及加分的天数上限（防止无限增长；60 天封顶最高 +1.8）。 */
    const val RECENCY_BOOST_CAP_DAYS: Double = 60.0

    /**
     * 距上次提起的天数（用于久未提及加分）。从未提起（null）按上限 [capDays] 处理（给满加分），
     * 负数（未来时间）按 0。
     */
    fun daysSinceMentioned(
        lastMentionedMillis: Long?,
        nowMillis: Long,
        capDays: Double = RECENCY_BOOST_CAP_DAYS
    ): Double {
        if (lastMentionedMillis == null) return capDays
        val d = (nowMillis - lastMentionedMillis) / MILLIS_PER_DAY
        return d.coerceIn(0.0, capDays)
    }

    /**
     * 完整召回算分（任务 2.6）：`权重 × 时间新鲜度 + 久未提及天数 × 每日加分 − fadeScore`。
     */
    fun recallScore(
        weight: Double,
        freshness: Double,
        fadeScore: Double,
        daysSinceMentioned: Double,
        boostPerDay: Double = RECENCY_BOOST_PER_DAY
    ): Double = weight * freshness + daysSinceMentioned * boostPerDay - fadeScore

    /** 是否可召回（得分高于阈值）。 */
    fun isRecallable(score: Double, minScore: Double = RECALL_MIN_SCORE): Boolean = score > minScore

    /**
     * 从 (id, score) 列表选 TOP k（按得分降序，自动剔除不可召回的）。
     * 任务 2.6 会在此基础上叠加「久未提及加分」，这里先给通用工具。
     */
    fun topK(candidates: List<Pair<Long, Double>>, k: Int, minScore: Double = RECALL_MIN_SCORE): List<Long> {
        if (k <= 0) return emptyList()
        return candidates.asSequence()
            .filter { it.second > minScore }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }
}
