package com.companion.chat.data.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.companion.chat.R
import com.companion.chat.ui.MainActivity

/**
 * 每日任务通知（2026-11-23）。
 *
 * 独立渠道「每日任务」（用户可单独静音），IMPORTANCE_HIGH 顶部弹出。
 * 点击打开 [MainActivity] 并带上会话 id，直接定位到该任务的专属会话。
 *
 * **刻意不接 App 勿扰**：用户明确——每日任务的时间是用户自己选的，到点照弹。
 */
class DailyTaskNotifier(private val context: Context) {

    fun show(title: String, body: String, conversationId: Long, notificationId: Int) {
        ensureChannel()
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val tapPi = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "每日任务" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, builder.build()) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "每日任务",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "每天定时让 AI 回答你设定的内容"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "daily_task"
        /**
         * 与 [com.companion.chat.data.proactive.AndroidProactiveNotifier.EXTRA_CONVERSATION_ID]
         * 用**同一个字符串键**，这样 [MainActivity] 用一套逻辑就能同时处理主动消息和每日任务的通知点击。
         */
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

        /** 通知 id：`2_000_000 + 任务id`，与主动消息/其他通知区分开。 */
        fun notificationIdOf(taskId: Long): Int {
            val v = 2_000_000 + (taskId % 1_000_000).toInt()
            return if (v <= 0) 2_000_000 else v
        }
    }
}
