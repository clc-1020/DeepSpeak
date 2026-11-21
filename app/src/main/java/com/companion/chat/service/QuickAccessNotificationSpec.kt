package com.companion.chat.service

/**
 * 通知栏常驻快捷入口的通知规格（纯 Kotlin，可 JVM 单测）。
 *
 * 把通知的 id / 渠道 / 文案 / 三个布尔标志 / 两个 requestCode 收敛到一个值对象，
 * 让「不撞 id」「requestCode 隔离」这些约束可以用可执行单测固定下来。
 */
data class QuickAccessNotificationSpec(
    val notificationId: Int,
    val channelId: String,
    val channelName: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val onlyAlertOnce: Boolean,
    val showWhen: Boolean,
    val tapRequestCode: Int,
    val voiceRequestCode: Int,
) {
    companion object {
        /**
         * 常驻通知固定 id = Int.MAX_VALUE。
         *
         * ProactiveDeliveryRunner 用 `msg.id % Int.MAX_VALUE` 派生通知 id，
         * 取模结果永远落在 [1, MAX_VALUE-1]，**取不到 MAX_VALUE 本身**。
         * 因此常驻通知不可能被任何主动消息通知顶掉（一旦撞 id，后 notify 的那条
         * 会直接替换掉常驻通知，用户就再也点不到快捷入口了）。
         * 这里用 MAX_VALUE 是「可证明不撞」，不是「碰巧没撞」。
         */
        const val NOTIFICATION_ID = Int.MAX_VALUE

        const val CHANNEL_ID = "quick_access"

        /**
         * 两个 PendingIntent 的 requestCode 刻意用负数：
         * 现有正能量 requestCode 都落在正数区间（AlarmCodes 从 1 起，
         * AndroidProactiveNotifier 的「已完成」按钮 +100_000），负数与它们天然隔离，
         * 不会因 PendingIntent 复用而把常驻通知的点击意图替换成闹钟/提醒的意图。
         */
        const val TAP_REQUEST_CODE = -1
        const val VOICE_REQUEST_CODE = -2

        fun default(): QuickAccessNotificationSpec = QuickAccessNotificationSpec(
            notificationId = NOTIFICATION_ID,
            channelId = CHANNEL_ID,
            channelName = "快捷入口",
            title = "陪聊",
            text = "点这里回到聊天；右侧按钮可直接说话",
            ongoing = true,
            onlyAlertOnce = true,
            showWhen = false,
            tapRequestCode = TAP_REQUEST_CODE,
            voiceRequestCode = VOICE_REQUEST_CODE,
        )
    }
}
