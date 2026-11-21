package com.companion.chat.data.local.dao

import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `MemoryShortDao` 的 JVM 假实现（2026-11-20）。
 *
 * 供 `FollowupPortImplTest` 验证「点完成/放弃 → 删掉该链短期记忆」：
 *  - `insert` 自增 id 入 [items]；
 *  - `deleteByContentPrefix` 对齐真实 SQL：`content LIKE :prefix || '%'` 前缀匹配
 *    （`followup:42:` 不碰 `followup:420:`，因为后者内容不以该前缀开头）。
 */
class FakeMemoryShortDao : MemoryShortDao {

    val items = ArrayList<MemoryShortEntity>()
    private var seq = 0L

    override suspend fun insert(memory: MemoryShortEntity): Long {
        val id = if (memory.id == 0L) ++seq else memory.id
        items.removeAll { it.id == id }
        items.add(memory.copy(id = id))
        return id
    }

    override suspend fun update(memory: MemoryShortEntity) {
        val i = items.indexOfFirst { it.id == memory.id }
        if (i >= 0) items[i] = memory
    }

    override suspend fun delete(memory: MemoryShortEntity) {
        items.removeAll { it.id == memory.id }
    }

    override fun observeAll(): Flow<List<MemoryShortEntity>> = flowOf(items.toList())

    override suspend fun getAll(): List<MemoryShortEntity> = items.toList()

    override suspend fun getById(id: Long): MemoryShortEntity? = items.firstOrNull { it.id == id }

    override suspend fun updateWeight(id: Long, weight: Double) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(weight = weight)
    }

    override suspend fun updateFadeScore(id: Long, fadeScore: Double) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(fadeScore = fadeScore)
    }

    override suspend fun incrementAllFadeScore(increment: Double) {
        for (i in items.indices) items[i] = items[i].copy(fadeScore = items[i].fadeScore + increment)
    }

    override suspend fun count(): Int = items.size

    override suspend fun updateLastMentioned(id: Long, time: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(lastMentioned = time)
    }

    override suspend fun applyFeedback(
        id: Long,
        weight: Double,
        dismissCount: Int,
        coolingUntil: Long?,
        blacklisted: Boolean
    ) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(
            weight = weight,
            dismissCount = dismissCount,
            coolingUntil = coolingUntil,
            blacklisted = blacklisted
        )
    }

    override suspend fun findLatestContaining(keyword: String): MemoryShortEntity? =
        items.lastOrNull { it.content.contains(keyword) }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}