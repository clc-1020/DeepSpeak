package com.companion.chat.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.companion.chat.CompanionApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 提醒/追问通知的按钮回执接收器。任务 3.7 + V2.2.5
 *
 * 类名与 manifest 条目**不许改**：已发出的通知里 PendingIntent 指的就是这个组件，
 * 改名会让升级后用户点旧通知毫无反应（静默失效，最难排查的一类 bug）。
 *
 * 分支逻辑全在 [com.companion.chat.data.reminder.ReminderActionHandler]，本类只做
 * 「取 extra → 调用 → 收通知 → finish」，用 goAsync() 争取进程存活时间。
 */
class ReminderDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // null 兼容：旧通知的「已完成」按钮没有 EXTRA_ACTION（V2.2.5 之前发出的）
        val action = intent.getStringExtra(EXTRA_ACTION)
        val memoryId = intent.getLongExtra(EXTRA_MEMORY_ID, 0L)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pending = goAsync()
        val app = context.applicationContext as? CompanionApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app != null) {
                    val handled = app.reminderActionHandler.handle(action, memoryId, reminderId)
                    Log.d(TAG, "按钮回执 action=$action handled=$handled memory=$memoryId reminder=$reminderId")
                } else {
                    Log.w(TAG, "applicationContext 不是 CompanionApp，回执被忽略")
                }
                // 「再等等」也要收回通知：setAutoCancel 只在点通知本体时生效，
                // 点 action 按钮不会自动消失。15 分钟后是新的 msg/新的 notificationId，
                // 所以取消旧的是安全的。
                if (notificationId != 0) {
                    runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "回执处理失败 action=$action", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderDoneReceiver"
        const val EXTRA_MEMORY_ID = "extra_memory_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        /** V2.2.5：追问链的 reminder id */
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        /** `"done"` / `"postpone"` / `"dismiss"`；缺失按 done（旧通知兼容） */
        const val EXTRA_ACTION = "extra_action"
    }
}