package com.companion.chat.data.environment

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * [CityNamePort] 的 Android 实现：用系统 [Geocoder] 反查地名。2026-11-22。
 *
 * **为什么用系统 Geocoder 而不是某个免 key 的在线接口**：
 * - 不新增第三方域名 / 不把坐标额外发给新厂商（反正是系统定位服务商在查）
 * - 不需要新权限（Geocoder 不需要定位权限，也不需要 INTERNET —— 联网在服务商那侧）
 * - 中文名由 [Locale.SIMPLIFIED_CHINESE] 指定，与手动城市走的 Open-Meteo `language=zh`
 *   命名风格一致（都是「佛山市」这种规范名）
 *
 * **已知会被阉割**：部分精简 ROM 没有 GeocodeProvider，[Geocoder.isPresent] 直接 false，
 * 或者调用抛 `IOException("Service not Available")`。两种情况都返回 null，
 * 注记第 3 段保持空 —— 和这个功能没做之前一样，不会更差。
 *
 * 调用点只在 `LocationCache.refreshTodayOnce`（每天最多一次，且已在该协程的 IO 线程上），
 * **绝不能在发送路径调**。
 */
class AndroidCityNameLookup(private val context: Context) : CityNamePort {

    override suspend fun nameOf(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return@withContext null
        if (!Geocoder.isPresent()) return@withContext null

        val addresses = try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.SIMPLIFIED_CHINESE)
                .getFromLocation(point.latitude, point.longitude, 1)
        } catch (e: IOException) {
            // "Service not Available" / 无网络 —— 常见，不记日志避免刷屏
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            // 个别厂商实现会抛别的（NullPointerException 之类），一律当失败
            null
        }

        val address = addresses?.firstOrNull() ?: return@withContext null

        // 城市级优先：locality（市）→ subAdminArea（区/县）→ adminArea（省）。
        // 只报省名对「今天热不热」没有意义，所以顺序不能反。
        listOfNotNull(address.locality, address.subAdminArea, address.adminArea)
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }
}