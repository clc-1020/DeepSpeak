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
import com.companion.chat.domain.proactive.ProactiveChannelPolicy
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
        // 1.13.3：指令提醒走**独立**高优先级「定时提醒」渠道 + CATEGORY_ALARM，
        // 与闲聊主动消息的 proactive_message 解耦 —— 用户静音闲聊时，自己设的定时提醒照样弹。
        // （此前两者共用 proactive_message：MIUI 上把闲聊通知划掉=该渠道被关=NONE，
        //   连用户自己设的定时提醒也一起不弹，就是这次「定时任务没有弹窗」的根因。）
        val isReminder = ProactiveChannelPolicy.usesReminderChannel(notification.category)
        val channelId = if (isReminder) REMINDER_CHANNEL_ID else CHANNEL_ID
        ensureChannel(channelId)
        // 渠道被系统关成 NONE 时，notify() 不报错、静默丢弃 —— 打一条日志便于排查。
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
                android.util.Log.w(
                    "ProactiveNotify",
                    "通知渠道 $channelId 已被系统关闭，提醒不会弹出（去系统设置重新开启该渠道）"
                )
            }
        }
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
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (isReminder) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_MESSAGE
            )
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

    private fun ensureChannel(channelId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) != null) return
        val (name, desc) = when (channelId) {
            REMINDER_CHANNEL_ID -> "定时提醒" to "你设定的定时提醒（到点必响）"
            else -> "主动消息" to "陪你聊天主动发来的消息"
        }
        val channel = NotificationChannel(
            channelId,
            name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = desc
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /** 闲聊主动消息渠道（保持原名，避免已有关闭状态失效）。 */
        const val CHANNEL_ID = "proactive_message"

        /** 指令提醒独立渠道（1.13.3）：全新 HIGH 渠道，不受「主动消息」被静音影响。 */
        const val REMINDER_CHANNEL_ID = "reminder"

        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val ACTION_PREFIX = "com.companion.chat.action.REMINDER_"
    }
}