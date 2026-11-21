package com.companion.chat.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.companion.chat.worker.ReminderAlarmReceiver

/**
 * AlarmManager 精确闹钟实现。任务 3.3
 *
 * 优先用 [AlarmManager.setAlarmClock]（精确、可在 Doze 下触发，属「闹钟」类，最不被限制）；
 * Android 12+ 若用户未授予精确闹钟权限 → 退回 [AlarmManager.setAndAllowWhileIdle]（待机也能唤醒的非精确闹钟），
 * 并 Toast 引导用户去系统设置授予「闹钟和提醒」权限（任务 3.3 审查改进③）。
 */
class AlarmScheduler(private val context: Context) : AlarmScheduling {

    /** Android 12+ 是否具备精确闹钟权限（供 7.1 设置页做权限引导入口）。 */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    override fun schedule(
        requestCode: Int,
        triggerAtMillis: Long,
        taskDesc: String,
        reminderId: Long?,
        kind: AlarmKind,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(requestCode, taskDesc, reminderId, kind)
        try {
            when (kind) {
                // 环境检查每小时一次：用 setAndAllowWhileIdle（待机可唤醒，但状态栏不刷闹钟图标）。
                // 若用 setAlarmClock，整夜每小时一个闹钟图标，用户会以为设了一堆闹钟。
                AlarmKind.ENV_CHECK -> {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                    Log.d(TAG, "ENV_CHECK setAndAllowWhileIdle @ $triggerAtMillis")
                }
                // 用户自己设的提醒：setAlarmClock（精确、Doze 可触发、状态栏显示闹钟图标符合预期）
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                        notifyExactPermissionNeeded()
                        Log.w(TAG, "无精确闹钟权限，退回 setAndAllowWhileIdle @ $triggerAtMillis")
                    } else {
                        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), pi)
                        Log.d(TAG, "setAlarmClock @ $triggerAtMillis：$taskDesc")
                    }
                }
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            notifyExactPermissionNeeded()
            Log.w(TAG, "setAlarmClock 被拒，退回 setAndAllowWhileIdle", e)
        }
    }

    /** 无精确闹钟权限时弹一次 Toast 引导（主线程，忽略极端情况异常）。 */
    private fun notifyExactPermissionNeeded() {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "提醒已设，但未授予「精确闹钟」权限，可能略有延迟。请在系统设置中允许「闹钟和提醒」。",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) { /* 无 UI 环境则静默 */ }
    }

    override fun cancel(requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            data = Uri.parse("companion://reminder/$requestCode")
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(
        requestCode: Int,
        taskDesc: String,
        reminderId: Long?,
        kind: AlarmKind,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_TASK, taskDesc)
            putExtra(ReminderAlarmReceiver.EXTRA_REQUEST_CODE, requestCode)
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId ?: -1L)
            putExtra(ReminderAlarmReceiver.EXTRA_KIND, kind.name)
            // data 唯一化，保证不同 requestCode 的 PendingIntent 不互相覆盖
            data = Uri.parse("companion://reminder/$requestCode")
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
