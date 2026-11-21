package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.emotion.Emotion
import com.companion.chat.domain.emotion.EmotionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪状态机（P1）：落库 / 衰减 / 护栏 / 注入文案 / 示弱配额。
 *
 * 这里刻意**不 fake EmotionDecay/Merge**，全程走真实实现 —— 假实现会把被测语义复制一份，
 * 两边一起错就测不出来。
 */
class MoodServiceEmotionTest {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
        override suspend fun getAll(): List<AppStateEntity> =
            map.map { (k, v) -> AppStateEntity(k, v) }
        override fun observeAll(): kotlinx.coroutines.flow.Flow<List<AppStateEntity>> =
            kotlinx.coroutines.flow.emptyFlow()
    }

    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "k", model = "m")
    private var nowMs = 1_000_000_000_000L

    private fun service(
        dao: FakeAppState,
        json: String,
        onCall: (() -> Unit)? = null,
    ) = MoodService(
        appStateDao = dao,
        callJson = { _, _ -> onCall?.invoke(); Result.success(json) },
        now = { nowMs },
    )

    private fun user(text: String) = MessageEntity(
        conversationId = 1, role = MessageRole.USER, content = text, timestamp = nowMs
    )

    @Test
    fun emotion_and_trigger_are_persisted() = runBlocking {
        val dao = FakeAppState()
        val json = """{"mood":"normal","reason":"r","emotions":{"hurt":0.6},"trigger":"他说在测试我"}"""
        val svc = service(dao, json)

        assertTrue(svc.onTurnCompleted(cfg, listOf(user("你说话好难听！！"))))

        val st = EmotionState.fromJson(dao.map[AppStateEntity.KEY_EMOTION_STATE])!!
        assertEquals(0.6, st.hers.emotions[Emotion.HURT]!!, 1e-6)
        assertEquals("他说在测试我", st.hers.trigger)
    }

    @Test
    fun negative_without_trigger_is_dropped() = runBlocking {
        val dao = FakeAppState()
        val json = """{"mood":"normal","emotions":{"hurt":0.7}}"""
        val svc = service(dao, json)

        svc.onTurnCompleted(cfg, listOf(user("哼")))

        val st = EmotionState.fromJson(dao.map[AppStateEntity.KEY_EMOTION_STATE])
        assertTrue("负面无 trigger → 不采信", st == null || st.hers.emotions.isEmpty())
    }

    @Test
    fun merge_keeps_higher_old_value() = runBlocking {
        val dao = FakeAppState()
        // 先种一条 0.6 的委屈（2 分钟前，衰减很小）
        val seeded = EmotionState(
            hers = com.companion.chat.domain.emotion.HersState(
                emotions = mapOf(Emotion.HURT to 0.6),
                trigger = "旧的事",
                since = nowMs - 120_000L,
            ),
            updatedAt = nowMs - 120_000L,
        )
        dao.map[AppStateEntity.KEY_EMOTION_STATE] = seeded.toJson()

        val svc = service(dao, """{"mood":"normal","emotions":{"hurt":0.3},"trigger":"新的事"}""")
        svc.onTurnCompleted(cfg, listOf(user("随便吧，我懒得说了")))

        val st = EmotionState.fromJson(dao.map[AppStateEntity.KEY_EMOTION_STATE])!!
        assertTrue("旧值 0.6 衰减后仍应压过模型报的 0.3，实际 ${st.hers.emotions[Emotion.HURT]}",
            st.hers.emotions[Emotion.HURT]!! > 0.55)
    }

    @Test
    fun no_stimulus_and_not_fifth_turn_skips_the_call() = runBlocking {
        val dao = FakeAppState()
        var calls = 0
        val svc = service(dao, """{"mood":"normal"}""") { calls++ }

        // 第 1 轮 + 平淡的「嗯」→ 不调模型
        assertFalse(svc.onTurnCompleted(cfg, listOf(user("嗯"))))
        assertEquals(0, calls)
    }

    @Test
    fun stimulus_triggers_a_call_even_before_five_turns() = runBlocking {
        val dao = FakeAppState()
        var calls = 0
        val svc = service(dao, """{"mood":"happy","emotions":{"happy":0.7},"trigger":"他夸我"}""") { calls++ }

        assertTrue(svc.onTurnCompleted(cfg, listOf(user("你真厉害"))))
        assertEquals(1, calls)
    }

    @Test
    fun reading_decays_by_thirty_minute_half_life() = runBlocking {
        val dao = FakeAppState()
        val seeded = EmotionState(
            hers = com.companion.chat.domain.emotion.HersState(
                emotions = mapOf(Emotion.HURT to 0.8), trigger = "t", since = nowMs - 1_800_000L
            ),
            updatedAt = nowMs - 1_800_000L, // 30 分钟前
        )
        dao.map[AppStateEntity.KEY_EMOTION_STATE] = seeded.toJson()

        val svc = service(dao, "{}")
        val st = svc.currentEmotionState()

        assertEquals(0.4, st.hers.emotions[Emotion.HURT]!!, 1e-6)
    }

    @Test
    fun injected_block_uses_soft_hurt_in_p1() = runBlocking {
        val dao = FakeAppState()
        val seeded = EmotionState(
            hers = com.companion.chat.domain.emotion.HersState(
                emotions = mapOf(Emotion.HURT to 0.6), trigger = "他说在测试我", since = nowMs
            ),
            updatedAt = nowMs,
        )
        dao.map[AppStateEntity.KEY_EMOTION_STATE] = seeded.toJson()

        val block = service(dao, "{}").renderEmotionBlock()
        assertNotNull(block)
        assertTrue(block!!.contains("有点委屈（0.6）"))
        assertTrue(block.contains("语气软一点"))
        assertFalse("P1 不许撒娇", block.contains("撒娇"))
    }

    @Test
    fun weakness_quota_is_once_per_day() = runBlocking {
        val svc = service(FakeAppState(), "{}")
        assertTrue("今天第一次", svc.claimWeaknessForToday())
        assertFalse("同一天第二次", svc.claimWeaknessForToday())

        // 换一天 → 又是第一次
        val svc2 = MoodService(
            appStateDao = FakeAppState().also {
                it.map[AppStateEntity.KEY_WEAKNESS_DATE] =
                    java.time.Instant.ofEpochMilli(nowMs - 86_400_000L)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            },
            callJson = { _, _ -> Result.success("{}") },
            now = { nowMs },
        )
        assertTrue("隔天重置", svc2.claimWeaknessForToday())
    }

    // ---- P2：想你（只在主动消息路径积累）----

    @Test
    fun away_boost_accumulates_missing_when_user_absent() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_LAST_INTERACTION] = (nowMs - 10 * 3_600_000L).toString()
        val svc = service(dao, "{}")

        val emo = svc.emotionsWithAwayBoost()

        assertTrue("10 小时没来应产生明显想你，实际 ${emo[Emotion.MISSING]}", (emo[Emotion.MISSING] ?: 0.0) >= 0.5)
    }

    @Test
    fun away_boost_does_nothing_within_two_hours() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_LAST_INTERACTION] = (nowMs - 30 * 60_000L).toString()
        val svc = service(dao, "{}")

        assertTrue(svc.emotionsWithAwayBoost().isEmpty())
    }
}