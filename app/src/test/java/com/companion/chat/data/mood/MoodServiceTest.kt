package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.remote.ApiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MoodService 每 5 轮更新 / 失败保持原 mood 单测。任务 5.1 */
class MoodServiceTest {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
        override suspend fun getAll(): List<AppStateEntity> = map.map { AppStateEntity(it.key, it.value) }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
    }

    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "k", model = "m")

    private fun service(dao: FakeAppState, result: Result<String>) =
        MoodService(
            appStateDao = dao,
            callJson = { _, _ -> result }
        )

    @Test
    fun no_update_before_threshold() = runBlocking {
        val dao = FakeAppState()
        val svc = service(dao, Result.success("""{"mood":"happy","reason":"x"}"""))
        repeat(4) { assertFalse(svc.onTurnCompleted(cfg, emptyList())) }
        assertEquals("normal", svc.currentMood())
        assertEquals(4, svc.turnCount())
    }

    @Test
    fun updates_on_fifth_turn() = runBlocking {
        val dao = FakeAppState()
        val svc = service(dao, Result.success("""{"mood":"happy","reason":"升职了"}"""))
        repeat(4) { svc.onTurnCompleted(cfg, emptyList()) }
        val triggered = svc.onTurnCompleted(cfg, emptyList())
        assertTrue(triggered)
        assertEquals("happy", svc.currentMood())
        assertEquals("升职了", dao.map[AppStateEntity.KEY_MOOD_REASON])
        assertEquals(5, svc.turnCount())
    }

    @Test
    fun failure_keeps_old_mood() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_MOOD] = "low"
        val svc = service(dao, Result.failure(RuntimeException("网络")))
        repeat(5) { svc.onTurnCompleted(cfg, emptyList()) }
        assertEquals("low", svc.currentMood()) // 保持不变
        assertEquals(5, svc.turnCount())       // 计数仍推进
    }

    @Test
    fun invalid_json_keeps_old_mood() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_MOOD] = "happy"
        val svc = service(dao, Result.success("不是 JSON"))
        repeat(5) { svc.onTurnCompleted(cfg, emptyList()) }
        assertEquals("happy", svc.currentMood())
    }

    @Test
    fun default_mood_is_normal() = runBlocking {
        assertEquals("normal", service(FakeAppState(), Result.success("{}")).currentMood())
    }
}