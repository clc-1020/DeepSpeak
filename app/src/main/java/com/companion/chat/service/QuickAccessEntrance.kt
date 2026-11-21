package com.companion.chat.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.SystemPermissions

/**
 * 通知栏常驻快捷入口的**唯一启停决策点**（纯决策逻辑可 JVM 单测）。
 *
 * UI 线只在 MainActivity.onResume 调 [sync]；CompanionApp.onCreate **不调**，
 * 因为冷启动从 Application 里拉前台服务在 Android 12+ 会抛
 * ForegroundServiceStartNotAllowedException。
 *
 * 幂等性：开启路径每次都发 `startForegroundService`，但服务里 notify 用的是
 * 固定 id（[QuickAccessNotificationSpec.NOTIFICATION_ID]），系统对同 id 是覆盖更新，
 * 重复调用不会产生第二条通知。
 */
object QuickAccessEntrance {

    /** 开关与通知权限同时成立才运行常驻入口。 */
    fun shouldRun(enabled: Boolean, notificationGranted: Boolean): Boolean = enabled && notificationGranted

    /**
     * 按当前开关/权限状态同步前台服务的启停。
     *
     * @return 本次期望的「是否在运行」；启动被系统拒绝时返回 false（绝不抛异常）。
     */
    fun sync(context: Context): Boolean {
        return try {
            val app = context.applicationContext as CompanionApp
            val enabled = app.configRepository.getQuickAccessEnabled()
            val notificationGranted = SystemPermissions(app).notificationsEnabled()

            if (!shouldRun(enabled, notificationGranted)) {
                context.stopService(Intent(context, QuickAccessService::class.java))
                NotificationManagerCompat.from(context)
                    .cancel(QuickAccessNotificationSpec.NOTIFICATION_ID)
                false
            } else {
                // Android 12+ 后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException；
                // 整段 try/catch 吞掉并记日志，绝不让 UI 的 onResume 崩掉。
                ContextCompat.startForegroundService(context, Intent(context, QuickAccessService::class.java))
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "同步常驻快捷入口失败: ${e.javaClass.simpleName} ${e.message}", e)
            false
        }
    }

    private const val TAG = "QuickAccessEntrance"
}
