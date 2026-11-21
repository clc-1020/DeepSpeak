package com.companion.chat.data.environment

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
) {

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
                return LocatedCity.Manual(name, GeoPoint(lat, lon))
            }
        }
        return LocatedCity.Auto(KnownLocationPick.pick(locations))
    }

    /** 手动城市名；null 表示走自动定位。设置页回显用。 */
    suspend fun manualCityName(): String? =
        store.get(EnvironmentKeys.MANUAL_CITY_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /** 记住手动城市（三个键一起写，避免只写了一半）。 */
    suspend fun setManualCity(name: String, point: GeoPoint) {
        store.put(EnvironmentKeys.MANUAL_CITY_NAME, name)
        store.put(EnvironmentKeys.MANUAL_LAT, point.latitude.toString())
        store.put(EnvironmentKeys.MANUAL_LON, point.longitude.toString())
    }

    /** 清空手动城市 → 恢复自动定位。三个键都要删。 */
    suspend fun clearManualCity() {
        store.remove(EnvironmentKeys.MANUAL_CITY_NAME)
        store.remove(EnvironmentKeys.MANUAL_LAT)
        store.remove(EnvironmentKeys.MANUAL_LON)
    }

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
}