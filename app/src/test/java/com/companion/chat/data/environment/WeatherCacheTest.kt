package com.companion.chat.data.environment

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 天气拉取 + 24h 缓存单测。任务 V2.1.1。
 *
 * 走**真实 OkHttp + MockWebServer**：覆盖 URL 拼装、HTTP 状态、JSON 解析、TTL 四段，
 * 只把时钟和存储换成假的（这两者不假就没法钉住 24h 边界）。
 */
class WeatherCacheTest {

    private lateinit var server: MockWebServer

    private val now = 1_763_000_000_000L // 固定时钟，不依赖真实时间
    private val today = LocalDate.of(2026, 11, 20)

    private val beijing = GeoPoint(39.9075, 116.39723)

    /** 北京时间 2026-11-19 / 20 / 21 三天。today 索引 = 1，yesterday 索引 = 0。 */
    private val sampleJson = """
        {
          "latitude": 39.9, "longitude": 116.4,
          "timezone": "Asia/Shanghai", "utc_offset_seconds": 28800,
          "daily": {
            "time": ["2026-11-19", "2026-11-20", "2026-11-21"],
            "temperature_2m_max": [10.2, 13.4, 14.0],
            "temperature_2m_min": [1.0, 2.6, 3.0],
            "precipitation_probability_max": [0, 80, 20]
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cache(
        store: KeyValueStore,
        clock: () -> Long = { now },
    ) = WeatherCache(
        store = store,
        http = OkHttpHttpText(),
        nowMillis = clock,
        forecastBase = server.url("/v1/forecast").toString(),
    )

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    // ---------- 正常解析 ----------

    @Test
    fun fresh_fetch_parses_today_and_yesterday() = runBlocking {
        enqueueJson(sampleJson)
        val store = FakeKeyValueStore()

        val bundle = cache(store).load(beijing, today)

        // 13.4 → 13，2.6 → 3（四舍五入）
        assertEquals(13, bundle.today?.tempMax)
        assertEquals(3, bundle.today?.tempMin)
        assertEquals(80, bundle.today?.precipProbabilityMax)
        assertEquals(today, bundle.today?.date)

        assertEquals(10, bundle.yesterday?.tempMax)
        assertEquals(1, bundle.yesterday?.tempMin)
        assertEquals(0, bundle.yesterday?.precipProbabilityMax)
        assertEquals(today.minusDays(1), bundle.yesterday?.date)
    }

    @Test
    fun fresh_fetch_writes_cache() = runBlocking {
        enqueueJson(sampleJson)
        val store = FakeKeyValueStore()
        cache(store).load(beijing, today)

        assertNotNull(store.data[EnvironmentKeys.WEATHER_CACHE_JSON])
        assertEquals(now.toString(), store.data[EnvironmentKeys.WEATHER_CACHE_AT])
    }

    @Test
    fun request_url_carries_coordinates_and_past_days() = runBlocking {
        enqueueJson(sampleJson)
        cache(FakeKeyValueStore()).load(beijing, today)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("latitude=39.9075"))
        assertTrue(path, path.contains("longitude=116.39723"))
        assertTrue(path, path.contains("past_days=1"))
        assertTrue(path, path.contains("timezone=auto"))
        assertTrue(path, path.contains("precipitation_probability_max"))
    }

    @Test
    fun missing_today_in_daily_time_returns_null() = runBlocking {
        enqueueJson(
            """
            {"daily":{"time":["2026-11-25"],
             "temperature_2m_max":[20],"temperature_2m_min":[5],
             "precipitation_probability_max":[10]}}
            """.trimIndent()
        )
        val bundle = cache(FakeKeyValueStore()).load(beijing, today)
        assertNull(bundle.today)
        assertNull(bundle.yesterday)
    }

    @Test
    fun null_entries_in_arrays_become_null_fields() = runBlocking {
        enqueueJson(
            """
            {"daily":{"time":["2026-11-19","2026-11-20"],
             "temperature_2m_max":[10.2,null],"temperature_2m_min":[null,2.6],
             "precipitation_probability_max":[5,null]}}
            """.trimIndent()
        )
        val bundle = cache(FakeKeyValueStore()).load(beijing, today)

        assertNull(bundle.today?.tempMax)
        assertNull(bundle.today?.precipProbabilityMax)
        assertEquals(3, bundle.today?.tempMin)

        assertEquals(10, bundle.yesterday?.tempMax)
        assertNull(bundle.yesterday?.tempMin)
        assertEquals(5, bundle.yesterday?.precipProbabilityMax)
    }

    // ---------- 缓存命中 ----------

    @Test
    fun fresh_cache_does_not_hit_network() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(), // 1 小时前
            )
        )

        val bundle = cache(store).load(beijing, today)

        assertEquals(0, server.requestCount)
        assertEquals(13, bundle.today?.tempMax)
    }

    // ---------- 过期 ----------

    @Test
    fun stale_cache_with_failing_network_returns_null() = runBlocking {
        enqueueJson("""{"error":true}""", code = 500)
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 25 * 3_600_000L).toString(), // 25 小时前
            )
        )

        val bundle = cache(store).load(beijing, today)

        assertEquals(1, server.requestCount)
        assertNull(bundle.today)
        assertNull(bundle.yesterday)
    }

    @Test
    fun stale_cache_refetches_and_uses_new_data() = runBlocking {
        enqueueJson(
            """
            {"daily":{"time":["2026-11-19","2026-11-20"],
             "temperature_2m_max":[1.0,99.0],"temperature_2m_min":[0.0,88.0],
             "precipitation_probability_max":[0,0]}}
            """.trimIndent()
        )
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 25 * 3_600_000L).toString(),
            )
        )

        val bundle = cache(store).load(beijing, today)

        assertEquals(1, server.requestCount)
        assertEquals(99, bundle.today?.tempMax)          // 用的是新响应，不是旧缓存
        assertEquals(now.toString(), store.data[EnvironmentKeys.WEATHER_CACHE_AT])
    }

    // ---------- 失败不崩 ----------

    @Test
    fun http_500_returns_empty_without_crash() = runBlocking {
        enqueueJson("""{"error":true}""", code = 500)
        val bundle = cache(FakeKeyValueStore()).load(beijing, today)
        assertEquals(WeatherBundle.EMPTY, bundle)
    }

    @Test
    fun malformed_json_returns_empty_without_crash() = runBlocking {
        enqueueJson("not json at all")
        assertEquals(WeatherBundle.EMPTY, cache(FakeKeyValueStore()).load(beijing, today))

        enqueueJson("""{"daily":{"time":"oops"}}""")
        assertEquals(WeatherBundle.EMPTY, cache(FakeKeyValueStore()).load(beijing, today))

        enqueueJson("""[]""")
        assertEquals(WeatherBundle.EMPTY, cache(FakeKeyValueStore()).load(beijing, today))
    }

    @Test
    fun no_coordinate_skips_network() = runBlocking {
        val bundle = cache(FakeKeyValueStore()).load(null, today)
        assertEquals(WeatherBundle.EMPTY, bundle)
        assertEquals(0, server.requestCount)
    }

    // ---------- TTL 边界（纯逻辑，不起服务器） ----------

    @Test
    fun ttl_boundary_is_exclusive_at_24h() {
        val c = WeatherCache(FakeKeyValueStore(), FakeHttpText.failure(), { now })

        assertTrue(c.isFresh(now))                                        // 刚拉到
        assertTrue(c.isFresh(now - (24 * 3_600_000L) + 1))                // 23:59:59.999
        assertFalse(c.isFresh(now - 24 * 3_600_000L))                     // 正好 24h → 过期
        assertFalse(c.isFresh(now + 60_000L))                             // 时钟回跳 → 当过期，宁可重拉
    }

    // ---------- 后台预热失败重试（1.9.3.1） ----------

    @Test
    fun retry_recovers_when_first_attempt_fails() = runBlocking {
        enqueueJson("""{"error":true}""", code = 500) // 第一次失败
        enqueueJson(sampleJson)                        // 第二次成功
        val store = FakeKeyValueStore()

        val bundle = cache(store).loadWithRetry(beijing, today, retryDelayMs = 0)

        assertEquals(2, server.requestCount)
        assertEquals(13, bundle.today?.tempMax)
        assertNotNull(store.data[EnvironmentKeys.WEATHER_CACHE_JSON])
    }

    @Test
    fun retry_stops_after_attempts_and_returns_empty() = runBlocking {
        enqueueJson("""{"error":true}""", code = 500)
        enqueueJson("""{"error":true}""", code = 500)

        val bundle = cache(FakeKeyValueStore()).loadWithRetry(beijing, today, retryDelayMs = 0)

        assertEquals(WeatherCache.DEFAULT_RETRY_ATTEMPTS, server.requestCount)
        assertEquals(WeatherBundle.EMPTY, bundle)
    }

    @Test
    fun retry_does_not_hit_network_when_no_coordinate() = runBlocking {
        val bundle = cache(FakeKeyValueStore()).loadWithRetry(null, today, retryDelayMs = 0)

        assertEquals(0, server.requestCount)
        assertEquals(WeatherBundle.EMPTY, bundle)
    }

    @Test
    fun retry_uses_cache_without_network_when_fresh() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(),
            )
        )

        val bundle = cache(store).loadWithRetry(beijing, today, retryDelayMs = 0)

        assertEquals(0, server.requestCount)   // load() 自己命中缓存，重试分支根本不触发
        assertEquals(13, bundle.today?.tempMax)
    }

    // ---------- peek()：发送路径只读缓存，绝不联网（V2.1.8） ----------

    @Test
    fun peek_returns_fresh_cached_snapshot_without_network() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(),
            )
        )

        val snap = cache(store).peek(today)

        assertEquals(0, server.requestCount)   // 绝不发 HTTP
        assertEquals(13, snap?.tempMax)         // 解析出今天
        assertEquals(80, snap?.precipProbabilityMax)
    }

    @Test
    fun peek_with_no_cache_returns_null() = runBlocking {
        val snap = cache(FakeKeyValueStore()).peek(today)
        assertNull(snap)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun peek_with_stale_cache_returns_null_without_refetch() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 25 * 3_600_000L).toString(),
            )
        )

        val snap = cache(store).peek(today)

        assertNull(snap)                        // 过期一律不返回旧数据
        assertEquals(0, server.requestCount)    // 也不重拉（发送路径不等待）
    }

    @Test
    fun peek_with_malformed_cache_returns_null_without_crash() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to "not json",
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(),
            )
        )

        val snap = cache(store).peek(today)
        assertNull(snap)
        assertEquals(0, server.requestCount)
    }

    // ---------- 2026-11-22：peekBundle（整点检查用，缓存新鲜时不碰定位） ----------

    @Test
    fun peek_bundle_returns_both_days_from_fresh_cache() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(),
            )
        )

        val bundle = cache(store).peekBundle(today)

        assertEquals(0, server.requestCount)     // 绝不联网
        assertEquals(13, bundle.today?.tempMax)
        assertEquals(10, bundle.yesterday?.tempMax)   // 整点检查要昨天算降温
    }

    @Test
    fun peek_bundle_is_empty_without_cache_and_without_network() = runBlocking {
        val bundle = cache(FakeKeyValueStore()).peekBundle(today)

        assertEquals(0, server.requestCount)
        assertNull(bundle.today)
        assertNull(bundle.yesterday)
    }

    @Test
    fun peek_bundle_is_empty_when_cache_is_stale() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 25 * 3_600_000L).toString(),
            )
        )

        val bundle = cache(store).peekBundle(today)

        assertEquals(0, server.requestCount)
        assertNull(bundle.today)
    }

    // ---------- 2026-11-22：缓存优先于坐标判空（后台天气钩子全哑的回归） ----------

    /**
     * 回归：后台拿不到定位（`point == null`）时，**新鲜缓存仍然必须能用**。
     *
     * 修之前 `load()` 第一行就是 `if (point == null) return EMPTY`，这行会把整点环境检查
     * 里的天气直接丢掉 —— 自动定位模式下下雨/降温/高温钩子一次都不触发。
     */
    @Test
    fun fresh_cache_is_used_even_without_a_coordinate() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to (now - 3_600_000L).toString(),
            )
        )

        val bundle = cache(store).load(point = null, today = today)

        assertEquals(0, server.requestCount)     // 不联网
        assertEquals(13, bundle.today?.tempMax)  // 缓存照常解析
    }

    /** 坐标刚刷新过 → `force = true` 必须无视新鲜缓存，立即重拉（否则还是旧城市的天气）。 */
    @Test
    fun force_ignores_fresh_cache_and_refetches() = runBlocking {
        val otherCityJson = """
            {
              "latitude": 23.0, "longitude": 113.1,
              "timezone": "Asia/Shanghai", "utc_offset_seconds": 28800,
              "daily": {
                "time": ["2026-11-19", "2026-11-20", "2026-11-21"],
                "temperature_2m_max": [30.0, 31.0, 32.0],
                "temperature_2m_min": [22.0, 23.0, 24.0],
                "precipitation_probability_max": [90, 95, 99]
              }
            }
        """.trimIndent()
        enqueueJson(otherCityJson)

        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,   // 旧城市（13°C）
                EnvironmentKeys.WEATHER_CACHE_AT to now.toString(),
            )
        )

        val bundle = cache(store).load(beijing, today, force = true)

        assertEquals(1, server.requestCount)
        assertEquals(31, bundle.today?.tempMax)     // 拿到新城市的数据
        assertEquals(95, bundle.today?.precipProbabilityMax)
    }

    /** 强制重拉但一格坐标都没有 → 不发请求，返回空（绝不会拿旧城市缓存凑）。 */
    @Test
    fun force_without_coordinate_returns_empty_without_network() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.WEATHER_CACHE_JSON to sampleJson,
                EnvironmentKeys.WEATHER_CACHE_AT to now.toString(),
            )
        )

        val bundle = cache(store).load(point = null, today = today, force = true)

        assertEquals(0, server.requestCount)
        assertNull(bundle.today)
    }
}