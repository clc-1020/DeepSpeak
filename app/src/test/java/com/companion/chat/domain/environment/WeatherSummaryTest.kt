package com.companion.chat.domain.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** WeatherSummary（V2.1.8）：快照 → 中文天气短语。纯函数，JVM 可测。 */
class WeatherSummaryTest {

    private val day = LocalDate.of(2026, 11, 20)

    @Test
    fun both_temps_and_rain() {
        val s = WeatherSummary.line(WeatherSnapshot(day, tempMax = 25, tempMin = 18, precipProbabilityMax = 10))
        assertEquals("18~25°C 降水概率10%", s)
    }

    @Test
    fun no_rain_field() {
        val s = WeatherSummary.line(WeatherSnapshot(day, tempMax = 25, tempMin = 18, precipProbabilityMax = null))
        assertEquals("18~25°C", s)
    }

    @Test
    fun only_max() {
        val s = WeatherSummary.line(WeatherSnapshot(day, tempMax = 35, tempMin = null, precipProbabilityMax = null))
        assertEquals("最高35°C", s)
    }

    @Test
    fun only_min() {
        val s = WeatherSummary.line(WeatherSnapshot(day, tempMax = null, tempMin = 8, precipProbabilityMax = null))
        assertEquals("最低8°C", s)
    }

    @Test
    fun only_rain() {
        val s = WeatherSummary.line(WeatherSnapshot(day, tempMax = null, tempMin = null, precipProbabilityMax = 80))
        assertEquals("降水概率80%", s)
    }

    @Test
    fun all_missing_returns_null() {
        assertNull(WeatherSummary.line(WeatherSnapshot(day, null, null, null)))
    }

    @Test
    fun null_snapshot_returns_null() {
        assertNull(WeatherSummary.line(null))
    }
}
