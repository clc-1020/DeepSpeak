package com.companion.chat.data.power

import android.app.AlarmManager
import android.app.NotificationManager
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
    val ignoringBatteryOptimizations: Boolean,
    /**
     * quick_access 通知渠道是否未被用户单独关掉。
     *
     * app 级 [notificationsEnabled]（areNotificationsEnabled）只看总开关；MIUI/HyperOS
     * 从通知栏「关闭」通常只关**渠道**，总开关仍是 allow → 通知发不出来却显示"已开启"。
     * 所以界面必须单独看渠道 importance 是否为 NONE。默认 true（渠道没建=没被单独禁）。
     */
    val quickAccessChannelEnabled: Boolean = true
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
        }.getOrDefault(false),
        quickAccessChannelEnabled = channelEnabled(QUICK_ACCESS_CHANNEL)
    )

    fun notificationsEnabled(): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)

    /**
     * 渠道级开关：用户把某个通知渠道关掉时 `getImportance()` 返回 IMPORTANCE_NONE。
     *
     * 这是**独立于** App 级 `areNotificationsEnabled()` 的第二层开关。MIUI/HyperOS 从通知栏
     * 滑动「关闭」通常只关当前渠道，App 级总开关仍是 allow，于是 areNotificationsEnabled()
     * 返回 true、但通知根本发不出来 —— 这就是「显示已开启却没通知」的根因。
     * 渠道尚未创建（从未发过通知）时返回 true（视为没被单独禁）。
     */
    fun channelEnabled(channelId: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(channelId) ?: return@runCatching true
        channel.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(true)

    /** 「通知真的能不能弹出来」：App 级开关 与 指定渠道级开关 同时成立。 */
    fun notificationsDeliverable(channelId: String): Boolean =
        notificationsEnabled() && channelEnabled(channelId)

    fun exactAlarmAllowed(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.canScheduleExactAlarms()
    }.getOrDefault(true)

    /** 跳本应用的通知设置（Android 8+ 有专门的 app-notification-settings 页，可看到各渠道）。 */
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
        Log.w(TAG, "startActivity 失败: ${e.javaClass.simpleName} (${intent.action})", e)
        false
    }

    private companion object {
        const val TAG = "SystemPermissions"

        /** 与 QuickAccessNotificationSpec.CHANNEL_ID 保持一致（用字面量避免 data→service 反向依赖）。 */
        const val QUICK_ACCESS_CHANNEL = "quick_access"
    }
}
