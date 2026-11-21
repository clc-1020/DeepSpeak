package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.memory.MemoryRecallRanker
import com.companion.chat.domain.memory.ScoredMemory

/**
 * 记忆召回服务。任务 2.6
 *
 * 流程：短期记忆按规则算分取 TOP3（[MemoryRecallRanker]）→ 交大模型挑一个（[selector]，
 * 单条候选直接选）→ 更新该记忆 `lastMentioned`（下次不再立刻重复）→ 返回选中记忆 id。
 * 无候选（全部不可召回）或模型明确认为不合适 → 返回 null（上层据此不发主动消息）。
 *
 * 任务 2.8：冷却中 / 已拉黑的记忆在 [MemoryRecallRanker] 里已被剔除，不会进入候选
 * （用户主动问仍走正常对话，不受影响）。
 *
 * `selector` 注入以便单测（真机用 [MemoryTopicSelector] 走大模型）。
 */
class MemoryRecallService(
    private val shortDao: MemoryShortDao,
    private val selector: suspend (ApiConfig, List<ScoredMemory>) -> Long?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val topK: Int = 3
) {

    /** 召回一条记忆用于主动话题；返回选中记忆 id，或 null（无可召回）。 */
    suspend fun recall(config: ApiConfig): Long? {
        val n = now()
        // 冷却中 / 已拉黑记忆由 MemoryRecallRanker 过滤（任务 2.8）。
        val candidates = MemoryRecallRanker.rank(shortDao.getAll(), n, topK)
        if (candidates.isEmpty()) return null
        val chosen = if (candidates.size == 1) candidates[0].id
        else (selector(config, candidates) ?: return null)
        shortDao.updateLastMentioned(chosen, n)
        return chosen
    }
}
