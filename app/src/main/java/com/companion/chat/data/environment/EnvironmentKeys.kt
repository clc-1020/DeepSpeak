package com.companion.chat.data.environment

/**
 * 环境层的存储键与 URL。任务 V2.1.1。
 *
 * 键名与计划书 §4 V2.1.1 逐字一致（`manual_city_name` / `manual_lat` / `manual_lon`），
 * 因为它们是**跨版本持久化契约**：改名字等于让老用户的「手动城市」失效。
 */
object EnvironmentKeys {

    // ---------- app_state（Room，长期） ----------

    /** 手动城市名；空/缺失 = 走自动定位。 */
    const val MANUAL_CITY_NAME = "manual_city_name"

    /** 手动城市纬度（十进制字符串）。 */
    const val MANUAL_LAT = "manual_lat"

    /** 手动城市经度（十进制字符串）。 */
    const val MANUAL_LON = "manual_lon"

    /** 上次「前台刷新定位」的 `epochDay`。每天首次打开才刷新一次。 */
    const val LOCATION_REFRESHED_DATE = "location_refreshed_date"

    // ---------- SharedPreferences（天气缓存，可丢弃） ----------

    /** 上一次成功的预报原始 JSON（不裁剪字段，便于以后加规则不用重新拉）。 */
    const val WEATHER_CACHE_JSON = "weather_cache_json"

    /** 上述 JSON 的抓取时刻（epoch millis），用于 24h TTL。 */
    const val WEATHER_CACHE_AT = "weather_cache_at"
}

/**
 * Open-Meteo 端点（免 API key）。任务 V2.1.1。
 *
 * 选它而不是和风/心知：**不需要申请 key**，用户装完 App 就能用；免费额度按 IP 限流
 * （每天约 1 万次），本 App 一天最多拉几次，远不到限。
 */
object EnvironmentUrls {

    const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"
    const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"

    /**
     * 城市名 → 坐标。`count=1` 只要最匹配一条；`language=zh` 让返回的中文名可直接显示。
     *
     * 城市名做 URL 编码（中文必须编码）。
     *
     * 注意 `URLEncoder.encode` 是**表单编码**（`application/x-www-form-urlencoded`），它把空格编成 `+`。
     * 塞进 query string 后，`+` 是否被服务端当空格取决于其解析实现——**不能赌**，所以统一换成 `%20`。
     * 中文城市名不含空格时两种写法等价，这个替换只为「New York」这类输入。
     *
     * @param base 仅供测试指向 MockWebServer；生产调用一律用默认值
     */
    fun geocodeUrl(city: String, base: String = GEOCODE_BASE): String {
        val encoded = java.net.URLEncoder.encode(city.trim(), "UTF-8").replace("+", "%20")
        return "$base?name=$encoded&count=1&language=zh&format=json"
    }

    /**
     * 坐标 → 预报。
     *
     * `past_days=1` 是**必须的**：规则引擎要算降温，没有昨天的 `temperature_2m_min` 就算不出来。
     * `timezone=auto` 让返回的 `daily.time` 是**当地日期**，与用户看到的「今天」一致；
     * 若用 UTC，UTC+8 的用户在 08:00 前会拿到昨天的日期，规则整天错位。
     *
     * @param base 仅供测试指向 MockWebServer；生产调用一律用默认值
     */
    fun forecastUrl(point: GeoPoint, base: String = FORECAST_BASE): String = buildString {
        append(base)
        append("?latitude=").append(point.latitude)
        append("&longitude=").append(point.longitude)
        append("&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max")
        append("&past_days=1")
        append("&timezone=auto")
    }
}