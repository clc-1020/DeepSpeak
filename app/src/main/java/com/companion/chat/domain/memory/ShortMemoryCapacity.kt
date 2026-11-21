package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.domain.reminder.ReminderMemoryMatcher

/**
 * 短期记忆的容量淘汰策略（2026-11-20 纯函数）。
 *
 * 用户诉求：**攒满 50 条就清掉淡忘分最高的 20 条**，但正在提醒/监督中的那条不能动 ——
 * 这样记忆曲线更像人脑：近期/正在用的事留住，久远又模糊的事先忘。
 *
 * 与既有机制的分工：
 *  - `MemoryDecayService`（任务 2.5）每天给所有记忆 `fadeScore += …`，**从不删除**；
 *  - 本对象只负责「超过容量后挑出该忘的」，删除动作由执行器做。
 *
 * 判定顺序（每条都对应一个会误删的情况）：
 *  1. **没攒满就不动**（`size < max` → 空计划），避免天天删。
 *  2. **保护窗口**：`now - 24h` 之内写入的一律不动 —— 否则刚说「我明天面试」，
 *     一攒满就被清掉。
 *  3. **正在提醒/监督的不动**：用 [ReminderMemoryMatcher] 判断内容是否属于某条 pending
 *     提醒的任务（subscribe/督促），与「点完成时删记忆」用的是同一套规则。
 *  4. 其余按 **fadeScore 降序**（越模糊越先忘），同分按 **时间升序**（先忘更旧的）。
 *  5. 被「别提这个」拉黑的记忆**参与淘汰**（它永不召回，只占地方）。若将来要保留审计痕迹，
 *     把 `blacklisted` 加进过滤条件即可。
 *
 * 纯函数、零 Android 依赖，可直接 JVM 单测。
 */
object ShortMemoryCapacity {

    /** 触发清理的条数。 */
    const val DEFAULT_MAX = 50

    /** 一次清理掉的条数。 */
    const val DEFAULT_EVICT = 20

    /** 保护窗口：这么久之内写入的记忆不参与淘汰（24 小时）。 */
    const val PROTECT_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * @param all 当前全部短期记忆
     * @param protectedTasks 正在提醒/监督中的任务原文（`reminders.taskDesc`，仅 pending）
     * @param nowMillis 当前时间
     * @return 应当删除的记忆 id；空 = 什么都不做
     */
    fun plan(
        all: List<MemoryShortEntity>,
        protectedTasks: List<String>,
        nowMillis: Long,
        max: Int = DEFAULT_MAX,
        evict: Int = DEFAULT_EVICT,
        protectWindowMs: Long = PROTECT_WINDOW_MS,
    ): List<Long> {
        if (all.size < max) return emptyList()

        val cutoff = nowMillis - protectWindowMs
        val candidates = all.filter { m ->
            m.timestamp < cutoff &&
                protectedTasks.none { task -> ReminderMemoryMatcher.shouldClear(m.content, task) }
        }
        if (candidates.isEmpty()) return emptyList()

        return candidates
            .sortedWith(
                compareByDescending<MemoryShortEntity> { it.fadeScore }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
            .take(evict)
            .map { it.id }
    }
}