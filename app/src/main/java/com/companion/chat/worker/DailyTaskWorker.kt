package com.companion.chat.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.power.WakeLockScope

/**
 * 每日任务「生成与送达」Worker（2026-11-23）。
 *
 * 由 [ReminderAlarmReceiver] 在 DAILY_TASK 闹钟到点时入队。持 5 分钟 PARTIAL_WAKE_LOCK，
 * 跑 [CompanionApp.dailyTaskRunner]（网络生成 + 入库 + 通知 + 可选语音），失败有限次重试。
 *
 * 复用 [ProactiveDeliveryWorker] 同款持锁契约（WakeLockScope.run：拿锁→执行→必释放）。
 */
class DailyTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CompanionApp ?: return Result.success()
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.success()

        val wakeLock = PowerWakeLock(applicationContext, WakeLockPolicy.TAG_DAILY)
        return try {
            val ok = WakeLockScope.run(
                handle = wakeLock,
                timeoutMs = WakeLockPolicy.TIMEOUT_MS,
                log = { Log.d(TAG, "wake lock $it") }
            ) {
                app.dailyTaskRunner.run(taskId)
            }
            if (!ok && runAttemptCount + 1 < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            if (runAttemptCount + 1 >= MAX_RETRIES) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "DailyTaskWorker"
        const val KEY_TASK_ID = "daily_task_id"
        private const val MAX_RETRIES = 3

        /** 触发某条每日任务（同任务名 REPLACE：同一时刻只留一个待跑实例）。 */
        fun enqueue(context: Context, taskId: Long) {
            val request = OneTimeWorkRequestBuilder<DailyTaskWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("daily_task_$taskId", ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * 取消某条任务「已排好但还没跑」的后台作业（2026-11-23）。
         *
         * 用户删除/禁用任务时调用：万一闹钟刚响过、Worker 还排在队列里（或正在重试），
         * 停掉它，别让一条已删除的任务过后又弹通知。真正执行时 [DailyTaskRunner] 也会
         * 再查一次任务是否存在/启用，这里是「双重保险」。
         */
        fun cancel(context: Context, taskId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("daily_task_$taskId")
        }
    }
}
