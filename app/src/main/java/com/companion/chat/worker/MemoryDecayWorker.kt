package com.companion.chat.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.companion.chat.CompanionApp

/**
 * 短期记忆每日淡忘衰减 Worker（任务 2.5）。
 * 由 CompanionApp 以「每 24h 周期 + 每次启动补跑一次」调度；内部按天数差幂等，重复触发安全。
 */
class MemoryDecayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CompanionApp ?: return Result.success()
        // 失败重试上限：避免持续失败时无限重试耗电
        if (runAttemptCount >= MAX_RETRIES) return Result.failure()
        return try {
            app.memoryDecayService.runOnce()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** 最多尝试次数（首次 + 重试）。 */
        private const val MAX_RETRIES = 3
    }
}
