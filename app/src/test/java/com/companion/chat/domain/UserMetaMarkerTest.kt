package com.companion.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** UserMetaMarker 单元测试（JVM）：日期/星期（中英文）/空地点/非法字符。 */
class UserMetaMarkerTest {

    // 2024-01-01 是星期一
    private val monday = LocalDate.of(2024, 1, 1)

    @Test
    fun chinese_format_with_location() {
        val s = UserMetaMarker.build(
            date = monday,
            location = "汕头",
            platform = "App",
            language = "中文"
        )
        assertEquals("2024-01-01,星期一,汕头,App,中文", s)
    }

    @Test
    fun empty_location_keeps_five_fields() {
        val s = UserMetaMarker.build(date = monday, location = "", platform = "App", language = "中文")
        assertEquals("2024-01-01,星期一,,App,中文", s)
        assertEquals(5, s.split(",").size)
    }

    @Test
    fun english_weekday() {
        val s = UserMetaMarker.build(date = monday, location = "", platform = "Web", language = "en")
        assertEquals("2024-01-01,Monday,,Web,en", s)
    }

    // ---- 2026-11-22：正常模式的注记（段数必须仍是 5，只填前两段） ----

    /**
     * **回归：段数必须是 5，且后三段不能为空。**
     *
     * 第一版发成 2 段（`2024-01-01,星期一`）→ 真机 400；
     * 第二版发成 5 段但把 2/3/4 段留空（`2024-01-01,星期一,,,`）→ **仍然 400**。
     * 结论：这个字段形状固定，**空字段也不被接受**。
     */
    @Test
    fun normal_mode_still_has_five_fields() {
        val s = UserMetaMarker.buildNormal(date = monday)
        assertEquals("2024-01-01,星期一,,App,中文", s)
        assertEquals(5, s.split(",").size)
    }

    @Test
    fun normal_mode_follows_language() {
        assertEquals("2024-01-01,Monday,,App,en", UserMetaMarker.buildNormal(date = monday, language = "en"))
    }

    @Test
    fun normal_mode_carries_no_location_or_weather() {
        val fields = UserMetaMarker.buildNormal(date = monday).split(",")
        assertEquals("", fields[2])   // 地点 + 天气：陪伴语义，问答不带
    }

    @Test
    fun weekday_mapping() {
        assertEquals("星期一", UserMetaMarker.weekday(monday, "中文"))
        assertEquals("Monday", UserMetaMarker.weekday(monday, "en"))
        // 2024-01-07 是星期日
        assertEquals("星期日", UserMetaMarker.weekday(LocalDate.of(2024, 1, 7), "中文"))
    }

    @Test
    fun comma_is_sanitized_to_keep_fields() {
        val s = UserMetaMarker.build(date = monday, location = "汕,头", platform = "App", language = "中文")
        assertEquals(5, s.split(",").size)
        assertEquals("2024-01-01,星期一,汕 头,App,中文", s)
    }

    @Test
    fun weather_merged_into_location_field() {
        val s = UserMetaMarker.build(
            date = monday, location = "汕头", platform = "App", language = "中文",
            weather = "18~25°C 降水概率10%"
        )
        // 天气并入第 3 段（location），段数恒为 5 —— 官方 latest_reminder 不接受第 6 段（真机 400 教训）
        assertEquals("2024-01-01,星期一,汕头 18~25°C 降水概率10%,App,中文", s)
        assertEquals(5, s.split(",").size)
    }

    @Test
    fun weather_without_location_keeps_third_field() {
        val s = UserMetaMarker.build(
            date = monday, location = "", platform = "App", language = "中文",
            weather = "降水概率80%"
        )
        assertEquals("2024-01-01,星期一,降水概率80%,App,中文", s)
        assertEquals(5, s.split(",").size)
    }

    @Test
    fun blank_weather_keeps_five_fields() {
        val s = UserMetaMarker.build(date = monday, location = "汕头", platform = "App", language = "中文", weather = "")
        assertEquals(5, s.split(",").size)
        assertEquals("2024-01-01,星期一,汕头,App,中文", s)
    }

    @Test
    fun weather_with_comma_is_sanitized() {
        val s = UserMetaMarker.build(
            date = monday, location = "", platform = "App", language = "中文",
            weather = "18,25"
        )
        assertEquals(5, s.split(",").size)
        assertEquals("2024-01-01,星期一,18 25,App,中文", s)
    }
}
