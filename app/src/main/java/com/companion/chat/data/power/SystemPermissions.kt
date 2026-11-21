package com.companion.chat.data.power

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.util.Log

/**
 * 系统权限 / 关键开关状态（任务 7.1 设置页「系统」区块用）。
 *
 * 这三项直接决定主动消息能不能真的弹出来：
 *  - 通知权限（Android 13+ POST_NOTIFICATIONS）
 *  - 精确闹钟（Android 12+ SCHEDULE_EXACT_ALARM，关掉会退化成不精确闹钟）
 *  - 电池优化白名单（任务 6.2 保活引导里的同一判据）
 */
data class SystemPermissionStatus(
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean
) {
    /** 三项都 OK 才算「完全就绪」。 */
    val allGranted: Boolean
        get() = notificationsEnabled && exactAlarmAllowed && ignoringBatteryOptimizations
}

/**
 * 权限状态读取 + 跳系统设置页（任务 7.1）。
 *
 * 只读状态/发 Intent，不改任何系统设置；跳转失败（个别 ROM 没有对应页）回落应用详情页。
 * 属 Android 运行时（编译验证）；判据本身没有可单测的纯逻辑，阈值/分支都在系统 API 上。
 */
class SystemPermissions(private val context: Context) {

    fun status(): SystemPermissionStatus = SystemPermissionStatus(
        notificationsEnabled = notificationsEnabled(),
        exactAlarmAllowed = exactAlarmAllowed(),
        ignoringBatteryOptimizations = runCatching {
            KeepAliveLauncher(context).isIgnoringBatteryOptimizations()
        }.getOrDefault(false)
    )

    fun notificationsEnabled(): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)

    fun exactAlarmAllowed(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    }.getOrDefault(true)

    /** 跳本应用的通知设置（Android 8+ 有专门的 app-notification-settings 页）。 */
    fun openNotificationSettings(): Boolean = start(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
    )

    /** 跳「精确闹钟」授权页（Android 12+）；低版本无此页 → 回落应用详情。 */
    fun openExactAlarmSettings(): Boolean {
        if (!start(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        ) {
            return openAppDetails()
        }
        return true
    }

    /** 兜底：应用详情页（任何设备都有）。 */
    fun openAppDetails(): Boolean = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    )

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "startActivity 失败: ${e.javaClass.simpleName} (${intent.action})")
        false
    }

    private companion object {
        const val TAG = "SystemPermissions"
    }
}