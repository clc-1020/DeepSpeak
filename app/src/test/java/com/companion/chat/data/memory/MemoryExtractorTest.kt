package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.NoopEmbeddingService
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.domain.memory.LongMemory
import com.companion.chat.domain.memory.MemoryExtraction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryExtractor.store 落库逻辑单测（假 DAO，JVM）。任务 2.2 */
class MemoryExtractorTest {

    // ---------- 假 DAO ----------
    private class FakeShort : MemoryShortDao {
        val items = ArrayList<MemoryShortEntity>()
        override suspend fun insert(memory: MemoryShortEntity): Long { items.add(memory); return items.size.toLong() }
        override suspend fun update(memory: MemoryShortEntity) {}
        override suspend fun delete(memory: MemoryShortEntity) { items.remove(memory) }
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
        override suspend fun insert(memory: MemoryLongEntity): Long { items.add(memory); return items.size.toLong() }
        override suspend fun update(memory: MemoryLongEntity) {}
        override suspend fun delete(memory: MemoryLongEntity) { items.remove(memory) }
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = items.toList()
        override suspend fun getById(id: Long): MemoryLongEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByCategory(category: String): List<MemoryLongEntity> = items.filter { it.category == category }
        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeDaily : MemoryDailyDao {
        val items = ArrayList<MemoryDailyEntity>()
        override suspend fun insert(log: MemoryDailyEntity): Long {
            val idx = items.indexOfFirst { it.date == log.date }
            if (idx >= 0) items[idx] = log else items.add(log)
            return if (log.id != 0L) log.id else items.size.toLong()
        }
        override suspend fun update(log: MemoryDailyEntity) {}
        override suspend fun delete(log: MemoryDailyEntity) { items.remove(log) }
        override fun observeAll(): Flow<List<MemoryDailyEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryDailyEntity> = items.toList()
        override suspend fun getByDate(date: String): MemoryDailyEntity? = items.firstOrNull { it.date == date }
        override suspend fun deleteAll() { items.clear() }
    }

    private fun extractor(
        short: FakeShort,
        long: FakeLong,
        daily: FakeDaily,
        emb: EmbeddingService = NoopEmbeddingService(),
        fixedNow: Long = 1000L,
        date: String = "2026-11-20",
        refDate: java.time.LocalDate = java.time.LocalDate.of(2026, 11, 20)
    ) = MemoryExtractor(
        apiClient = ChatApiClient(),
        memoryShortDao = short,
        memoryLongDao = long,
        memoryDailyDao = daily,
        embeddingServiceProvider = { emb },
        now = { fixedNow },
        today = { date },
        referenceDate = { refDate }
    )

    @Test
    fun store_inserts_into_each_table() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val ex = MemoryExtraction(
            short = listOf("准备简历"),
            long = listOf(LongMemory("对花生过敏", "allergy")),
            daily = listOf("去了公司")
        )
        val n = extractor(s, l, d).store(ex)
        assertEquals(3, n)
        assertEquals(1, s.items.size)
        assertEquals("准备简历", s.items[0].content)
        assertEquals(1000L, s.items[0].timestamp)
        assertEquals(1, l.items.size)
        assertEquals("allergy", l.items[0].category)
        assertEquals(1, d.items.size)
        assertTrue(d.items[0].events.contains("去了公司"))
    }

    @Test
    fun skips_exact_duplicates() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val e = extractor(s, l, d)
        e.store(MemoryExtraction(short = listOf("明天面试")))
        val n = e.store(MemoryExtraction(short = listOf("明天面试", "后天面试")))
        assertEquals(1, n)
        assertEquals(2, s.items.size)
    }

    @Test
    fun merges_daily_events_and_dedupes() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val e = extractor(s, l, d)
        e.store(MemoryExtraction(daily = listOf("去了公司")))
        val n = e.store(MemoryExtraction(daily = listOf("去了公司", "面试通过")))
        assertEquals(1, n)
        assertEquals(1, d.items.size)
        assertTrue(d.items[0].events.contains("去了公司"))
        assertTrue(d.items[0].events.contains("面试通过"))
    }

    @Test
    fun empty_extraction_writes_nothing() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val n = extractor(s, l, d).store(MemoryExtraction())
        assertEquals(0, n)
        assertTrue(s.items.isEmpty())
        assertTrue(l.items.isEmpty())
        assertTrue(d.items.isEmpty())
    }

    @Test
    fun embedding_null_when_service_not_ready() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        extractor(s, l, d, NoopEmbeddingService()).store(MemoryExtraction(short = listOf("x")))
        assertNull(s.items[0].embedding)
    }

    @Test
    fun embedding_written_when_ready() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val fake = object : EmbeddingService {
            override val isReady = true
            override val dimensions = 3
            override suspend fun embed(text: String) = floatArrayOf(1f, 0f, 0f)
        }
        extractor(s, l, d, fake).store(MemoryExtraction(short = listOf("x")))
        assertNotNull(s.items[0].embedding)
        assertEquals(12, s.items[0].embedding!!.size) // 3 dims * 4 bytes
    }

    @Test
    fun store_converts_relative_time_to_absolute() = runBlocking {
        val s = FakeShort(); val l = FakeLong(); val d = FakeDaily()
        val e = extractor(s, l, d) // refDate = 2026-11-20
        e.store(MemoryExtraction(short = listOf("明天 7 点叫我起床"), long = listOf(LongMemory("下周三开会", "plan"))))
        assertEquals("2026-11-21 07:00 叫我起床", s.items[0].content)
        assertEquals("2026-11-25 开会", l.items[0].content)
    }
}