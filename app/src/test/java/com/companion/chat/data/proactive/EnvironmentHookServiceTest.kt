package com.companion.chat.data.proactive

import com.companion.chat.data.environment.FakeHttpText
import com.companion.chat.data.environment.FakeKeyValueStore
import com.companion.chat.data.environment.FakeLocationPort
import com.companion.chat.data.environment.GeoPoint
import com.companion.chat.data.environment.LocationCache
import com.companion.chat.data.environment.LocationProviders
import com.companion.chat.data.environment.WeatherCache
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.environment.EnvironmentHook
import com.companion.chat.domain.environment.EnvironmentRuleEngine
import com.companion.chat.domain.proactive.PendingMessageContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 环境钩子投递 + 同日去重（V2.1.4）单测。
 *
 * 用假 DAO / 假 store / 假 HTTP / 假定位组装出真实的 [EnvironmentHookService]，
 * 全纯 JVM。
 */
class EnvironmentHookServiceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val beijing = GeoPoint(39.9075, 116.39723)

    /** 上海时区某时刻的 epoch millis。 */
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val day20 = LocalDate.of(2026, 11, 20)
    private val day21 = LocalDate.of(2026, 11, 21)

    /** 晴：无降水、温度正常 → 无天气类钩子。 */
    private val sunnyJson = """
        {"daily":{"time":["2026-11-19","2026-11-20","2026-11-21"],
         "temperature_2m_max":[20.0,22.0,23.0],"temperature_2m_min":[10.0,12.0,13.0],
         "precipitation_probability_max":[0,0,0]}}
    """.trimIndent()

    /**
     * 大雨：today 降水 80% → Rain。
     *
     * 必须覆盖 11-19/20/21 三天（2026-11-21 00:0x 踩到）：`different_day_…` 用例会注入
     * 「明天 11-21」去验证「跨日后同一钩子可重发」，假数据少了这一天就查不到天气 → 无钩子。
     */
    private val rainyJson = """
        {"daily":{"time":["2026-11-19","2026-11-20","2026-11-21"],
         "temperature_2m_max":[20.0,22.0,23.0],"temperature_2m_min":[10.0,12.0,13.0],
         "precipitation_probability_max":[0,80,80]}}
    """.trimIndent()

    /** 内存假 AppStateDao。 */
    private class FakeAppStateDao(
        private val store: FakeKeyValueStore,
    ) : AppStateDao {
        override suspend fun put(state: AppStateEntity) { store.data[state.key] = state.value }
        override suspend fun get(key: String): String? = store.data[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<AppStateEntity> = emptyList()
        override suspend fun delete(key: String) { store.data.remove(key) }
        override suspend fun deleteAll() { store.data.clear() }
    }

    /** 内存假 PendingMessageDao：只记录 enqueue 的 insert，其余方法空实现。 */
    private class FakePendingDao : PendingMessageDao {
        val inserted = mutableListOf<PendingMessageEntity>()
        private var nextId = 1L

        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = nextId++)
            inserted += withId
            return withId.id
        }

        override suspend fun nextDue(now: Long): PendingMessageEntity? = null
        override suspend fun getById(id: Long): PendingMessageEntity? = null
        override suspend fun getByState(state: String): List<PendingMessageEntity> = emptyList()
        override fun observeAll(): Flow<List<PendingMessageEntity>> = flowOf(emptyList())
        override suspend fun transitionState(id: Long, newState: String, validFrom: List<String>): Int = 0
        override suspend fun updateState(id: Long, state: String): Int = 0
        override suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int = 0
        override suspend fun countPending(): Int = 0
        override suspend fun countPendingByContent(content: String): Int =
            inserted.count { it.state == PendingMessageState.PENDING && it.content == content }
        override suspend fun countAvailable(now: Long): Int = 0
        // V2.2.5：本测试不关心取消追问，只为满足接口（新增方法必须实现）
        override suspend fun cancelPendingByContentPrefix(prefix: String, newState: String): Int {
            var n = 0
            for (i in inserted.indices) {
                val row = inserted[i]
                if (row.state == PendingMessageState.PENDING && row.content.startsWith(prefix)) {
                    inserted[i] = row.copy(state = newState); n++
                }
            }
            return n
        }
        override suspend fun deleteAll() {}
    }

    private fun build(
        store: FakeKeyValueStore = FakeKeyValueStore(),
        http: FakeHttpText = FakeHttpText.ok(sunnyJson),
        point: GeoPoint? = beijing,
        dao: FakePendingDao = FakePendingDao(),
        nowMillis: Long = at(day20, 10, 0), // 默认 10:00（非饭点、非熬夜）
        enabled: Boolean = true,
    ): Pair<EnvironmentHookService, FakePendingDao> {
        val weatherCache = WeatherCache(
            store = store,
            http = http,
            nowMillis = { nowMillis },
        )
        val port = FakeLocationPort(
            if (point != null) mapOf(LocationProviders.NETWORK to point) else emptyMap()
        )
        val locationCache = LocationCache(store, port)
        val queue = PendingMessageQueue(dao = dao, now = { nowMillis })
        val service = EnvironmentHookService(
            ruleEngine = EnvironmentRuleEngine(),
            appStateDao = FakeAppStateDao(store),
            weatherCache = weatherCache,
            locationCache = locationCache,
            queue = queue,
            enabled = { enabled },
            zone = zone,
            nowMillis = { nowMillis },
        )
        return service to dao
    }

    // ---------- 类型映射 ----------

    @Test
    fun late_night_enqueues_as_late_night_type() = runBlocking {
        // 23:30 触发 LateNight（天气晴）
        val night = at(day20, 23, 30)
        val (service, dao) = build(nowMillis = night)

        val hook = service.recall()
        assertTrue(hook is EnvironmentHook.LateNight)

        service.enqueue(hook!!)
        val inserted = dao.inserted.single()
        assertEquals(PendingMessageType.LATE_NIGHT.code, inserted.type)
        assertEquals(2, inserted.priority) // 与 ENVIRONMENT 同级
        assertEquals("env:late_night", inserted.content)
    }

    @Test
    fun rain_enqueues_as_environment_type() = runBlocking {
        val (service, dao) = build(http = FakeHttpText.ok(rainyJson))

        val hook = service.recall()
        assertTrue(hook is EnvironmentHook.Rain)

        service.enqueue(hook!!)
        val inserted = dao.inserted.single()
        assertEquals(PendingMessageType.ENVIRONMENT.code, inserted.type)
        assertEquals("env:rain", inserted.content)
    }

    // ---------- 同日去重 ----------

    @Test
    fun same_hook_same_day_recall_returns_null_after_sent() = runBlocking {
        val store = FakeKeyValueStore()
        val (service, _) = build(store = store, http = FakeHttpText.ok(rainyJson))

        // 第一次 recall 返回 Rain（未记账）
        assertTrue(service.recall() is EnvironmentHook.Rain)

        // 模拟送达回调记账：env_sent_rain_2026-11-20
        store.data["env_sent_rain_2026-11-20"] = "1"

        // 第二次 recall → null（同日已发）
        assertNull(service.recall())
    }

    @Test
    fun different_day_same_hook_recall_returns_hook() = runBlocking {
        val store = FakeKeyValueStore()
        store.data["env_sent_rain_2026-11-20"] = "1" // 昨天已发

        // 今天（仍是 11-20 10:00）→ rain 已被去重，返回 null
        val (service, _) = build(store = store, http = FakeHttpText.ok(rainyJson))
        assertNull(service.recall())

        // 明天（11-21 10:00）→ 新的一天，rain 重新可发
        val tomorrow = at(day21, 10, 0)
        val (service2, _) = build(store = store, http = FakeHttpText.ok(rainyJson), nowMillis = tomorrow)
        assertTrue(service2.recall() is EnvironmentHook.Rain)
    }

    // ---------- 天气不可用 / 定位失败 ----------

    @Test
    fun no_weather_no_location_returns_null_at_10am() = runBlocking {
        // 无坐标 + 天气失败 → 只有时间类钩子；10:00 非饭点非熬夜 → 无钩子
        val (service, _) = build(point = null, http = FakeHttpText.failure())
        assertNull(service.recall())
    }

    @Test
    fun no_weather_still_returns_mealtime_at_noon() = runBlocking {
        // 天气失败但 12:00 是饭点 → 时间类钩子照常（不依赖天气）
        val noon = at(day20, 12, 0)
        val (service, _) = build(point = null, http = FakeHttpText.failure(), nowMillis = noon)
        assertTrue(service.recall() is EnvironmentHook.Mealtime)
    }

    // ---------- 编码可解析 ----------

    @Test
    fun enqueue_content_is_decodable_environment() = runBlocking {
        val (service, dao) = build()
        service.enqueue(EnvironmentHook.Rain(80))
        val decoded = PendingMessageContent.decode(dao.inserted.single().content)
        assertTrue(decoded is PendingMessageContent.Environment)
        assertEquals("rain", (decoded as PendingMessageContent.Environment).stableId)
    }

    // ---------- 环境总开关（V2.1.5 审查项） ----------

    /** 开关关闭 → recall 直接返回 null（不做天气/定位开销）。 */
    @Test
    fun disabled_returns_null() = runBlocking {
        val (service, _) = build(enabled = false, http = FakeHttpText.ok(rainyJson))
        assertNull(service.recall())
    }

    // ---------- 逐条找未发过（V2.1.5 审查项） ----------

    /**
     * 熬夜发出后，同夜的雨不该被第一条挡住：
     * 23:30 熬夜+下雨 → 熬夜去重键已写 → recall 应返回 Rain（遍历而非只看 first）。
     */
    @Test
    fun rain_still_returned_after_late_night_sent() = runBlocking {
        val store = FakeKeyValueStore()
        // 23:30 同时命中熬夜+下雨；熬夜去重键已写（昨晚发过），雨没发过
        store.data["env_sent_late_night_2026-11-20"] = "1"
        val night = at(day20, 23, 30)
        // 23:30 用雨天 JSON
        val (service, _) = build(
            store = store,
            http = FakeHttpText.ok(
                """
                {"daily":{"time":["2026-11-19","2026-11-20"],
                 "temperature_2m_max":[20.0,22.0],"temperature_2m_min":[10.0,12.0],
                 "precipitation_probability_max":[0,80]}}
                """.trimIndent()
            ),
            nowMillis = night,
        )

        val hook = service.recall()
        assertTrue("应返回 Rain 而不是 null（熬夜已发过，雨未发）", hook is EnvironmentHook.Rain)
    }

    // ---------- 队列查重（V2.1.5 审查项） ----------

    /** 队列里已有同 content 的待发消息 → 不再投（防每小时检查堆副本）。 */
    @Test
    fun same_content_already_queued_returns_null() = runBlocking {
        val dao = FakePendingDao()
        val (service, _) = build(http = FakeHttpText.ok(rainyJson), dao = dao)

        // 第一次 recall 返回 Rain，enqueue 入队
        val hook = service.recall()
        assertTrue(hook is EnvironmentHook.Rain)
        service.enqueue(hook!!)

        // 第二次 recall：Rain 已在队列 → 返回 null（不去重键，而是查重）
        assertNull(service.recall())
    }
}
