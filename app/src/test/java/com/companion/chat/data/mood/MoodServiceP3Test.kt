package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.emotion.Emotion
import com.companion.chat.domain.emotion.EmotionState
import com.companion.chat.domain.emotion.HersState
import com.companion.chat.domain.emotion.RelationshipStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：关系阶段 → 撒娇开关 + 情绪写回长期记忆。
 *
 * 这两条一旦错了后果很具体：不该撒娇的人撒娇（生疏就腻）、或记忆被情绪流水刷屏。
 */
class MoodServiceP3Test {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
        override suspend fun getAll(): List<AppStateEntity> = map.map { (k, v) -> AppStateEntity(k, v) }
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
    }

    private class FakeLongDao : MemoryLongDao {
        val rows = mutableListOf<MemoryLongEntity>()
        private var nextId = 1L
        override suspend fun insert(memory: MemoryLongEntity): Long {
            val id = nextId++
            rows += memory.copy(id = id)
            return id
        }
        override suspend fun update(memory: MemoryLongEntity) {
            val i = rows.indexOfFirst { it.id == memory.id }
            if (i >= 0) rows[i] = memory
        }
        override suspend fun delete(memory: MemoryLongEntity) { rows.removeAll { it.id == memory.id } }
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = rows.toList()
        override suspend fun getById(id: Long): MemoryLongEntity? = rows.firstOrNull { it.id == id }
        override suspend fun getByCategory(category: String): List<MemoryLongEntity> =
            rows.filter { it.category == category }.sortedByDescending { it.createTime }
        override suspend fun deleteAll() { rows.clear() }
    }

    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "k", model = "m")
    private var nowMs = 1_794_000_000_000L

    private fun user(text: String) =
        MessageEntity(conversationId = 1, role = MessageRole.USER, content = text, timestamp = nowMs)

    private fun service(
        dao: FakeAppState,
        json: String,
        stats: RelationshipStage.Stats?,
        longDao: MemoryLongDao? = null,
    ) = MoodService(
        appStateDao = dao,
        callJson = { _, _ -> Result.success(json) },
        now = { nowMs },
        relationshipProvider = { stats },
        memoryLongDao = longDao,
    )

    // ---- 撒娇：只有亲近才行 ----

    @Test
    fun affection_only_in_close_stage() = runBlocking {
        val hurtState = EmotionState(
            hers = HersState(emotions = mapOf(Emotion.HURT to 0.6), trigger = "他说在测试我", since = nowMs),
            updatedAt = nowMs,
        )

        val closeDao = FakeAppState().also {
            it.map[AppStateEntity.KEY_EMOTION_STATE] = hurtState.toJson()
        }
        val closeBlock = service(closeDao, "{}", RelationshipStage.Stats(300, 5)).renderEmotionBlock()!!
        assertTrue("亲近就该能撒娇：$closeBlock", closeBlock.contains("撒娇"))

        val familiarDao = FakeAppState().also {
            it.map[AppStateEntity.KEY_EMOTION_STATE] = hurtState.toJson()
        }
        val familiarBlock = service(familiarDao, "{}", RelationshipStage.Stats(40, 2)).renderEmotionBlock()!!
        assertFalse("熟悉不许撒娇", familiarBlock.contains("撒娇"))
        assertTrue(familiarBlock.contains("语气软一点"))
    }

    // ---- 情绪记忆：只在亲近 + 强度够 ----

    @Test
    fun emotion_memory_written_when_close() = runBlocking {
        val dao = FakeAppState()
        val longDao = FakeLongDao()
        val svc = service(
            dao,
            """{"mood":"happy","emotions":{"happy":0.8},"trigger":"你连夸我厉害可爱"}""",
            RelationshipStage.Stats(300, 5),
            longDao,
        )

        svc.onTurnCompleted(cfg, listOf(user("你真厉害，我最喜欢你了")))

        assertEquals(1, longDao.rows.size)
        val row = longDao.rows.first()
        assertEquals("relationship", row.category)
        assertTrue(row.content.contains("你连夸我厉害可爱"))
        assertTrue(row.content.contains("开心"))
    }

    @Test
    fun emotion_memory_not_written_when_only_familiar() = runBlocking {
        val longDao = FakeLongDao()
        val svc = service(
            FakeAppState(),
            """{"mood":"happy","emotions":{"happy":0.8},"trigger":"你夸我"}""",
            RelationshipStage.Stats(50, 2),
            longDao,
        )

        svc.onTurnCompleted(cfg, listOf(user("你真厉害")))

        assertTrue("熟悉阶段不写情绪记忆", longDao.rows.isEmpty())
    }

    @Test
    fun emotion_memory_capped_at_three() = runBlocking {
        val longDao = FakeLongDao()
        repeat(3) { i ->
            longDao.insert(
                MemoryLongEntity(
                    content = "2026-11-1$i 旧事$i，我当时开心。",
                    category = "relationship",
                    createTime = nowMs - (10 - i) * 1_000L,
                )
            )
        }
        val svc = service(
            FakeAppState(),
            """{"mood":"happy","emotions":{"happy":0.9},"trigger":"今天你特别温柔"}""",
            RelationshipStage.Stats(300, 5),
            longDao,
        )

        svc.onTurnCompleted(cfg, listOf(user("谢谢你，今天对我真好")))

        assertEquals("最多保留 3 条", 3, longDao.rows.size)
        assertTrue("新的一条必须在", longDao.rows.any { it.content.contains("今天你特别温柔") })
        assertFalse("最旧的应被淘汰", longDao.rows.any { it.content.contains("旧事0") })
    }

    // ---- 关系摘要（设置页文案）----

    @Test
    fun relationship_summary_reads_naturally() = runBlocking {
        val svc = service(FakeAppState(), "{}", RelationshipStage.Stats(213, 5))
        val text = svc.relationshipSummary()
        assertTrue(text.contains("亲近"))
        assertTrue(text.contains("213"))
        assertTrue(text.contains("第 5 天"))
    }

    // ---- P3.1 热络度 ----

    @Test
    fun cold_warmth_blocks_affection_even_when_close() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_EMOTION_STATE] = EmotionState(
            hers = HersState(emotions = mapOf(Emotion.HURT to 0.6), trigger = "他说在测试我", since = nowMs),
            updatedAt = nowMs,
        ).toJson()
        dao.map[AppStateEntity.KEY_WARMTH] = "0.2"
        dao.map[AppStateEntity.KEY_WARMTH_AT] = nowMs.toString()

        val block = service(dao, "{}", RelationshipStage.Stats(300, 5)).renderEmotionBlock()!!

        assertFalse("热络度低就不该出现撒娇文案（提示行里的「别撒娇」不算）", block.contains("可以跟他撒撒娇"))
        assertTrue("要提示她收着点", block.contains("热络度"))
        assertTrue(block.contains("别撒娇"))
    }

    @Test
    fun chatting_warms_up() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_WARMTH] = "0.4"
        dao.map[AppStateEntity.KEY_WARMTH_AT] = nowMs.toString()
        val svc = service(dao, """{"mood":"normal"}""", RelationshipStage.Stats(10, 1))

        svc.onTurnCompleted(cfg, listOf(user("嗯")))

        val stored = dao.map[AppStateEntity.KEY_WARMTH]!!.toDouble()
        assertTrue("聊过一轮应回暖，实际 $stored", stored > 0.4)
    }

    @Test
    fun cold_warmth_decays_over_time_without_chat() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_WARMTH] = "0.8"
        dao.map[AppStateEntity.KEY_WARMTH_AT] = (nowMs - 14L * 24 * 60 * 60_000L).toString()
        val svc = service(dao, "{}", RelationshipStage.Stats(300, 5))

        val w = svc.currentWarmth()
        assertEquals("两周没聊应减半", 0.4, w, 0.02)
    }
}