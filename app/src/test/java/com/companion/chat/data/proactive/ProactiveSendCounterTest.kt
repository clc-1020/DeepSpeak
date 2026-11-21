package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** ProactiveSendCounter 今日自发计数单测。任务 3.5 */
class ProactiveSendCounterTest {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
        override suspend fun getAll(): List<AppStateEntity> = map.map { AppStateEntity(it.key, it.value) }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
    }

    @Test
    fun starts_at_zero() = runBlocking {
        val dao = FakeAppState()
        val c = ProactiveSendCounter(dao) { "2026-11-20" }
        assertEquals(0, c.countToday())
    }

    @Test
    fun increments_same_day() = runBlocking {
        val dao = FakeAppState()
        val c = ProactiveSendCounter(dao) { "2026-11-20" }
        c.increment(); assertEquals(1, c.countToday())
        c.increment(); assertEquals(2, c.countToday())
    }

    @Test
    fun resets_on_new_day() = runBlocking {
        val dao = FakeAppState()
        var day = "2026-11-20"
        val c = ProactiveSendCounter(dao) { day }
        c.increment(); c.increment()
        assertEquals(2, c.countToday())
        day = "2026-11-21" // 跨天
        assertEquals(0, c.countToday())
        c.increment()
        assertEquals(1, c.countToday())
    }

    @Test
    fun corrupt_value_treated_as_zero() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_PROACTIVE_SENT] = "not-json"
        val c = ProactiveSendCounter(dao) { "2026-11-20" }
        assertEquals(0, c.countToday())
    }
}