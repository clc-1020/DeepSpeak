package com.companion.chat.data.environment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「每天首次前台刷新定位」的结果（2026-11-22）。
 *
 * @param attempted 本次是否**真的发起了主动定位**。手动城市模式 / 当天已刷过 → false，
 *   调用方据此决定要不要顺带强制重拉天气。
 * @param point 刷新后该用的坐标；主动请求和被动兜底都失败 → null。
 */
data class DailyRefresh(
    val attempted: Boolean,
    val point: GeoPoint?,
)

/**
 * 当前位置/城市的选择。任务 V2.1.1。
 *
 * 核心规则（计划书 §4 V2.1.1）：
 * 1. `app_state[manual_city_name]` 非空 → **直接用 `manual_lat/lon`，一次定位都不调**
 * 2. 否则 → [KnownLocationPick] 被动读历史位置（NETWORK → GPS）
 * 3. 都没有 → [LocatedCity.Auto] 坐标 null；上层据此只用时间规则
 *
 * 第 1 条是省电关键：用户既然手填了城市，再调定位就是纯粹的浪费。
 */
class LocationCache(
    private val store: KeyValueStore,
    private val locations: LocationPort,
    /**
     * 坐标 → 城市名（2026-11-22）。null = 不反查，注记第 3 段在自动定位模式下保持空
     * ——既有单测与任何没装配它的调用方都自动退回改动前的行为。
     */
    private val cityNames: CityNamePort? = null,
) {

    /**
     * 串行化「每天首次刷新」。前台 onResume 与 ViewModel init 可能同时进来，
     * 没有锁的话两边都会读到「今天还没刷」，各发一次主动定位。
     */
    private val refreshLock = Mutex()

    /**
     * 当前用哪个坐标。
     *
     * 手动城市名存在但坐标缺失/损坏时，**回退到自动定位**而不是返回半残的手动结果：
     * 让状态自愈，总比「有个城市名但永远拉不到天气」好。这种情况下定位会被调用一次。
     */
    suspend fun current(): LocatedCity {
        val name = store.get(EnvironmentKeys.MANUAL_CITY_NAME)?.trim()
        if (!name.isNullOrEmpty()) {
            val lat = store.get(EnvironmentKeys.MANUAL_LAT)?.toDoubleOrNull()
            val lon = store.get(EnvironmentKeys.MANUAL_LON)?.toDoubleOrNull()
            if (lat != null && lon != null) {
                return LocatedCity.Manual(GeoPoint(lat, lon))
            }
        }
        return LocatedCity.Auto(KnownLocationPick.pick(locations))
    }

    /** 手动城市名；null 表示走自动定位。设置页回显用。 */
    suspend fun manualCityName(): String? =
        store.get(EnvironmentKeys.MANUAL_CITY_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 自动定位反查到的城市名（2026-11-22）；从没成功反查过 → null。
     *
     * 注意它**没有过期判断**：名字可能对应几天前的坐标。这样是刻意的——
     * 每天首次回前台刷新定位时会连带重查一次；中间这几天用旧名字报天气，
     * 比第 3 段空着更有用（模型至少能说「你在佛山」）。
     */
    suspend fun autoCityName(): String? =
        store.get(EnvironmentKeys.AUTO_CITY_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 注记第 3 段（`latest_reminder` 的地点）该用的地名：**手填优先，其次自动定位反查结果**。
     * 两者都没有 → null（第 3 段留空，与改动前一致）。
     *
     * **只读缓存，绝不联网**——它会被发送路径直接调用。
     */
    suspend fun displayCityName(): String? = manualCityName() ?: autoCityName()

    /**
     * 把反查到的城市名记下来。失败/未装配 lookup 一律静默（第 3 段保持原样）。
     *
     * 同时记下这批名字对应的坐标，便于排查「名字和坐标对不上」。
     */
    private suspend fun rememberAutoCity(point: GeoPoint) {
        val lookup = cityNames ?: return
        val name = runCatching { lookup.nameOf(point) }.getOrNull()?.trim()
        if (name.isNullOrEmpty()) return
        store.put(EnvironmentKeys.AUTO_CITY_NAME, name)
        store.put(EnvironmentKeys.AUTO_CITY_LAT, point.latitude.toString())
        store.put(EnvironmentKeys.AUTO_CITY_LON, point.longitude.toString())
    }

    // 2026-11-22 移除 setManualCity / clearManualCity：
    // 从 V2.1.6 起手动城市由 `CityGeocoder.resolve()` 直接写/删 `app_state` 三个键
    // （命中时写、输入清空时删），MainActivity 只注入 cityGeocoder，这两个方法
    // **生产路径零调用**，只有单测在用。删掉避免两处写同一份契约、谁改谁不知道。

    /**
     * 今天是否还没刷新过定位（「每天首次打开前台更新一次」）。
     *
     * 参数传 `epochDay` 而不是自己读时钟，测试才能钉住跨日边界。
     */
    suspend fun shouldRefreshToday(todayEpochDay: Long): Boolean =
        store.get(EnvironmentKeys.LOCATION_REFRESHED_DATE)?.toLongOrNull() != todayEpochDay

    /** 记下今天已刷新，避免同一天重复刷新。 */
    suspend fun markRefreshed(todayEpochDay: Long) {
        store.put(EnvironmentKeys.LOCATION_REFRESHED_DATE, todayEpochDay.toString())
    }

    /**
     * 每天首次回到前台时**主动**定位一次（2026-11-22，接的计划书 §4 那句
     * 「每天首次打开前台更新一次」—— 此前 [shouldRefreshToday] / [markRefreshed] 是死代码，
     * 生产路径一个调用者都没有，定位永远停在系统缓存里）。
     *
     * 只允许在前台调（`onResume`）：前台请求只要「仅使用 App 时允许」，
     * 后台请求在 Android 10+ 需要「始终允许」权限，本 App 刻意不申请（MIUI 还会反复降级）。
     *
     * 规则：
     * 1. **手动城市 → 一次定位都不调**（`attempted = false`），保持第 1 条硬承诺
     * 2. 当天已刷过 → 不动（`attempted = false`）
     * 3. 否则先主动要一次 NETWORK（最省电、室内可用、几公里精度够算城市），
     *    拿不到再退回被动 [KnownLocationPick]（NETWORK → GPS 的历史位置）
     * 4. **无论成功失败都"记账"**（用户 2026-11-22 拍板）：失败多是没权限 / provider 关闭，
     *    重试没有意义，当天不再反复空跑
     *
     * @param timeoutMs 主动请求的兜底超时；到点没结果就退回被动，绝不让前台干等
     */
    suspend fun refreshTodayOnce(
        todayEpochDay: Long,
        timeoutMs: Long = DEFAULT_REFRESH_TIMEOUT_MS,
    ): DailyRefresh = refreshLock.withLock {
        if (manualCityName() != null) return@withLock DailyRefresh(attempted = false, point = null)
        if (!shouldRefreshToday(todayEpochDay)) {
            return@withLock DailyRefresh(attempted = false, point = null)
        }

        val point = runCatching {
            locations.currentOnce(LocationProviders.NETWORK, timeoutMs)
                ?: KnownLocationPick.pick(locations)
        }.getOrNull()

        // 拿到坐标后顺手反查城市名（一天最多一次，失败的代价只是第 3 段继续空着）
        if (point != null) runCatching { rememberAutoCity(point) }

        markRefreshed(todayEpochDay)
        DailyRefresh(attempted = true, point = point)
    }

    companion object {
        /** 主动定位的兜底超时。5s 内一般能拿到（NETWORK 走基站/WiFi，通常几十毫秒到 1-2 秒）。 */
        const val DEFAULT_REFRESH_TIMEOUT_MS: Long = 5_000L
    }
}