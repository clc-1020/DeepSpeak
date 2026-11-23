package com.companion.chat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.companion.chat.R
import com.companion.chat.ui.MainActivity
import com.companion.chat.ui.speech.VoiceInputActivity

/**
 * 通知栏常驻快捷入口的通知构建与显示（2026-11-22：由前台服务改为普通通知）。
 *
 * **为什么不再用前台服务(FGS)**：MIUI/HyperOS 的 `NotificationFilterController` 会
 * `filter out ongoing notif`——把 FGS 的 ongoing 常驻通知直接从通知栏丢掉（服务在跑、
 * 通知却看不见，用户实测）。改用**普通通知**（非 ongoing、非 FGS）系统就会正常显示；
 * 进程被杀后普通通知仍由系统保留，回到前台再 sync 一次即可保持。
 *
 * 代价：普通通知不再"划不掉"，用户可手动划走——但每次回前台 [QuickAccessEntrance.sync]
 * 会重新贴回来；关开关则 [cancel]。
 */
object QuickAccessNotifier {

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(QuickAccessNotificationSpec.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            QuickAccessNotificationSpec.CHANNEL_ID,
            QuickAccessNotificationSpec.default().channelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** 贴/更新常驻快捷入口通知（固定 id，重复调用是覆盖更新，不会多条）。 */
    fun show(context: Context) {
        val spec = QuickAccessNotificationSpec.default()
        ensureChannel(context)
        NotificationManagerCompat.from(context).notify(spec.notificationId, build(context, spec))
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(QuickAccessNotificationSpec.NOTIFICATION_ID)
    }

    private fun build(context: Context, spec: QuickAccessNotificationSpec): Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            spec.tapRequestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 注意：PendingIntent.getActivity 的官方约定——activity 在现有 activity 之外启动，
        // **必须**带 FLAG_ACTIVITY_NEW_TASK，否则可能被开进通知栏/调用方所在 task。
        val voiceIntent = Intent(context, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val voicePi = PendingIntent.getActivity(
            context,
            spec.voiceRequestCode,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            // 刻意不 setOngoing(true)：MIUI 的 NotificationFilterController 会把 ongoing
            // （尤其是 FGS）通知过滤掉、通知栏里看不到。用普通通知才能显示。
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(spec.showWhen)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapPi)
            .addAction(R.drawable.ic_notification, "🎙 语音输入", voicePi)
            .build()
    }
}
