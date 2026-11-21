package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.domain.emotion.EmotionalMemoryScore
import com.companion.chat.domain.emotion.EmotionMemory
import com.companion.chat.domain.emotion.EmotionPhrase

/**
 * 情绪记忆拟人化服务（1.9.4.0 / vc66）。
 *
 * 把三条链路收敛到一处，避免逻辑散落导致回归：
 *  1. **写入**（[writeOrMerge]）：同类合并 / pivotal 分层 / 打分淘汰；
 *  2. **注入**（[injectable]）：情绪加权 + boost tiebreaker + 冷却；
 *  3. **回忆与厌恶**（[recall] / [onDislike] / [dismissById]）：boost 每日一次、扣分、7 天冷却、二次永久停止。
 *
 * 所有"记忆"只做**主动提起**的抑制，**绝不物理删除**——用户主动问起时照常能答。
 * 判定细节全在纯函数 [EmotionalMemoryScore]，这里只负责 IO 与编排。
 * 只用 DAO 既有的 getAll/getById/insert/update/delete，6 个测试 Fake DAO 零改动。
 */
class EmotionalMemoryService(
    private val longDao: MemoryLongDao,
    private val appStateDao: AppStateDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val today: () -> String = { java.time.LocalDate.now().toString() },
) {

    companion object {
        const val CATEGORY = "relationship"
        /** pivotal 写入阈值；普通记忆沿用 [EmotionMemory.MIN_INTENSITY]。 */
        const val PIVOTAL_MIN_INTENSITY = 0.85
        /** 普通记忆上限 4 条；pivotal 每种情绪 1 条、共 4 条。 */
        const val NORMAL_LIMIT = 4
        /** boost 每日一次：记在 app_state 的 key，值为 `id@日期` 的 CSV。 */
        private const val KEY_BOOST_DAY = "emotion_memory_boost_day"
    }

    private suspend fun emotionalRows(): List<MemoryLongEntity> =
        longDao.getAll().filter { it.category == CATEGORY && it.intensity > 0.0 }

    /** 写入一条情绪记忆：同类合并 → pivotal 分层 → 打分淘汰。返回是否新增了行。 */
    suspend fun writeOrMerge(emotion: String, intensity: Double, trigger: String, dateLabel: String): Boolean {
        if (trigger.isBlank() || intensity < EmotionMemory.MIN_INTENSITY) return false
        val picked = EmotionMemory.format(emotion, trigger, dateLabel)
        val all = emotionalRows()
        val t = now()
        val label = EmotionPhrase.label(emotion)

        // 1) 同类合并：同情绪 + trigger 前 8 字重合（零向量成本，和既有"同日同 trigger"口径一致）。
        val key = trigger.trim().take(8)
        val same = all.firstOrNull { it.content.contains(label) && it.content.contains(key) }
        if (same != null) {
            longDao.update(
                same.copy(
                    content = EmotionalMemoryScore.mergeText(same.content, same.occurrenceCount + 1),
                    intensity = maxOf(same.intensity, intensity),
                    occurrenceCount = same.occurrenceCount + 1,
                )
            )
            return false
        }

        // 2) pivotal：强度达阈且同情绪尚无更强 pivotal；同情绪已有 pivotal 时只在"新更强"才顶替。
        val shouldPivotal = intensity >= PIVOTAL_MIN_INTENSITY
        val existingPivotal = all.firstOrNull { it.pivotal && it.content.contains(label) }
        val promoted = shouldPivotal && (existingPivotal == null || intensity > existingPivotal.intensity)

        longDao.insert(
            MemoryLongEntity(
                content = picked,
                category = CATEGORY,
                createTime = t,
                intensity = intensity.coerceIn(0.0, 1.0),
                boost = 0.0,
                pivotal = promoted,
                occurrenceCount = 1,
            )
        )
        if (promoted && existingPivotal != null) longDao.update(existingPivotal.copy(pivotal = false))

        // 3) 打分淘汰：普通记忆超 4 条淘汰 score 最低者；pivotal 只被同情绪更强者顶替，不参与淘汰。
        val current = emotionalRows()
        val normals = current.filter { !it.pivotal }
        if (normals.size > NORMAL_LIMIT) {
            normals.minByOrNull { EmotionalMemoryScore.score(it.intensity, it.boost, it.createTime, t) }
                ?.let { longDao.delete(it) }
        }
        return true
    }

    /**
     * 本轮可注入的情绪记忆候选（按优先级排好）。
     * @param dominantEmotion 当前主导情绪（hers 最强项）；null = 不按情绪加权。
     * @return Pair(id, 正文)；调用方在**确实注入后**应逐条调用 [recall] 记账。
     */
    suspend fun injectable(dominantEmotion: String?, limit: Int = 2): List<Pair<Long, String>> {
        val all = emotionalRows()
        if (all.isEmpty()) return emptyList()
        val t = now()
        val day = today()
        val eligible = all.filter {
            EmotionalMemoryScore.canInject(
                pivotal = it.pivotal,
                lastInjectedAt = it.lastInjectedAt,
                lastInjectedDay = it.lastInjectedDay.ifBlank { null },
                today = day,
                nowMillis = t,
                blacklisted = it.coolingUntil == Long.MAX_VALUE,
                coolingUntil = it.coolingUntil,
                dislikeCount = it.dislikeCount,
            ) && it.content.isNotBlank()
        }
        if (eligible.isEmpty()) return emptyList()

        // 情绪匹配 +0.5 加权；boost 只作 tiebreaker（rankForInjection 内部处理）。
        val label = dominantEmotion?.let { EmotionPhrase.label(it) }
        val ranked = EmotionalMemoryScore.rankForInjection(
            eligible.map { m ->
                val emoBoost = if (label != null && m.content.contains(label)) 0.5f else 0f
                Triple(m.id, EmotionalMemoryScore.score(m.intensity, m.boost, m.createTime, t).toFloat() + emoBoost, m.boost)
            }
        )
        val byId = eligible.associateBy { it.id }
        return ranked.take(limit).mapNotNull { id -> byId[id]?.let { it.id to it.content } }
    }

    /** 一条记忆被真正注入后调用：刷新注入时刻；每日最多一次 boost。 */
    suspend fun recall(memoryId: Long) {
        val m = longDao.getById(memoryId) ?: return
        val t = now()
        val day = today()
        val already = appStateDao.get(KEY_BOOST_DAY)?.split(",")?.contains("$memoryId@$day") == true
        longDao.update(
            m.copy(
                boost = if (already) m.boost else EmotionalMemoryScore.applyBoost(m.boost),
                lastInjectedAt = t,
                lastInjectedDay = day,
            )
        )
        if (!already) {
            val old = appStateDao.get(KEY_BOOST_DAY).orEmpty()
            val kept = old.split(",").filter { it.isNotBlank() && !it.endsWith("@$day") }
            appStateDao.put(AppStateEntity(KEY_BOOST_DAY, (kept + "$memoryId@$day").joinToString(",").takeLast(400)))
        }
    }

    /** 用户明确表示厌恶：对最近注入的一条扣分 + 7 天冷却；第二次起永久停止。 */
    suspend fun onDislike(text: String): Boolean {
        if (!EmotionalMemoryScore.isNegativeUserText(text)) return false
        val recent = emotionalRows().filter { it.lastInjectedAt > 0L }.maxByOrNull { it.lastInjectedAt }
            ?: return false
        applyDislike(recent)
        return true
    }

    /** 通知"别提这个"：按 id 对指定长期记忆施加同样规则。 */
    suspend fun dismissById(memoryId: Long): Boolean {
        val m = longDao.getById(memoryId) ?: return false
        if (m.category != CATEGORY || m.intensity <= 0.0) return false
        applyDislike(m)
        return true
    }

    private suspend fun applyDislike(m: MemoryLongEntity) {
        val next = m.dislikeCount + 1
        longDao.update(
            m.copy(
                boost = EmotionalMemoryScore.applyDislike(m.boost),
                coolingUntil = if (next >= 2) Long.MAX_VALUE else now() + EmotionalMemoryScore.DISLIKE_COOLING_MS,
                dislikeCount = next,
            )
        )
    }
}
