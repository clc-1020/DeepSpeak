package com.companion.chat.data.environment

/**
 * 定位端口。任务 V2.1.1。
 *
 * 只暴露「按 provider 名取最近一次已知位置」，**不做排序、不抓异常**——排序在
 * [KnownLocationPick]，异常在 Android 实现里吞掉。这样「NETWORK 优先、GPS 兜底」这段
 * 关键顺序逻辑就能在主机上被单测覆盖。
 *
 * 刻意不用 `android.location.Location` 作返回值：拿到坐标后上层只需经纬度。
 */
interface LocationPort {
    /**
     * @param provider [LocationProviders.NETWORK] 或 [LocationProviders.GPS]
     * @return 最近一次已知位置；无权限 / provider 关闭 / 从未定位过 → null
     */
    fun lastKnown(provider: String): GeoPoint?

    /**
     * **主动**要一次当前位置（2026-11-22 新增，仅供「每天首次回到前台」使用）。
     *
     * 与 [lastKnown] 的区别：这里真的会向系统发起一次定位请求，能拿到比系统缓存更新的位置；
     * 代价是要等（毫秒到几秒），所以必须带 [timeoutMs] 兜底，且**只在前台调用**——
     * 后台请求定位在 Android 10+ 需要「始终允许」权限，本 App 刻意不申请。
     *
     * 默认实现退化为 [lastKnown]：这样所有既有假实现（测试）无需改动，
     * 且任何不支持主动定位的端口都自动降级成「和以前一样」。
     *
     * @return 超时 / 无权限 / provider 不可用 → null（不抛）
     */
    suspend fun currentOnce(provider: String, timeoutMs: Long): GeoPoint? = lastKnown(provider)
}

/**
 * provider 名。任务 V2.1.1。
 *
 * 值与 `android.location.LocationManager.NETWORK_PROVIDER` / `GPS_PROVIDER` **完全相同**
 * （"network" / "gps"）。在这里重写一份是为了让 domain 侧的选择逻辑不 import Android 类。
 */
object LocationProviders {
    const val NETWORK = "network"
    const val GPS = "gps"
}

/**
 * 取最近一次已知位置：**NETWORK 优先，再 GPS**。任务 V2.1.1。
 *
 * 顺序不是随便定的：
 * - `NETWORK_PROVIDER` 走基站/WiFi，**室内也有**，耗电极低，返回快
 * - `GPS_PROVIDER` 室内常为空、耗电高
 *
 * 天气只需要城市级精度（几公里），低精度反而更稳。所以先网络后 GPS，取到就停。
 *
 * 注意这里取的是 `getLastKnownLocation`——**被动读历史**，不主动请求定位更新。
 * 主动请求会：① 需要更高的电量预算，② 需要在 `onResume` 里注册/注销监听，
 * ③ 首次使用常常立刻返回 null。对一个「每小时判一次要不要关心用户」的功能，
 * 读历史位置 + 手动城市兜底已经完全够，且电池代价接近零（这是本 App 的硬约束）。
 */
object KnownLocationPick {

    /** 尝试顺序。测试直接断言这个列表，别再写死一份。 */
    val ORDER: List<String> = listOf(LocationProviders.NETWORK, LocationProviders.GPS)

    /** 按 [ORDER] 逐个尝试，返回第一个非空。全空返回 null（不是异常）。 */
    fun pick(port: LocationPort): GeoPoint? {
        for (provider in ORDER) {
            val point = port.lastKnown(provider)
            if (point != null) return point
        }
        return null
    }
}