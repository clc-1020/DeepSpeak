package com.companion.chat.data.environment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置/城市选择 + provider 顺序。任务 V2.1.1。
 *
 * 最要紧的一条：**有手动城市时一次定位都不许调**（计划书 §4 验收项）。
 * [FakeLocationPort.calls] 就是为断言这个而存在的。
 */
class LocationCacheTest {

    private val beijing = GeoPoint(39.9075, 116.39723)
    private val shanghai = GeoPoint(31.2222, 121.4581)

    private fun manualStore(name: String = "北京市", lat: String = "39.9075", lon: String = "116.39723") =
        FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.MANUAL_CITY_NAME to name,
                EnvironmentKeys.MANUAL_LAT to lat,
                EnvironmentKeys.MANUAL_LON to lon,
            )
        )

    // ---------- 手动城市优先 ----------

    @Test
    fun manual_city_never_touches_location() = runBlocking {
        val port = FakeLocationPort(
            mapOf(LocationProviders.NETWORK to shanghai, LocationProviders.GPS to beijing)
        )
        val city = LocationCache(manualStore(), port).current()

        val manual = city as LocatedCity.Manual
        assertEquals(39.9075, manual.point.latitude, 0.0)
        assertEquals(116.39723, manual.point.longitude, 0.0)
        assertEquals(0, port.calls.size)                 // 关键断言：没调定位
    }

    @Test
    fun manual_city_name_with_broken_coordinates_falls_back_to_location() = runBlocking {
        val port = FakeLocationPort(mapOf(LocationProviders.NETWORK to shanghai))
        val store = manualStore(lat = "not-a-number")

        val city = LocationCache(store, port).current()

        val auto = city as LocatedCity.Auto
        assertEquals(shanghai, auto.point)
        assertEquals(mutableListOf(LocationProviders.NETWORK), port.calls) // 自愈：回退到自动定位
    }

    @Test
    fun blank_manual_name_is_treated_as_auto() = runBlocking {
        val port = FakeLocationPort(mapOf(LocationProviders.NETWORK to shanghai))
        val city = LocationCache(manualStore(name = "   "), port).current()

        assertTrue(city is LocatedCity.Auto)
        assertEquals(shanghai, (city as LocatedCity.Auto).point)
    }

    // ---------- provider 顺序 ----------

    @Test
    fun network_provider_wins_over_gps() = runBlocking {
        val port = FakeLocationPort(
            mapOf(LocationProviders.NETWORK to shanghai, LocationProviders.GPS to beijing)
        )
        val auto = LocationCache(FakeKeyValueStore(), port).current() as LocatedCity.Auto

        assertEquals(shanghai, auto.point)
        assertEquals(listOf(LocationProviders.NETWORK), port.calls)   // 拿到就停，不试 GPS
    }

    @Test
    fun gps_is_used_when_network_is_empty() = runBlocking {
        val port = FakeLocationPort(mapOf(LocationProviders.GPS to beijing))
        val auto = LocationCache(FakeKeyValueStore(), port).current() as LocatedCity.Auto

        assertEquals(beijing, auto.point)
        assertEquals(listOf(LocationProviders.NETWORK, LocationProviders.GPS), port.calls)
    }

    @Test
    fun both_providers_empty_gives_null_coordinate() = runBlocking {
        val port = FakeLocationPort()
        val auto = LocationCache(FakeKeyValueStore(), port).current() as LocatedCity.Auto

        assertNull(auto.point)
        assertEquals(2, port.calls.size)
    }

    @Test
    fun provider_order_is_network_then_gps() {
        assertEquals(listOf("network", "gps"), KnownLocationPick.ORDER)
    }

    // ---------- 每天首次刷新 ----------

    @Test
    fun refresh_flag_is_per_epoch_day() = runBlocking {
        val cache = LocationCache(FakeKeyValueStore(), FakeLocationPort())
        val day1 = 20_000L
        val day2 = 20_001L

        assertTrue(cache.shouldRefreshToday(day1))       // 从没刷过
        cache.markRefreshed(day1)
        assertFalse(cache.shouldRefreshToday(day1))      // 同一天不再刷
        assertTrue(cache.shouldRefreshToday(day2))       // 跨日要刷
    }

    @Test
    fun corrupted_refresh_stamp_means_refresh() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(EnvironmentKeys.LOCATION_REFRESHED_DATE to "garbage")
        )
        assertTrue(LocationCache(store, FakeLocationPort()).shouldRefreshToday(20_000L))
    }

    // ---------- 2026-11-22：每天首次前台主动刷新（refreshTodayOnce） ----------

    @Test
    fun daily_refresh_never_touches_location_when_manual_city_is_set() = runBlocking {
        val port = ActiveFakeLocationPort(known = emptyMap(), active = shanghai)
        val store = manualStore()
        val day = 20_000L

        val result = LocationCache(store, port).refreshTodayOnce(day)

        assertFalse(result.attempted)                  // 手填城市 → 连主动请求都不发
        assertNull(result.point)
        assertEquals(0, port.activeCalls)              // 关键断言：一次定位都没调
        assertTrue(port.lastKnownCalls.isEmpty())
        // 手动城市不记账（本来就不需要刷新，别让标记无意义地变化）
        assertNull(store.data[EnvironmentKeys.LOCATION_REFRESHED_DATE])
    }

    @Test
    fun daily_refresh_asks_the_system_once_and_records_the_day() = runBlocking {
        val port = ActiveFakeLocationPort(known = emptyMap(), active = shanghai)
        val store = FakeKeyValueStore()
        val day = 20_000L
        val cache = LocationCache(store, port)

        val result = cache.refreshTodayOnce(day)

        assertTrue(result.attempted)
        assertEquals(shanghai, result.point)
        assertEquals(1, port.activeCalls)
        assertEquals(listOf(LocationProviders.NETWORK), port.activeProviders)  // 只请求最省电的那个
        assertFalse(cache.shouldRefreshToday(day))     // 当天已记账
    }

    @Test
    fun daily_refresh_is_a_no_op_on_the_same_day() = runBlocking {
        val port = ActiveFakeLocationPort(known = emptyMap(), active = shanghai)
        val day = 20_000L
        val cache = LocationCache(FakeKeyValueStore(), port)

        cache.refreshTodayOnce(day)
        val second = cache.refreshTodayOnce(day)

        assertFalse(second.attempted)                  // 同一天不再折腾
        assertEquals(1, port.activeCalls)
    }

    @Test
    fun daily_refresh_marks_even_when_location_fails() = runBlocking {
        val port = ActiveFakeLocationPort(known = emptyMap(), active = null)
        val store = FakeKeyValueStore()
        val day = 20_000L
        val cache = LocationCache(store, port)

        val result = cache.refreshTodayOnce(day)

        assertTrue(result.attempted)
        assertNull(result.point)
        assertEquals(1, port.activeCalls)
        // 用户 2026-11-22 拍板：失败也算「今天刷过」，当天不再反复空跑
        assertFalse(cache.shouldRefreshToday(day))
    }

    @Test
    fun daily_refresh_falls_back_to_last_known_when_active_request_is_empty() = runBlocking {
        val port = ActiveFakeLocationPort(
            known = mapOf(LocationProviders.NETWORK to shanghai),
            active = null,
        )

        val result = LocationCache(FakeKeyValueStore(), port).refreshTodayOnce(20_000L)

        assertTrue(result.attempted)
        assertEquals(shanghai, result.point)           // 主动失败 → 退回被动历史位置
        assertEquals(1, port.activeCalls)
        assertEquals(listOf(LocationProviders.NETWORK), port.lastKnownCalls)
    }

    @Test
    fun daily_refresh_runs_again_on_the_next_day() = runBlocking {
        val port = ActiveFakeLocationPort(known = emptyMap(), active = shanghai)
        val cache = LocationCache(FakeKeyValueStore(), port)

        cache.refreshTodayOnce(20_000L)
        val nextDay = cache.refreshTodayOnce(20_001L)

        assertTrue(nextDay.attempted)                  // 跨日要重刷
        assertEquals(2, port.activeCalls)
    }

    // ---------- 2026-11-22：自动定位反查城市名（注记第 3 段） ----------

    @Test
    fun daily_refresh_records_looked_up_city_name() = runBlocking {
        val store = FakeKeyValueStore()
        val port = ActiveFakeLocationPort(known = emptyMap(), active = shanghai)
        val lookup = FakeCityNameLookup("上海市")
        val cache = LocationCache(store, port, lookup)

        val result = cache.refreshTodayOnce(20_000L)

        assertEquals(shanghai, result.point)
        assertEquals(listOf(shanghai), lookup.points)     // 只反查一次，用刚拿到的坐标
        assertEquals("上海市", cache.autoCityName())
        assertEquals("上海市", cache.displayCityName())   // 没有手填城市 → 用反查结果
        assertEquals("31.2222", store.data[EnvironmentKeys.AUTO_CITY_LAT])
        assertEquals("121.4581", store.data[EnvironmentKeys.AUTO_CITY_LON])
    }

    @Test
    fun failed_lookup_leaves_auto_city_empty() = runBlocking {
        val lookup = FakeCityNameLookup(null)
        val cache = LocationCache(
            FakeKeyValueStore(),
            ActiveFakeLocationPort(known = emptyMap(), active = shanghai),
            lookup,
        )

        cache.refreshTodayOnce(20_000L)

        assertEquals(1, lookup.points.size)             // 试过了
        assertNull(cache.autoCityName())                // 但没写成
        assertNull(cache.displayCityName())
    }

    @Test
    fun without_lookup_auto_city_stays_empty() = runBlocking {
        val cache = LocationCache(
            FakeKeyValueStore(),
            ActiveFakeLocationPort(known = emptyMap(), active = shanghai),
        )

        cache.refreshTodayOnce(20_000L)

        assertNull(cache.autoCityName())                // 未装配 lookup → 退回改动前行为
    }

    @Test
    fun manual_city_wins_and_skips_lookup() = runBlocking {
        val lookup = FakeCityNameLookup("深圳市")
        val cache = LocationCache(manualStore(), ActiveFakeLocationPort(emptyMap(), shanghai), lookup)

        cache.refreshTodayOnce(20_000L)

        assertTrue(lookup.points.isEmpty())             // 手填城市 → 一次反查都不做
        assertEquals("北京市", cache.displayCityName()) // 手填优先
    }
}

/** 假的反查：固定返回一个地名（或 null），并记录被查过的坐标。 */
private class FakeCityNameLookup(private val name: String?) : CityNamePort {

    val points: MutableList<GeoPoint> = mutableListOf()

    override suspend fun nameOf(point: GeoPoint): String? {
        points += point
        return name
    }
}

/**
 * 能区分「主动请求」和「被动读缓存」的假定位（2026-11-22）。
 *
 * [FakeLocationPort] 的 `currentOnce` 走接口默认实现、会记进同一个 `calls` 列表，
 * 没法断言「到底调没调主动定位」。这里分开记。
 */
private class ActiveFakeLocationPort(
    private val known: Map<String, GeoPoint>,
    private val active: GeoPoint?,
) : LocationPort {

    val lastKnownCalls: MutableList<String> = mutableListOf()
    val activeProviders: MutableList<String> = mutableListOf()
    val activeCalls: Int get() = activeProviders.size

    override fun lastKnown(provider: String): GeoPoint? {
        lastKnownCalls += provider
        return known[provider]
    }

    override suspend fun currentOnce(provider: String, timeoutMs: Long): GeoPoint? {
        activeProviders += provider
        return active
    }
}