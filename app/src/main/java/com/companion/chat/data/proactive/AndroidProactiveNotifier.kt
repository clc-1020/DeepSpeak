package com.companion.chat.data.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.companion.chat.R
import com.companion.chat.domain.proactive.NotificationActionKind
import com.companion.chat.domain.proactive.NotificationActions
import com.companion.chat.domain.proactive.ProactiveNotification
import com.companion.chat.domain.proactive.ProactiveNotifier
import com.companion.chat.ui.MainActivity
import com.companion.chat.worker.ReminderDoneReceiver

/**
 * 主动消息通知（Android 实现）。任务 3.5
 *
 * IMPORTANCE_HIGH 渠道、顶部弹出；点击打开 MainActivity（带会话 id 供 UI 定位）。
 * 需 Android 13+ 运行时通知权限（POST_NOTIFICATIONS，UI 层申请）。
 */
class AndroidProactiveNotifier(private val context: Context) : ProactiveNotifier {

    override fun show(notification: ProactiveNotification) {
        ensureChannel()
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            notification.conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
        }
        val tapPi = PendingIntent.getActivity(
            context,
            notification.notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
        // 任务 3.7 + V2.2.5：按钮构成由 domain 的纯函数决定，这里只负责渲染。
        NotificationActions.planFor(
            notificationId = notification.notificationId,
            completeMemoryId = notification.completeMemoryId,
            followupReminderId = notification.followupReminderId,
            dismissLongMemoryId = notification.dismissLongMemoryId
        ).forEach { action ->
            val actionIntent = Intent(context, ReminderDoneReceiver::class.java).apply {
                // 同时设 action 字符串：Intent 的 filterEquals 不看 extras，
                // 只靠 requestCode 区分也可以，但两个都设是双保险。
                //
                // **必须拼上 notificationId**（V2.2.5 观察② 收口，2026-11-20）：
                // requestCode 由 NotificationActions 的固定偏移算出，不含 notificationId；
                // filterEquals 又只比 action + data + type + class… 不看 extras。
                // 两条通知若 requestCode 恰好相同（id 相差 20 万）就会互相覆写 → 点旧通知删错记忆。
                // 拼上 id 后 action 字符串天然唯一，覆写不可能发生。
                setAction(ACTION_PREFIX + action.kind.name.lowercase() + ":" + notification.notificationId)
                putExtra(ReminderDoneReceiver.EXTRA_ACTION, wireActionOf(action.kind))
                putExtra(ReminderDoneReceiver.EXTRA_NOTIFICATION_ID, notification.notificationId)
                // 1.9.4.2：按按钮种类带对应 id——DONE 带短期(指令)id、DISMISS 带长期(别提)id、POSTPONE 不带
                val memoryIdForAction = when (action.kind) {
                    NotificationActionKind.DONE -> notification.completeMemoryId
                    NotificationActionKind.DISMISS -> notification.dismissLongMemoryId
                    NotificationActionKind.POSTPONE -> null
                }
                memoryIdForAction?.let {
                    putExtra(ReminderDoneReceiver.EXTRA_MEMORY_ID, it)
                }
                notification.followupReminderId?.let {
                    putExtra(ReminderDoneReceiver.EXTRA_REMINDER_ID, it)
                }
            }
            val pi = PendingIntent.getBroadcast(
                context,
                action.requestCode,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(iconOf(action.kind), labelOf(action.kind), pi)
        }
        val built = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(notification.notificationId, built)
        } catch (_: SecurityException) {
            // 未授予 POST_NOTIFICATIONS：静默（消息已入库，不阻断）
        }
    }

    private fun labelOf(kind: NotificationActionKind): String = when (kind) {
        NotificationActionKind.DONE -> "已完成"
        NotificationActionKind.POSTPONE -> "再等等"
        NotificationActionKind.DISMISS -> "别提这个"
    }

    private fun iconOf(kind: NotificationActionKind): Int = when (kind) {
        NotificationActionKind.DONE -> R.drawable.ic_check
        NotificationActionKind.POSTPONE -> R.drawable.ic_pause
        NotificationActionKind.DISMISS -> R.drawable.ic_not_interested
    }

    private fun wireActionOf(kind: NotificationActionKind): String = when (kind) {
        NotificationActionKind.DONE -> NotificationActions.ACTION_DONE
        NotificationActionKind.POSTPONE -> NotificationActions.ACTION_POSTPONE
        NotificationActionKind.DISMISS -> NotificationActions.ACTION_DISMISS
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "主动消息",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "陪你聊天主动发来的消息"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "proactive_message"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val ACTION_PREFIX = "com.companion.chat.action.REMINDER_"
    }
}