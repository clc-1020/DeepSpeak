package com.companion.chat.data.environment

import com.google.gson.JsonParser

/**
 * 城市名解析结果。任务 V2.1.1。
 *
 * 三种「没成功」的情形刻意分开，因为**UI 文案和缓存处理完全不同**（计划书 §4）：
 * - [NoMatch]：查到了但没这个城市 → Toast「找不到该城市，请检查拼写」，**不动缓存**
 * - [NetworkError]：网络失败 → Toast「网络异常，使用上次位置」，**保留原缓存**
 * - [Cleared]：输入被清空 → 不 Toast，**删掉手动城市**，恢复自动定位
 *
 * 数据层不弹 Toast（那是 `ui/` 的活），只把结果交出去。UI 线的映射见计划书 §11 的接口约定。
 */
sealed class GeocodeOutcome {
    /** 命中，并且已经写进 `app_state`。 */
    data class Hit(val name: String, val point: GeoPoint) : GeocodeOutcome()

    /** 空结果。缓存保持原样。 */
    object NoMatch : GeocodeOutcome()

    /** 网络/HTTP 失败。缓存保持原样。 */
    object NetworkError : GeocodeOutcome()

    /** 输入清空，手动城市已删除。 */
    object Cleared : GeocodeOutcome()
}

/**
 * 城市名 → 坐标（Open-Meteo geocoding，免 key）。任务 V2.1.1。
 *
 * 命中时把**接口返回的规范名**（比如输入「北京」得到「北京市」）连同坐标写进 `app_state`，
 * 而不是存用户输入的原文——这样设置页回显的是标准地名。
 */
class CityGeocoder(
    private val store: KeyValueStore,
    private val http: HttpText,
) {

    /**
     * 解析并落地。
     *
     * 空/空白输入走 [GeocodeOutcome.Cleared]：删掉三个键，恢复自动定位。
     * 其余分支的缓存语义见 [GeocodeOutcome] 的说明。
     */
    suspend fun resolve(query: String): GeocodeOutcome {
        val city = query.trim()
        if (city.isEmpty()) {
            store.remove(EnvironmentKeys.MANUAL_CITY_NAME)
            store.remove(EnvironmentKeys.MANUAL_LAT)
            store.remove(EnvironmentKeys.MANUAL_LON)
            return GeocodeOutcome.Cleared
        }

        val result = http.get(EnvironmentUrls.geocodeUrl(city))
        if (result !is HttpTextResult.Ok) return GeocodeOutcome.NetworkError

        val hit = parseFirst(result.body) ?: return GeocodeOutcome.NoMatch
        store.put(EnvironmentKeys.MANUAL_CITY_NAME, hit.name)
        store.put(EnvironmentKeys.MANUAL_LAT, hit.point.latitude.toString())
        store.put(EnvironmentKeys.MANUAL_LON, hit.point.longitude.toString())
        return hit
    }

    /**
     * 取 `results` 第一条。
     *
     * Open-Meteo 在查不到时会**根本不返回 `results` 字段**（不是空数组），所以两条路径都要当空处理。
     * 解析失败也返回 null（当 NoMatch），绝不让畸形 JSON 冒泡成崩溃。
     */
    private fun parseFirst(body: String): GeocodeOutcome.Hit? = try {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) {
            null
        } else {
            val results = root.asJsonObject.getAsJsonArray("results")
            if (results == null || results.size() == 0) {
                null
            } else {
                val first = results.get(0)
                if (!first.isJsonObject) {
                    null
                } else {
                    val obj = first.asJsonObject
                    val lat = obj.get("latitude")?.takeIf { it.isJsonPrimitive }?.asDouble
                    val lon = obj.get("longitude")?.takeIf { it.isJsonPrimitive }?.asDouble
                    val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    if (lat == null || lon == null || name.isNullOrBlank()) {
                        null
                    } else {
                        GeocodeOutcome.Hit(name, GeoPoint(lat, lon))
                    }
                }
            }
        }
    } catch (e: RuntimeException) {
        null
    }
}