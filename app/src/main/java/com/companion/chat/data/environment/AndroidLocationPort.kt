package com.companion.chat.data.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [LocationPort] 的 Android 实现。任务 V2.1.1。
 *
 * **两种取法，别混用**：
 * - [lastKnown]：被动读系统缓存（`getLastKnownLocation`），**不注册 `requestLocationUpdates`**。
 *   后台（整点检查、送达 Worker）只能走这条——后台请求定位在 Android 10+ 要「始终允许」权限，
 *   本 App 不申请。
 * - [currentOnce]：主动要一次（2026-11-22）。**只允许在前台调**（每天首次回前台刷新位置），
 *   那时只需要「仅使用 App 时允许」，不引入后台权限。
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

    /**
     * 主动请求一次位置，最多等 [timeoutMs]。
     *
     * 用 [LocationManagerCompat.getCurrentLocation]：API 30+ 走系统的当前定位接口，
     * 低版本由 androidx 内部降级成 `requestSingleUpdate`，minSdk 26 也能用。
     *
     * 超时用 [withTimeoutOrNull] 兜底（用户要求「加兜底」）：拿不到就返回 null，
     * 绝不让前台等下去。超时同时会取消系统侧的请求，不会留下监听。
     */
    override suspend fun currentOnce(provider: String, timeoutMs: Long): GeoPoint? {
        if (!hasLocationPermission()) return null
        val lm = manager ?: return null
        val enabled = try {
            lm.isProviderEnabled(provider)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!enabled) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(
                        lm,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (cont.isActive) {
                            cont.resume(location?.let { GeoPoint(it.latitude, it.longitude) })
                        }
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (e: IllegalArgumentException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** 粗定位就够算城市；细定位权限也认，有就用。 */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}