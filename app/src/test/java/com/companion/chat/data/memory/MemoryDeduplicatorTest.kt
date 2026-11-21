package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.NoopEmbeddingService
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryDeduplicator 语义去重单测（假 DAO + 假 embedding）。任务 2.3 */
class MemoryDeduplicatorTest {

    private class FakeShort : MemoryShortDao {
        val items = ArrayList<MemoryShortEntity>()
        private var seq = 0L
        override suspend fun insert(memory: MemoryShortEntity): Long {
            val withId = memory.copy(id = if (memory.id == 0L) ++seq else memory.id)
            items.add(withId); return withId.id
        }
        override suspend fun update(memory: MemoryShortEntity) {
            val i = items.indexOfFirst { it.id == memory.id }
            if (i >= 0) items[i] = memory
        }
        override suspend fun delete(memory: MemoryShortEntity) { items.removeAll { it.id == memory.id } }
        override fun observeAll(): Flow<List<MemoryShortEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryShortEntity> = items.toList()
        override suspend fun getById(id: Long): MemoryShortEntity? = items.firstOrNull { it.id == id }
        override suspend fun updateWeight(id: Long, weight: Double) {}
        override suspend fun updateFadeScore(id: Long, fadeScore: Double) {}
        override suspend fun incrementAllFadeScore(increment: Double) {}
        override suspend fun count(): Int = items.size
        override suspend fun deleteById(id: Long) { items.removeAll { it.id == id } }

        override suspend fun findLatestContaining(keyword: String): MemoryShortEntity? =
            items.lastOrNull { it.content.contains(keyword) }
        override suspend fun updateLastMentioned(id: Long, time: Long) {}
        override suspend fun applyFeedback(id: Long, weight: Double, dismissCount: Int, coolingUntil: Long?, blacklisted: Boolean) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(weight = weight, dismissCount = dismissCount, coolingUntil = coolingUntil, blacklisted = blacklisted)
        }
        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeLong : MemoryLongDao {
        val items = ArrayList<MemoryLongEntity>()
        private var seq = 0L
        override suspend fun insert(memory: MemoryLongEntity): Long {
            val withId = memory.copy(id = if (memory.id == 0L) ++seq else memory.id)
            items.add(withId); return withId.id
        }
        override suspend fun update(memory: MemoryLongEntity) {
            val i = items.indexOfFirst { it.id == memory.id }
            if (i >= 0) items[i] = memory
        }
        override suspend fun delete(memory: MemoryLongEntity) { items.removeAll { it.id == memory.id } }
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = items.toList()
        override suspend fun getById(id: Long): MemoryLongEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByCategory(category: String): List<MemoryLongEntity> = items.filter { it.category == category }
        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeEmb(private val map: Map<String, FloatArray>) : EmbeddingService {
        override val isReady = true
        override val dimensions = 3
        override suspend fun embed(text: String): FloatArray = map[text] ?: floatArrayOf(0f, 0f, 0f)
    }

    private val A = "我明天要面试"
    private val A2 = "明天去公司面试"
    private val B = "我喜欢猫"

    private val emb = FakeEmb(
        mapOf(
            A to floatArrayOf(1f, 0f, 0f),
            A2 to floatArrayOf(0.98f, 0.12f, 0f), // 与 A 余弦 ≈0.99 > 0.85
            B to floatArrayOf(0f, 1f, 0f)         // 与 A 正交 ≈0
        )
    )

    private fun dedup(short: FakeShort, long: FakeLong, e: EmbeddingService = emb) =
        MemoryDeduplicator(short, long, { e }, threshold = 0.85f, now = { 1000L })

    @Test
    fun same_text_twice_keeps_one() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertShort(A))
        assertEquals(0, d.upsertShort(A))
        assertEquals(1, s.items.size)
        assertEquals(1.1, s.items[0].weight, 1e-6)
    }

    @Test
    fun paraphrase_merges_and_updates_content() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertShort(A))
        assertEquals(0, d.upsertShort(A2)) // 近义合并
        assertEquals(1, s.items.size)
        assertEquals(A2, s.items[0].content)
        assertEquals(1.1, s.items[0].weight, 1e-6)
    }

    @Test
    fun unrelated_kept_separate() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertShort(A))
        assertEquals(1, d.upsertShort(B))
        assertEquals(2, s.items.size)
    }

    @Test
    fun weight_increments_on_each_merge() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        d.upsertShort(A)   // insert 1.0
        d.upsertShort(A2)  // merge 1.1
        d.upsertShort(A)   // merge 1.2
        assertEquals(1, s.items.size)
        assertEquals(1.2, s.items[0].weight, 1e-6)
    }

    @Test
    fun long_same_category_merges() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertLong(A, "plan"))
        assertEquals(0, d.upsertLong(A2, "plan"))
        assertEquals(1, l.items.size)
        assertEquals("plan", l.items[0].category)
    }

    @Test
    fun long_different_category_not_merged() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertLong(A, "plan"))
        assertEquals(1, d.upsertLong(A2, "health")) // 不同类别，不比较
        assertEquals(2, l.items.size)
    }

    @Test
    fun not_ready_falls_back_to_exact_text() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l, NoopEmbeddingService())
        assertEquals(1, d.upsertShort(A))
        assertEquals(0, d.upsertShort(A))   // 完全相同 → 跳过
        assertEquals(1, d.upsertShort(A2))  // 无法语义比较 → 视为不同插入
        assertEquals(2, s.items.size)
    }

    @Test
    fun merged_record_keeps_embedding_for_next_compare() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        d.upsertShort(A)
        d.upsertShort(A2)
        assertTrue(s.items[0].embedding != null && s.items[0].embedding!!.isNotEmpty())
    }

    @Test
    fun long_category_normalized_across_language_merges() = runBlocking {
        val s = FakeShort(); val l = FakeLong()
        val d = dedup(s, l)
        assertEquals(1, d.upsertLong(A, "过敏"))     // 中文 category
        assertEquals(0, d.upsertLong(A2, "allergy")) // 归一后同 allergy → 近义合并
        assertEquals(1, l.items.size)
        assertEquals("allergy", l.items[0].category)
    }
}