package com.companion.chat.data.environment

/**
 * 经纬度。任务 V2.1.1。
 *
 * 刻意不用 `android.location.Location`：定位结果一旦跨出 `LocationPort` 就只剩坐标，
 * 纯 JVM 可测。
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 当前用哪个城市/坐标。任务 V2.1.1。
 *
 * - [Manual]：用户在设置里手填的城市，**不再调定位**（省电，且尊重用户意图）
 * - [Auto]：自动定位结果，坐标可能为 null（无权限 / 无历史位置 / provider 关闭）
 *
 * 2026-11-22 精简：原先 `Manual` 带一个 `name` 字段，但**生产代码一处都没读过它**
 * （设置页回显走 `LocationCache.manualCityName()`）。现在两个子类共用抽象属性 [point]，
 * 调用方一律写 `locationCache.current().point`，不用再写两边分支相同的 `when`。
 */
sealed class LocatedCity {

    /** 实际要用的坐标；null = 拿不到位置，只能用时间规则。 */
    abstract val point: GeoPoint?

    /** 手动城市。[point] 一定非空（坐标缺失时不会走到这里，见 [LocationCache.current]）。 */
    data class Manual(override val point: GeoPoint) : LocatedCity()

    /** 自动定位。[point] 为 null 表示拿不到位置 → 天气不可用，只用时间规则。 */
    data class Auto(override val point: GeoPoint?) : LocatedCity()
}