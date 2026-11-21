package com.companion.chat.data.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL 拼装单测。任务 V2.1.1。 */
class EnvironmentUrlsTest {

    private val point = GeoPoint(39.9075, 116.39723)

    @Test
    fun forecast_url_contains_every_required_param() {
        val url = EnvironmentUrls.forecastUrl(point)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=39.9075"))
        assertTrue(url.contains("longitude=116.39723"))
        assertTrue(url.contains("temperature_2m_max"))
        assertTrue(url.contains("temperature_2m_min"))
        assertTrue(url.contains("precipitation_probability_max"))
        // 没有 past_days 就永远算不出降温
        assertTrue(url.contains("past_days=1"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test
    fun forecast_url_has_exactly_one_question_mark() {
        assertEquals(1, EnvironmentUrls.forecastUrl(point).count { it == '?' })
    }

    @Test
    fun geocode_url_encodes_chinese_city() {
        val url = EnvironmentUrls.geocodeUrl("北京")

        assertTrue(url.startsWith("https://geocoding-api.open-meteo.com/v1/search?"))
        assertTrue(url.contains("name=%E5%8C%97%E4%BA%AC"))
        assertFalse(url.contains("北京"))     // 不能把中文裸着塞进 URL
    }

    @Test
    fun geocode_url_trims_whitespace() {
        assertEquals(
            EnvironmentUrls.geocodeUrl("北京"),
            EnvironmentUrls.geocodeUrl("  北京  "),
        )
    }

    /** 空格必须编成 `%20` 而不是表单编码的 `+`，否则服务端可能把 `+` 当字面加号。 */
    @Test
    fun geocode_url_encodes_space_as_percent20() {
        val url = EnvironmentUrls.geocodeUrl("New York")
        assertTrue(url, url.contains("name=New%20York"))
        assertFalse(url, url.contains("+"))
    }

    @Test
    fun base_override_only_affects_prefix() {
        val url = EnvironmentUrls.forecastUrl(point, base = "http://127.0.0.1:1/f")
        assertTrue(url.startsWith("http://127.0.0.1:1/f?latitude="))
    }
}