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
    /**
     * **短期**记忆合并阈值（0.85）。⚠️ 不要调低到 0.85 以下——短期记忆是「可执行的任务/待办」
     * （明天面试、7点叫我吃药、监督我背单词），误合并会直接丢一个提醒/一件事（「7点吃药」和「8点吃药」
     * 余弦≈0.9，一降就并掉），比"重复"危害大得多。真遇同义漏并，改 [longThreshold]，别动这里。
     */
    private val threshold: Float = 0.85f,
    /**
     * 长期记忆合并阈值（2026-11-22 设 0.80；2026-11-23 降到 0.75）。
     * **故意比短期(0.85)低**：长期是「稳定事实/偏好」，同义换说法更常见，误并危害也小于短期待办。
     * 真机样本：「用户希望获取 DeepSeek 官方最新公告的内容」与「用户此前关注 DeepSeek 的相关动态。」
     * 余弦实测 **0.755**，旧阈值 0.80 卡在线上漏并（用户反馈的重复两条）。降到 0.75 后能并掉；
     * 无关事实实测 0.36 左右，离得远，安全。
     */
    private val longThreshold: Float = 0.75f,
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

    /**
     * 长期记忆 upsert。返回 1=新增，0=合并/跳过。
     *
     * 2026-11-22 修复：以前**只在同 category 内比较** → 「用户说X很好喝(fact)」和「用户喜欢喝X(preference)」
     * 明明同义却因类别不同永不合并（真机踩到）。现改为**跨类别比较**，只保留一条安全护栏：
     * **allergy（过敏/忌口）不与其它类别互相合并**——避免把「对花生过敏」并成「不喜欢花生」而丢过敏事实；
     * 合并时保留 `best` 原 category，过敏安全信息不会被降级。
     */
    suspend fun upsertLong(content: String, category: String): Int {
        val c = content.trim()
        if (c.isEmpty()) return 0
        // category 先归一（中英文/拼写变体 → 固定枚举），避免碎片化导致漏去重
        val cat = com.companion.chat.domain.memory.MemoryCategory.normalize(category)
        val all = longDao.getAll()
        val newIsAllergy = cat == com.companion.chat.domain.memory.MemoryCategory.ALLERGY
        val emb = embedOrNull(c)

        if (emb == null) {
            if (all.any { it.content.trim() == c }) return 0
            longDao.insert(MemoryLongEntity(content = c, category = cat, createTime = now()))
            return 1
        }

        var best: MemoryLongEntity? = null
        var bestSim = -1f
        for (m in all) {
            val mcat = com.companion.chat.domain.memory.MemoryCategory.normalize(m.category)
            // 过敏与非过敏不互相合并（安全护栏）
            if ((mcat == com.companion.chat.domain.memory.MemoryCategory.ALLERGY) != newIsAllergy) continue
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
        if (best != null && bestSim >= longThreshold) {
            longDao.update(best.copy(content = c, createTime = now(), embedding = newBytes))
            return 0
        }
        longDao.insert(
            MemoryLongEntity(content = c, category = cat, createTime = now(), embedding = newBytes)
        )
        return 1
    }

    /**
     * 一次性整理：把已有的「普通长期记忆」（intensity<=0，**不动情绪记忆**）里互相重复的合并掉，
     * 并给缺向量的旧记录补算向量（否则它们永远无法参与语义去重/召回）。
     *
     * 2026-11-22 新增：修「历史上因『手动加记忆不走向量/去重』+『跨类别不比较』而残留的重复项」。
     * 每对同义（cos>=threshold 且不违反过敏护栏）只保留**内容更长**的那条，删掉另一条。幂等：
     * 没有重复时返回 0；情绪记忆（intensity>0）完全不碰（那是另一套子系统）。
     *
     * @return 被合并删除的条数
     */
    suspend fun consolidatePlainLong(threshold: Float = this.longThreshold): Int {
        val all = runCatching { longDao.getAll() }.getOrDefault(emptyList())
            .filter { it.intensity <= 0.0 }
        if (all.size < 2) {
            all.forEach { backfillVector(it) }
            return 0
        }

        val vec = HashMap<Long, FloatArray?>()
        all.forEach { m -> vec[m.id] = backfillVector(m) }

        val removed = HashSet<Long>()
        val order = all.sortedByDescending { it.content.length } // 内容更长的优先保留
        var merged = 0
        for (i in order.indices) {
            val a = order[i]
            if (a.id in removed) continue
            val va = vec[a.id] ?: continue
            val aIsAllergy =
                com.companion.chat.domain.memory.MemoryCategory.normalize(a.category) ==
                    com.companion.chat.domain.memory.MemoryCategory.ALLERGY
            for (j in i + 1 until order.size) {
                val b = order[j]
                if (b.id in removed) continue
                val bIsAllergy =
                    com.companion.chat.domain.memory.MemoryCategory.normalize(b.category) ==
                        com.companion.chat.domain.memory.MemoryCategory.ALLERGY
                if (aIsAllergy != bIsAllergy) continue // 过敏护栏
                val vb = vec[b.id] ?: continue
                val sim = runCatching { VectorMath.cosineSimilarity(va, vb) }.getOrNull() ?: continue
                if (sim >= threshold) {
                    longDao.delete(b)
                    removed.add(b.id)
                    merged++
                }
            }
        }
        return merged
    }

    /** 旧记录若无向量则补算并落库；返回其向量（失败/模型未就绪返回 null）。 */
    private suspend fun backfillVector(m: MemoryLongEntity): FloatArray? {
        bytesToVec(m.embedding)?.let { return it }
        val v = embedOrNull(m.content) ?: return null
        runCatching { longDao.update(m.copy(embedding = FloatArrayCodec.toBytes(v))) }
        return v
    }

    private fun bytesToVec(b: ByteArray?): FloatArray? =
        if (b == null) null else try { FloatArrayCodec.fromBytes(b) } catch (_: Exception) { null }

    private suspend fun embedOrNull(text: String): FloatArray? {
        val svc = try { embeddingProvider() } catch (_: Exception) { return null }
        if (!svc.isReady) return null
        return try { svc.embed(text) } catch (_: Exception) { null }
    }
}
