package com.companion.chat.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.power.WakeLockScope
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import java.util.concurrent.TimeUnit

/**
 * 主动消息「生成与送达」Worker（任务 3.5）。
 *
 * 由检查 Worker（3.2，投递成功后）或闹钟接收器（3.3，入队后）触发，每次尝试送达队列里最高优先级的一条。
 * 生成失败会有限次重试（保留待发），避免网络抖动直接丢消息。
 *
 * **任务 6.1**：被唤醒时先拿 `PARTIAL_WAKE_LOCK`（[WakeLockPolicy.TAG_DELIVERY]），
 * 生成 + 入库 + 通知结束（或异常/取消）**必释放**；5 分钟超时兜底。
 * 语音朗读由 [com.companion.chat.data.tts.ReadAloudController] 另持一把 10 分钟的锁，
 * 因此即使 5 分钟外层锁到期，播报仍能继续。
 *
 * **任务 7.2**：勿扰区间内不发通知时，送达层会把消息顺延到区间结束并返回
 * [ProactiveDeliveryOutcome.DND_DEFERRED]，此时用 [enqueueDelayed] 安排一次到点再送。
 */
class ProactiveDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CompanionApp ?: return Result.success()
        val wakeLock = PowerWakeLock(applicationContext, WakeLockPolicy.TAG_DELIVERY)
        return try {
            val outcome = WakeLockScope.run(
                handle = wakeLock,
                timeoutMs = WakeLockPolicy.TIMEOUT_MS,
                log = { Log.d(TAG, "wake lock $it") }
            ) {
                app.proactiveDeliveryRunner.deliverNext()
            }
            when {
                outcome == ProactiveDeliveryOutcome.GENERATION_FAILED &&
                    runAttemptCount + 1 < MAX_RETRIES -> Result.retry()

                outcome == ProactiveDeliveryOutcome.DND_DEFERRED ->
                    // 已由送达层安排延迟任务，这里直接成功，别重试叠加
                    Result.success()

                else -> Result.success()
            }
        } catch (_: Exception) {
            if (runAttemptCount + 1 >= MAX_RETRIES) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "ProactiveDeliveryWorker"
        const val WORK_NAME = "proactive_delivery"
        private const val WORK_NAME_DEFERRED = "proactive_delivery_deferred"
        private const val MAX_RETRIES = 3

        /** 触发一次送达（REPLACE：合并同一时刻的多次请求）。 */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProactiveDeliveryWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * 勿扰顺延后到点再送达一次（任务 7.2）。
         * 用**独立** work name，避免 REPLACE 掉正在运行的 [WORK_NAME] 实例。
         */
        fun enqueueDelayed(context: Context, delayMs: Long) {
            if (delayMs <= 0L) return
            val request = OneTimeWorkRequestBuilder<ProactiveDeliveryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_DEFERRED, ExistingWorkPolicy.REPLACE, request)
        }
    }
}