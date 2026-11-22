package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.local.dao.FakeMemoryShortDao
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MemoryContextProvider` 接线单测（2026-11-21）。
 *
 * 策略在 `MemoryContextBlockTest` 里测；这里只测**读表 + 相似度 + 降级**：
 *  - embedding 未就绪 → 短期不注入，但长期/日记照常（保证功能不全废）；
 *  - 相似度高的一条排在前面并被注入；
 *  - 任何异常都不抛，返回 null。
 */
class MemoryContextProviderTest {

    private class FakeShort(private val rows: List<MemoryShortEntity>) : MemoryShortDao by FakeMemoryShortDao() {
        override suspend fun getAll(): List<MemoryShortEntity> = rows
    }

    private class FakeLong(private val rows: List<MemoryLongEntity>) : MemoryLongDao by EmptyLong() {
        override suspend fun getAll(): List<MemoryLongEntity> = rows
    }

    private class FakeDaily(private val rows: List<MemoryDailyEntity>) : MemoryDailyDao by EmptyDaily() {
        override suspend fun getAll(): List<MemoryDailyEntity> = rows
    }

    private open class EmptyLong : MemoryLongDao {
        override suspend fun insert(memory: MemoryLongEntity): Long = 0
        override suspend fun update(memory: MemoryLongEntity) {}
        override suspend fun delete(memory: MemoryLongEntity) {}
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = emptyList()
        override suspend fun getById(id: Long): MemoryLongEntity? = null
        override suspend fun getByCategory(category: String): List<MemoryLongEntity> = emptyList()
        override suspend fun deleteAll() {}
    }

    private open class EmptyDaily : MemoryDailyDao {
        override suspend fun insert(log: MemoryDailyEntity): Long = 0
        override suspend fun update(log: MemoryDailyEntity) {}
        override suspend fun delete(log: MemoryDailyEntity) {}
        override fun observeAll(): Flow<List<MemoryDailyEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryDailyEntity> = emptyList()
        override suspend fun getByDate(date: String): MemoryDailyEntity? = null
        override suspend fun deleteAll() {}
    }

    /** 假 embedding：按文本映射固定向量，方便算出确定相似度。 */
    private class FakeEmbedding(
        override val isReady: Boolean = true,
        private val map: Map<String, FloatArray> = emptyMap()
    ) : EmbeddingService {
        override val dimensions: Int = 3
        override suspend fun embed(text: String): FloatArray =
            map[text] ?: floatArrayOf(0f, 0f, 1f)
    }

    private fun shortRow(id: Long, content: String) =
        MemoryShortEntity(id = id, content = content, timestamp = 0L)

    private fun provider(
        short: List<MemoryShortEntity> = emptyList(),
        long: List<MemoryLongEntity> = emptyList(),
        daily: List<MemoryDailyEntity> = emptyList(),
        embedding: EmbeddingService = FakeEmbedding()
    ) = MemoryContextProvider(
        shortDao = FakeShort(short),
        longDao = FakeLong(long),
        dailyDao = FakeDaily(daily),
        embedding = { embedding }
    )

    @Test
    fun injects_related_short_memory_and_stable_long_and_daily() = runBlocking {
        // query 与本行向量完全同向 → 相似度 1.0
        val emb = FakeEmbedding(
            map = mapOf(
                "今天好累" to floatArrayOf(1f, 0f, 0f),
                "用户明天要面试" to floatArrayOf(1f, 0f, 0f),
                "用户喜欢安静" to floatArrayOf(0f, 1f, 0f), // 与服务端无关，仍会被长期注入
                "去公司" to floatArrayOf(1f, 0f, 0f)
            )
        )
        val p = provider(
            short = listOf(shortRow(1, "用户明天要面试"), shortRow(2, "用户喜欢安静")),
            long = listOf(MemoryLongEntity(content = "对花生过敏", category = "health", createTime = 0)),
            daily = listOf(MemoryDailyEntity(date = "2026-11-21", events = "去公司")),
            embedding = emb
        )

        val block = p.build("今天好累", budgetChars = 2000)!!

        assertTrue("相关的短期记忆要注入", block.contains("用户明天要面试"))
        assertTrue("不相关的短期记忆不注入", !block.contains("用户喜欢安静"))
        assertTrue("长期记忆照常注入", block.contains("对花生过敏"))
        assertTrue("日记照常注入", block.contains("去公司"))
    }

    @Test
    fun embedding_not_ready_skips_short_and_daily_but_keeps_long() = runBlocking {
        val p = provider(
            short = listOf(shortRow(1, "用户明天要面试")),
            long = listOf(MemoryLongEntity(content = "对花生过敏", category = "health", createTime = 0)),
            daily = listOf(MemoryDailyEntity(date = "2026-11-21", events = "去公司")),
            embedding = FakeEmbedding(isReady = false)
        )

        val block = p.build("今天好累", budgetChars = 2000)!!

        assertTrue("模型未就绪 → 短期不注入", !block.contains("用户明天要面试"))
        assertTrue("长期仍然注入", block.contains("对花生过敏"))
        assertTrue("模型未就绪 → 无法判相关，日记也不注入", !block.contains("去公司"))
    }

    @Test
    fun no_memory_at_all_returns_null() = runBlocking {
        assertNull(provider().build("今天好累", budgetChars = 2000))
    }

    @Test
    fun blank_user_text_skips_short_memory() = runBlocking {
        val p = provider(
            short = listOf(shortRow(1, "用户明天要面试")),
            long = listOf(MemoryLongEntity(content = "对花生过敏", category = "health", createTime = 0))
        )
        val block = p.build("   ", budgetChars = 2000)!!
        assertTrue(!block.contains("用户明天要面试"))
        assertTrue(block.contains("对花生过敏"))
    }

    @Test
    fun dao_failure_returns_null_instead_of_throwing() = runBlocking {
        val broken = object : MemoryLongDao by EmptyLong() {
            override suspend fun getAll(): List<MemoryLongEntity> = error("db down")
        }
        val p = MemoryContextProvider(
            shortDao = FakeShort(emptyList()),
            longDao = broken,
            dailyDao = FakeDaily(emptyList()),
            embedding = { FakeEmbedding() }
        )
        // 长期表读失败被内层 runCatching 吞掉 → 剩余为空 → 返回 null，不抛
        assertNull(p.build("今天好累", budgetChars = 2000))
    }

    /**
     * 1.9.4.1 回归：普通长期记忆 ≥12 条（LONG_LIMIT）时，情绪回忆**不能**被 take(12) 静默挤掉。
     * 之前 emotional 追加在 long 末尾，被整段切掉 → 功能无声失效。
     */
    @Test
    fun emotional_survives_when_normal_long_exceeds_limit() = runBlocking {
        val manyNormal = (1..15).map {
            MemoryLongEntity(content = "普通事实$it", category = "fact", createTime = it.toLong())
        }
        val p = provider(long = manyNormal)
        val block = p.build("今天好累", budgetChars = 5000, emotional = listOf("2026-11-21 你夸我，我当时开心。"))!!
        assertTrue("情绪回忆不能被普通记忆挤出", block.contains("你夸我"))
    }

    // ---- 2026-11-22：深度回忆（recallMode）----

    @Test
    fun recall_mode_lowers_threshold_so_weak_match_surfaces() = runBlocking {
        val emb = FakeEmbedding(
            map = mapOf(
                "你还记得那次吗" to floatArrayOf(1f, 0f, 0f),
                "半相关的旧事" to floatArrayOf(0.35f, 0.9367f, 0f) // cos≈0.35（>0.32 但 <0.45）
            )
        )
        val p = provider(short = listOf(shortRow(1, "半相关的旧事")), embedding = emb)

        assertNull("普通阈值 0.45 会拦住弱相关", p.build("你还记得那次吗", budgetChars = 2000))

        val recall = p.build("你还记得那次吗", budgetChars = 2000, recallMode = true)!!
        assertTrue("深度回忆阈值 0.32 放进来", recall.contains("半相关的旧事"))
    }

    @Test
    fun recall_mode_reaches_old_daily_beyond_normal_window() = runBlocking {
        val daily = listOf(
            MemoryDailyEntity(date = "2026-11-24", events = "今天没事1"),
            MemoryDailyEntity(date = "2026-11-23", events = "今天没事2"),
            MemoryDailyEntity(date = "2026-11-22", events = "今天没事3"),
            MemoryDailyEntity(date = "2026-11-21", events = "今天没事4"),
            MemoryDailyEntity(date = "2026-09-01", events = "那家糖水店")
        )
        val emb = FakeEmbedding(
            map = mapOf(
                "你还记得那家店吗" to floatArrayOf(1f, 0f, 0f),
                "那家糖水店" to floatArrayOf(0.9f, 0.4359f, 0f) // cos≈0.9
            )
        )
        val p = provider(daily = daily, embedding = emb)

        assertNull("普通轮只看近 3 天，翻不到更早的日记", p.build("你还记得那家店吗", budgetChars = 5000))

        val recall = p.build("你还记得那家店吗", budgetChars = 5000, recallMode = true)!!
        assertTrue("深度回忆把更久的日记翻出来", recall.contains("那家糖水店"))
    }

    @Test
    fun recall_mode_with_no_match_returns_only_honesty_note_not_random_memories() = runBlocking {
        val emb = FakeEmbedding(map = mapOf("你还记得那事吗" to floatArrayOf(1f, 0f, 0f)))
        val p = provider(short = listOf(shortRow(1, "完全无关的短期")), embedding = emb)

        // 普通轮：没相关 → null
        assertNull(p.build("你还记得那事吗", budgetChars = 2000))

        // 深度回忆轮：翻不到 → 只给"记不清别编"的提示，绝不塞无关记忆凑数（坑2）
        val out = p.build("你还记得那事吗", budgetChars = 2000, recallMode = true)!!
        assertEquals(
            com.companion.chat.domain.memory.MemoryContextBlock.RECALL_NOTE.trim(),
            out.trim()
        )
        assertTrue("不许拿无关记忆凑数", !out.contains("完全无关的短期"))
    }
}