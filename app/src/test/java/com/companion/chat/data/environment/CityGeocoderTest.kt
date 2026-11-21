package com.companion.chat.data.environment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 城市解析三条分支（命中 / 空结果 / 网络失败）+ 清空。任务 V2.1.1。
 *
 * 用假 HTTP 而不是 MockWebServer：这三个分支的**区别在结果处理，不在解析**，
 * 起服务器只是让测试变慢。真实 URL 拼装在 [EnvironmentUrlsTest] 里单独钉。
 */
class CityGeocoderTest {

    private val hitBody = """
        {"generationtime_ms":0.31,
         "results":[{"id":1816670,"name":"北京市","latitude":39.9075,"longitude":116.39723,
                     "country":"中国","admin1":"北京市","timezone":"Asia/Shanghai"}]}
    """.trimIndent()

    /** 查不到时 Open-Meteo **不返回 results 字段**（不是空数组）。 */
    private val emptyBody = """{"generationtime_ms":0.29}"""

    @Test
    fun hit_saves_resolved_name_and_coordinates() = runBlocking {
        val store = FakeKeyValueStore()
        val outcome = CityGeocoder(store, FakeHttpText.ok(hitBody)).resolve("北京")

        val hit = outcome as GeocodeOutcome.Hit
        assertEquals("北京市", hit.name)                 // 存接口返回的规范名，不是输入原文
        assertEquals(39.9075, hit.point.latitude, 0.0)
        assertEquals(116.39723, hit.point.longitude, 0.0)

        assertEquals("北京市", store.data[EnvironmentKeys.MANUAL_CITY_NAME])
        assertEquals("39.9075", store.data[EnvironmentKeys.MANUAL_LAT])
        assertEquals("116.39723", store.data[EnvironmentKeys.MANUAL_LON])
    }

    @Test
    fun empty_result_keeps_existing_cache() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.MANUAL_CITY_NAME to "上海市",
                EnvironmentKeys.MANUAL_LAT to "31.22",
                EnvironmentKeys.MANUAL_LON to "121.46",
            )
        )
        val outcome = CityGeocoder(store, FakeHttpText.ok(emptyBody)).resolve("不存在的城市")

        assertEquals(GeocodeOutcome.NoMatch, outcome)
        assertEquals("上海市", store.data[EnvironmentKeys.MANUAL_CITY_NAME])
        assertEquals("31.22", store.data[EnvironmentKeys.MANUAL_LAT])
    }

    @Test
    fun network_failure_keeps_existing_cache() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(EnvironmentKeys.MANUAL_CITY_NAME to "上海市")
        )
        val outcome = CityGeocoder(store, FakeHttpText.failure(null, "timeout")).resolve("北京")

        assertEquals(GeocodeOutcome.NetworkError, outcome)
        assertEquals("上海市", store.data[EnvironmentKeys.MANUAL_CITY_NAME])
    }

    @Test
    fun http_error_status_counts_as_network_error() = runBlocking {
        val store = FakeKeyValueStore()
        val outcome = CityGeocoder(store, FakeHttpText.failure(500, "Internal Server Error")).resolve("北京")

        assertEquals(GeocodeOutcome.NetworkError, outcome)
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun blank_query_clears_manual_city() = runBlocking {
        val store = FakeKeyValueStore(
            mapOf(
                EnvironmentKeys.MANUAL_CITY_NAME to "北京市",
                EnvironmentKeys.MANUAL_LAT to "39.9",
                EnvironmentKeys.MANUAL_LON to "116.4",
            )
        )
        val http = FakeHttpText.ok(hitBody)

        assertEquals(GeocodeOutcome.Cleared, CityGeocoder(store, http).resolve(""))
        assertTrue(store.data.isEmpty())
        assertEquals(0, http.urls.size)                  // 清空不该发请求

        // 纯空格同样算清空
        store.data[EnvironmentKeys.MANUAL_CITY_NAME] = "北京市"
        assertEquals(GeocodeOutcome.Cleared, CityGeocoder(store, http).resolve("   "))
        assertNull(store.data[EnvironmentKeys.MANUAL_CITY_NAME])
    }

    @Test
    fun malformed_and_typeless_json_do_not_crash() = runBlocking {
        val store = FakeKeyValueStore()
        assertNull(
            (CityGeocoder(store, FakeHttpText.ok("garbage"))).resolve("北京").let { it as? GeocodeOutcome.Hit }
        )
        assertEquals(
            GeocodeOutcome.NoMatch,
            CityGeocoder(store, FakeHttpText.ok("""{"results":[]}""")).resolve("北京")
        )
        assertEquals(
            GeocodeOutcome.NoMatch,
            CityGeocoder(store, FakeHttpText.ok("""{"results":[{"latitude":1.0}]}""")).resolve("北京")
        )
        assertEquals(
            GeocodeOutcome.NoMatch,
            CityGeocoder(store, FakeHttpText.ok("""[]""")).resolve("北京")
        )
    }

    @Test
    fun query_is_url_encoded() = runBlocking {
        val http = FakeHttpText.ok(hitBody)
        CityGeocoder(FakeKeyValueStore(), http).resolve("北京")

        val url = http.urls.single()
        assertTrue(url, url.contains("name=%E5%8C%97%E4%BA%AC"))
        assertTrue(url, url.contains("count=1"))
        assertTrue(url, url.contains("language=zh"))
        assertTrue(url, url.contains("format=json"))
    }
}