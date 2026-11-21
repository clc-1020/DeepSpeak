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
        assertEquals("北京市", manual.name)
        assertEquals(39.9075, manual.point.latitude, 0.0)
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

    // ---------- 手动城市增删 ----------

    @Test
    fun set_and_clear_manual_city_round_trip() = runBlocking {
        val store = FakeKeyValueStore()
        val cache = LocationCache(store, FakeLocationPort())

        assertNull(cache.manualCityName())

        cache.setManualCity("北京市", beijing)
        assertEquals("北京市", cache.manualCityName())
        assertEquals("39.9075", store.data[EnvironmentKeys.MANUAL_LAT])

        cache.clearManualCity()
        assertNull(cache.manualCityName())
        assertTrue(store.data.isEmpty())
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
}