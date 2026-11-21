package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MemoryShortEntity

/** 一条召回候选（记忆 id + 内容 + 得分）。 */
data class ScoredMemory(val id: Long, val content: String, val score: Double)

/**
 * 记忆召回排序（纯函数，可 JVM 单测）。任务 2.6 / 2.8
 *
 * 用 [MemoryScoring.recallScore]（权重×新鲜度 + 久未提及加分 − 淡忘分）给每条短期记忆算分，
 * 过滤掉不可召回（得分 ≤ 阈值）的，按得分降序取 TOP K。
 * 任务 2.8：先剔除「冷却中 / 已拉黑」的记忆（[MemoryFeedback.isSuppressed]），再算分排序。
 */
object MemoryRecallRanker {

    fun rank(
        memories: List<MemoryShortEntity>,
        nowMillis: Long,
        topK: Int = 3,
        halfLifeDays: Double = MemoryScoring.HALF_LIFE_DAYS,
        boostPerDay: Double = MemoryScoring.RECENCY_BOOST_PER_DAY,
        boostCapDays: Double = MemoryScoring.RECENCY_BOOST_CAP_DAYS,
        minScore: Double = MemoryScoring.RECALL_MIN_SCORE
    ): List<ScoredMemory> {
        if (topK <= 0) return emptyList()
        return memories
            .filterNot { MemoryFeedback.isSuppressed(it.blacklisted, it.coolingUntil, nowMillis) }
            .map { m ->
                val fresh = MemoryScoring.freshness(m.timestamp, nowMillis, halfLifeDays)
                val days = MemoryScoring.daysSinceMentioned(m.lastMentioned, nowMillis, boostCapDays)
                val score = MemoryScoring.recallScore(m.weight, fresh, m.fadeScore, days, boostPerDay)
                ScoredMemory(m.id, m.content, score)
            }
            .filter { it.score > minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }
}
