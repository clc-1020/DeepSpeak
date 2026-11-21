package com.companion.chat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.companion.chat.CompanionApp
import com.companion.chat.R
import com.companion.chat.ui.MainActivity
import com.companion.chat.ui.speech.VoiceInputActivity

/**
 * 通知栏常驻快捷入口的前台服务。任务 V2.3.6
 *
 * 关键约束：
 * - onCreate / onStartCommand 第一件事就是 startForeground（5s ANR 窗口），
 *   onCreate 里不做任何额外 notify，也不碰数据库/网络。
 * - 渠道是独立的 quick_access（IMPORTANCE_LOW、静音、无震动、无角标），
 *   **绝不复用 proactive_message**：那是 IMPORTANCE_HIGH，会顶部弹窗。
 * - 两个 PendingIntent 都 FLAG_IMMUTABLE；requestCode 用负数与闹钟/提醒隔离。
 */
class QuickAccessService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 立刻进前台，抢在 5s ANR 窗口内；ensureChannel 只是系统服务调用，不是慢 IO。
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 的重启语义：进程被系统杀掉后服务会被重新拉起（intent 可能为 null）。
        // 若不复查开关，用户关掉开关后仍会看到通知「复活」，直到下次进前台才自愈。
        // 复查不通过 → stopSelf 并返回 START_NOT_STICKY，避免再次重启。
        val app = applicationContext as CompanionApp
        val shouldRun = QuickAccessEntrance.shouldRun(
            app.configRepository.getQuickAccessEnabled(),
            app.systemPermissions.notificationsEnabled(),
        )
        if (!shouldRun) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 关开关时 QuickAccessEntrance 会 stopService，通知必须立刻消失
        NotificationManagerCompat.from(this).cancel(QuickAccessNotificationSpec.NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val spec = QuickAccessNotificationSpec.default()
        ensureChannel(spec)
        val notification = buildNotification(spec)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, spec.notificationId, notification, type)
    }

    private fun ensureChannel(spec: QuickAccessNotificationSpec) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(spec.channelId) != null) return
        val channel = NotificationChannel(
            spec.channelId,
            spec.channelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(spec: QuickAccessNotificationSpec): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPi = PendingIntent.getActivity(
            this,
            spec.tapRequestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 注意：PendingIntent.getActivity 的官方约定——activity 在现有 activity 之外启动，
        // **必须**带 FLAG_ACTIVITY_NEW_TASK，否则可能被开进通知栏/调用方所在 task，
        // 一露头就被 onPause 盖住 → 语音浮层瞬间 finish，表现为「点语音输入没反应」（V2.3.6 审查）。
        val voiceIntent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val voicePi = PendingIntent.getActivity(
            this,
            spec.voiceRequestCode,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, spec.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(spec.onlyAlertOnce)
            .setShowWhen(spec.showWhen)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapPi)
            .addAction(R.drawable.ic_notification, "🎙 语音输入", voicePi)
            .build()
    }
}
