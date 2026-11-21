package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.FloatArrayCodec
import com.companion.chat.data.embedding.VectorMath
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity

/**
 * 记忆语义去重。任务 2.3
 *
 * 规则（§任务 2.3）：
 *  - 新记忆先算 embedding；
 *  - 与**同类别**旧记忆（短期=全部短期；长期=同 category）算余弦相似度；
 *  - 相似度 > 阈值(默认 0.85) → 与最相似那条**合并**：更新 content / timestamp，`weight += 0.1`；
 *  - 否则插入新记忆。
 *
 * 兼容没有向量时的情况：
 *  - embedding 服务未就绪 / 生成失败 → 退回「完全相同文本」跳过（不阻断、不崩）。
 *  - 旧记忆 embedding 为空 → 就地把它的向量补算并落库（backfill），之后复用。
 *
 * 纯规则 + 依赖注入，可用假 DAO / 假 embedding 做 JVM 单测。
 */
class MemoryDeduplicator(
    private val shortDao: MemoryShortDao,
    private val longDao: MemoryLongDao,
    private val embeddingProvider: suspend () -> EmbeddingService,
    private val threshold: Float = 0.85f,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /** 短期记忆 upsert。返回 1=新增，0=合并/跳过。 */
    suspend fun upsertShort(content: String): Int {
        val c = content.trim()
        if (c.isEmpty()) return 0
        val all = shortDao.getAll()
        val emb = embedOrNull(c)

        if (emb == null) {
            // 无向量兜底：仅完全相同文本跳过
            if (all.any { it.content.trim() == c }) return 0
            shortDao.insert(MemoryShortEntity(content = c, timestamp = now(), weight = 1.0, fadeScore = 0.0))
            return 1
        }

        var best: MemoryShortEntity? = null
        var bestSim = -1f
        for (m in all) {
            var v = bytesToVec(m.embedding)
            if (v == null) {
                v = embedOrNull(m.content)
                if (v != null) shortDao.update(m.copy(embedding = FloatArrayCodec.toBytes(v))) else continue
            }
            val sim = VectorMath.cosineSimilarity(emb, v)
            if (sim > bestSim) {
                bestSim = sim
                best = m
            }
        }

        val newBytes = FloatArrayCodec.toBytes(emb)
        if (best != null && bestSim >= threshold) {
            shortDao.update(
                best.copy(content = c, timestamp = now(), weight = best.weight + 0.1, embedding = newBytes)
            )
            return 0
        }
        shortDao.insert(
            MemoryShortEntity(content = c, timestamp = now(), weight = 1.0, fadeScore = 0.0, embedding = newBytes)
        )
        return 1
    }

    /** 长期记忆 upsert（只在同 category 内比较）。返回 1=新增，0=合并/跳过。 */
    suspend fun upsertLong(content: String, category: String): Int {
        val c = content.trim()
        if (c.isEmpty()) return 0
        // category 先归一（中英文/拼写变体 → 固定枚举），避免碎片化导致漏去重
        val cat = com.companion.chat.domain.memory.MemoryCategory.normalize(category)
        val all = longDao.getAll().filter { com.companion.chat.domain.memory.MemoryCategory.normalize(it.category) == cat }
        val emb = embedOrNull(c)

        if (emb == null) {
            if (all.any { it.content.trim() == c }) return 0
            longDao.insert(MemoryLongEntity(content = c, category = cat, createTime = now()))
            return 1
        }

        var best: MemoryLongEntity? = null
        var bestSim = -1f
        for (m in all) {
            var v = bytesToVec(m.embedding)
            if (v == null) {
                v = embedOrNull(m.content)
                if (v != null) longDao.update(m.copy(embedding = FloatArrayCodec.toBytes(v))) else continue
            }
            val sim = VectorMath.cosineSimilarity(emb, v)
            if (sim > bestSim) {
                bestSim = sim
                best = m
            }
        }

        val newBytes = FloatArrayCodec.toBytes(emb)
        if (best != null && bestSim >= threshold) {
            longDao.update(best.copy(content = c, createTime = now(), embedding = newBytes))
            return 0
        }
        longDao.insert(
            MemoryLongEntity(content = c, category = cat, createTime = now(), embedding = newBytes)
        )
        return 1
    }

    private fun bytesToVec(b: ByteArray?): FloatArray? =
        if (b == null) null else try { FloatArrayCodec.fromBytes(b) } catch (_: Exception) { null }

    private suspend fun embedOrNull(text: String): FloatArray? {
        val svc = try { embeddingProvider() } catch (_: Exception) { return null }
        if (!svc.isReady) return null
        return try { svc.embed(text) } catch (_: Exception) { null }
    }
}
