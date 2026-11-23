package com.companion.chat.service

import android.content.Context
import android.util.Log
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.SystemPermissions

/**
 * 通知栏常驻快捷入口的**唯一启停决策点**（纯决策逻辑可 JVM 单测）。
 *
 * UI 线只在 MainActivity.onResume 与设置页保存时调 [sync]。
 *
 * 2026-11-22：从「前台服务(FGS) + ongoing 通知」改为**普通通知**。
 * 原因：MIUI/HyperOS 的 `NotificationFilterController` 会 `filter out ongoing notif`，
 * 把 FGS 的常驻通知直接丢弃（服务在跑、通知栏里却没有）。普通通知（非 ongoing、非 FGS）
 * 才能正常显示。幂等：固定 id，重复 notify 是覆盖更新，不会产生第二条。
 */
object QuickAccessEntrance {

    /** 开关与通知权限（App 级 + 渠道级）同时成立才显示常驻入口。 */
    fun shouldRun(enabled: Boolean, notificationGranted: Boolean): Boolean = enabled && notificationGranted

    /**
     * 按当前开关/权限状态贴/撤常驻通知。
     *
     * @return 本次期望的「是否在显示」；异常时返回 false（绝不抛）。
     */
    fun sync(context: Context): Boolean {
        return try {
            val app = context.applicationContext as CompanionApp
            val enabled = app.configRepository.getQuickAccessEnabled()
            // 必须用「App 级 + quick_access 渠道级」综合判断：只看 areNotificationsEnabled()
            // 的话，MIUI 从通知栏关掉渠道后它仍返回 true，会贴不出通知却以为在跑。
            val notificationGranted = SystemPermissions(app)
                .notificationsDeliverable(QuickAccessNotificationSpec.CHANNEL_ID)

            if (!shouldRun(enabled, notificationGranted)) {
                QuickAccessNotifier.cancel(context)
                false
            } else {
                QuickAccessNotifier.show(context)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "同步常驻快捷入口失败: ${e.javaClass.simpleName} ${e.message}", e)
            false
        }
    }

    private const val TAG = "QuickAccessEntrance"
}
