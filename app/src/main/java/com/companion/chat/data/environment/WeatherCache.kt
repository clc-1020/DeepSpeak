package com.companion.chat.data.environment

import com.companion.chat.domain.environment.WeatherSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 一次天气读取的结果。任务 V2.1.1。
 *
 * 两个字段都可空，因为**它们各自独立地可能缺失**：
 * - `today = null`：没坐标、网络失败、缓存过期又拉不到 → 规则引擎只用时间规则
 * - `yesterday = null`：只有今天的数据（API 不返 `past_days`）→ 算不出降温，[EnvironmentHook.TempDrop] 不触发
 */
data class WeatherBundle(
    val today: WeatherSnapshot?,
    val yesterday: WeatherSnapshot?,
) {
    companion object {
        /** 什么都没有。天气不可用时的统一返回值。 */
        val EMPTY = WeatherBundle(today = null, yesterday = null)
    }
}

/**
 * 天气拉取 + 24h 缓存。任务 V2.1.1。
 *
 * 为什么要有缓存：环境检查每小时可能跑一次（V2.1.5 的整点闹钟），而天气一天只需拉一次。
 * 没有缓存就是每小时一次网络请求，既费电又可能被限流。
 *
 * TTL 语义（计划书 §4）：「超 24h 天气字段返回 null」。含义是**过期数据一律不用**——
 * 宁可这小时不判天气，也不拿昨天的温度提醒用户「今天降温」。过期后会先尝试重新拉；
 * 拉到了就用新的，拉不到就返回 [WeatherBundle.EMPTY]（而不是拿旧数据凑）。
 *
 * 缓存存在 `SharedPreferences`（[SharedPrefsKeyValueStore]）而不是 Room：它随时可丢，
 * 不需要事务、不需要查询、丢了就是多拉一次网络。
 *
 * @param http 网络端口（测试注入假实现或配 MockWebServer）
 * @param nowMillis 时钟。注入以便钉住 24h 边界，绝不直接 `System.currentTimeMillis()`
 * @param ttlMillis 缓存有效期，默认 24h
 */
class WeatherCache(
    private val store: KeyValueStore,
    private val http: HttpText,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    /** 预报端点基址。**仅供测试指向 MockWebServer**，生产一律用默认值。 */
    private val forecastBase: String = EnvironmentUrls.FORECAST_BASE,
) {

    /**
     * 只读缓存、**绝不联网、也不需要坐标**，返回完整 bundle（today + yesterday）。
     *
     * 2026-11-22 新增。用途：整点环境检查先问缓存——缓存新鲜时规则引擎要的
     * today/yesterday 都在里面，**完全不必读定位**。此前每次 `recall()` 都会先
     * `locationCache.current()`，而后台读位置在 Android 10+ 必被拒；缓存新鲜的 23/24 小时
     * 里那次读取纯属白跑。
     *
     * @return 缓存缺失 / 过期（> 24h）/ 解析失败 → [WeatherBundle.EMPTY]
     */
    suspend fun peekBundle(today: LocalDate): WeatherBundle {
        val cachedJson = store.get(EnvironmentKeys.WEATHER_CACHE_JSON) ?: return WeatherBundle.EMPTY
        val cachedAt = store.get(EnvironmentKeys.WEATHER_CACHE_AT)?.toLongOrNull()
            ?: return WeatherBundle.EMPTY
        if (!isFresh(cachedAt)) return WeatherBundle.EMPTY
        return parse(cachedJson, today)
    }

    /**
     * **只读缓存、绝不联网**（V2.1.8 发送路径专用）。
     *
     * 为什么单独开一个方法：`load()` 在缓存缺失/过期时**会现场发 HTTP**（天气客户端
     * readTimeout 20s），把它放在「用户点发送」的路径上，真机表现为**消息半天不上屏**
     * （实测：`weather_cache_at` 与 user 消息时间戳只差 2ms，说明整个发送被那次网络请求挡住）。
     *
     * 发送路径改用本方法：有新鲜缓存就带上天气，没有就返回 null（注记里不带天气，
     * 模型按 `ContextBuilder.CAPABILITIES` 的约定说"这会儿拿不到"）。
     * 缓存由「整点环境检查」与聊天页的**后台预热**填充，不占发送路径。
     *
     * @return 今天的新鲜快照；缓存缺失/过期/解析失败 → null
     */
    suspend fun peek(today: LocalDate): WeatherSnapshot? = peekBundle(today).today

    /**
     * 失败重试版 [load]（1.9.3.1）：**仅用于后台预热**，绝不用于发送路径。
     *
     * 背景（2026-11-21 真机）：清掉应用数据后，天气缓存为空，唯一能填缓存的
     * [prefetchWeather] 只拉一次；而 Open-Meteo 预报端点在当前网络下**间歇性超时**
     * （实测同一 URL 既有 12–20s 超时、也有 HTTP 200）。一旦那一次撞上超时，
     * 缓存就永远是空的，发送路径的 [peek] 只读缓存 → 用户一直看不到天气。
     *
     * 判定「失败」= [load] 返回 [WeatherBundle.EMPTY]（无坐标 / 非 2xx / 解析不出）。
     * 重试 [DEFAULT_RETRY_ATTEMPTS] 次，每次间隔 [RETRY_DELAY_MS]；仍失败就认了，
     * 由整点环境检查下次再填，绝不阻塞任何前台路径。
     */
    suspend fun loadWithRetry(
        point: GeoPoint?,
        today: LocalDate,
        attempts: Int = DEFAULT_RETRY_ATTEMPTS,
        retryDelayMs: Long = RETRY_DELAY_MS,
    ): WeatherBundle {
        var last = WeatherBundle.EMPTY
        repeat(attempts) { index ->
            last = load(point, today)
            if (last.today != null) return last
            if (index < attempts - 1 && retryDelayMs > 0) delay(retryDelayMs)
        }
        return last
    }

    /**
     * 读取天气。
     *
     * 流程（**顺序是修过的，别调回去**）：
     * 1. 缓存新鲜且 `force = false` → 用缓存解析（**不联网、连坐标都不需要**）
     * 2. 没有坐标 → EMPTY（不发请求）
     * 3. 缓存过期或没有（或 `force = true`）→ 联网；成功则写缓存并解析，失败则 EMPTY
     *
     * **为什么缓存必须先于坐标判空**（2026-11-22 修的真 bug）：
     * 原实现第一步就是 `if (point == null) return EMPTY`。而整点环境检查跑在**后台**，
     * 手机上只给了「仅在使用 App 时允许」定位权限 → 后台 `getLastKnownLocation` 被系统拒
     * → point 恒为 null → 就算缓存里有今天的新鲜天气也被这行直接丢掉。
     * 表现为：**下雨 / 降温 / 高温三个天气钩子在自动定位模式下一次都不触发**，
     * 只剩熬夜、饭点这些跟位置无关的。而发送路径走 [peek]（不判 point），
     * 所以聊天注记里的天气看着是好的 —— 极难归因。
     *
     * @param point 当前坐标；null 表示定位失败且没有手动城市
     * @param today 用户本地日期（`LocalDate.now()`），用来在 `daily.time` 里定位今天与昨天
     * @param force 忽略新鲜缓存强制重拉。**只在「坐标刚变过」时用**（每天首次前台刷新定位之后），
     *   否则 24h 缓存里还是旧城市的天气。强制重拉失败时返回 [WeatherBundle.EMPTY] 且
     *   **不覆盖旧缓存**——宁可这小时不判天气，也不拿旧城市的数据冒充新城市。
     */
    suspend fun load(
        point: GeoPoint?,
        today: LocalDate,
        force: Boolean = false,
    ): WeatherBundle {
        val cachedJson = store.get(EnvironmentKeys.WEATHER_CACHE_JSON)
        val cachedAt = store.get(EnvironmentKeys.WEATHER_CACHE_AT)?.toLongOrNull()
        if (!force && cachedJson != null && cachedAt != null && isFresh(cachedAt)) {
            return parse(cachedJson, today)
        }

        if (point == null) return WeatherBundle.EMPTY

        val result = http.get(EnvironmentUrls.forecastUrl(point, forecastBase))
        if (result !is HttpTextResult.Ok) return WeatherBundle.EMPTY

        store.put(EnvironmentKeys.WEATHER_CACHE_JSON, result.body)
        store.put(EnvironmentKeys.WEATHER_CACHE_AT, nowMillis().toString())
        return parse(result.body, today)
    }

    /**
     * 缓存是否仍有效。
     *
     * `fetchedAt` 在未来（用户手动改了系统时间、或时区回跳）也算过期——重新拉一次是无害的，
     * 相比之下「拿未来时间当新鲜」会让缓存永远不刷新。
     */
    fun isFresh(fetchedAtMillis: Long, atMillis: Long = nowMillis()): Boolean {
        val age = atMillis - fetchedAtMillis
        return age >= 0 && age < ttlMillis
    }

    /**
     * 解析 Open-Meteo 的 `daily` 块。
     *
     * 三条真实存在的坑：
     * 1. **数组里可能有 null**（某个格点缺测）→ 逐元素判空，不整体失败
     * 2. **`daily.time` 里的日期可能不含今天**（设备时钟离谱、或只返了未来几天）→ 那天就不给数据
     * 3. 值是 `Double`（如 12.1）而 [WeatherSnapshot] 用 `Int` → 四舍五入（35.6→36 算高温，35.4→35 不算）
     */
    private fun parse(body: String, today: LocalDate): WeatherBundle {
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return WeatherBundle.EMPTY

            val daily = root.asJsonObject.get("daily")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return WeatherBundle.EMPTY
            val timesEl = daily.get("time")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return WeatherBundle.EMPTY

            val dates = ArrayList<String>(timesEl.size())
            for (i in 0 until timesEl.size()) {
                val el = timesEl.get(i)
                if (el != null && el.isJsonPrimitive) dates.add(el.asString)
            }

            val maxes = daily.arrayOrNull("temperature_2m_max")
            val mins = daily.arrayOrNull("temperature_2m_min")
            val precs = daily.arrayOrNull("precipitation_probability_max")

            val todayIndex = dates.indexOf(today.toString())
            val yesterdayDate = today.minusDays(1)
            val yesterdayIndex = dates.indexOf(yesterdayDate.toString())

            WeatherBundle(
                today = if (todayIndex >= 0) {
                    WeatherSnapshot(
                        date = today,
                        tempMax = maxes.intOrNull(todayIndex),
                        tempMin = mins.intOrNull(todayIndex),
                        precipProbabilityMax = precs.intOrNull(todayIndex),
                    )
                } else {
                    null
                },
                yesterday = if (yesterdayIndex >= 0) {
                    WeatherSnapshot(
                        date = yesterdayDate,
                        tempMax = maxes.intOrNull(yesterdayIndex),
                        tempMin = mins.intOrNull(yesterdayIndex),
                        precipProbabilityMax = precs.intOrNull(yesterdayIndex),
                    )
                } else {
                    null
                },
            )
        } catch (e: RuntimeException) {
            // 畸形 JSON / 类型不符 / 越界，一律当「没有天气」，绝不让它冒泡打断环境检查
            WeatherBundle.EMPTY
        }
    }

    companion object {
        /** 24 小时。 */
        const val DEFAULT_TTL_MILLIS = 24 * 60 * 60 * 1000L

        /** 后台预热总尝试次数（1.9.3.1）：首次 + 1 次重试，应对 Open-Meteo 间歇超时。 */
        const val DEFAULT_RETRY_ATTEMPTS = 2

        /** 两次尝试之间的退避。只在后台协程里 delay，不占发送路径。 */
        const val RETRY_DELAY_MS = 1_500L
    }
}

private fun com.google.gson.JsonObject.arrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

/**
 * 取第 [index] 个元素为 Int。
 *
 * null 元素、非数字元素、越界一律返回 null——Open-Meteo 在缺测时确实会填 `null`。
 */
private fun JsonArray?.intOrNull(index: Int): Int? {
    if (this == null || index < 0 || index >= size()) return null
    val el = get(index) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    val prim = el.asJsonPrimitive
    if (!prim.isNumber) return null
    return Math.round(prim.asDouble).toInt()
}