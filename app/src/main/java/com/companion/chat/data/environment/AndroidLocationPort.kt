package com.companion.chat.data.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * [LocationPort] 的 Android 实现。任务 V2.1.1。
 *
 * **只读历史位置**（`getLastKnownLocation`），不注册 `requestLocationUpdates`：
 * 天气只要城市级精度，被动读一次就够，主动定位会让「每小时判一次」的常驻逻辑变成耗电大户。
 *
 * 三类失败一律返回 null，**绝不抛**：
 * 1. 没有 `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` → `SecurityException`
 * 2. provider 未开启或设备上不存在（`getLastKnownLocation` 返回 null 或抛 `IllegalArgumentException`）
 * 3. 从未定位过（返回 null）
 *
 * 上层（[LocationCache]）再兜底到手动城市。
 */
class AndroidLocationPort(private val context: Context) : LocationPort {

    private val manager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    override fun lastKnown(provider: String): GeoPoint? {
        if (!hasLocationPermission()) return null
        val lm = manager ?: return null
        // provider 字符串与 LocationManager 常量同值，见 LocationProviders；这里仍然
        // 传字符串而不是常量，好让本类不依赖常量对象，两边任一改动都会被测试发现。
        val location = try {
            lm.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // 设备上不存在该 provider（部分精简 ROM 会这样）
            null
        }
        return location?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** 粗定位就够算城市；细定位权限也认，有就用。 */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}