package com.companion.chat.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.power.WakeLockScope
import com.companion.chat.data.proactive.ProactiveCheckOutcome
import com.companion.chat.domain.proactive.ProactiveDebugFlags

/**
 * 主动消息定时检查 Worker（任务 3.2）。
 *
 * 生产：由 CompanionApp 以「每 2h 周期 + 需要网络」调度（WorkManager 自动处理息屏/Doze
 * 延迟与无网等待，恢复网络后再执行）。真正逻辑在 [com.companion.chat.data.proactive.ProactiveCheckRunner]：
 * 过闸门 → 召回记忆钩子 → 投待发队列。
 *
 * **调试**（[ProactiveDebugFlags.ENABLED]）：周期任务的 15 分钟下限做不到 1 分钟，
 * 所以此模式下由 [CompanionApp.scheduleNextFastCheck] 用一次性任务链式自排队，
 * 跑完一轮立刻排下一轮。
 *
 * **任务 6.1**：被唤醒时先拿 `PARTIAL_WAKE_LOCK`（tag [WakeLockPolicy.TAG_CHECK]），
 * 检查 + 投递结束（或异常/取消）**必释放**；5 分钟系统级超时兜底。
 */
class ProactiveCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CompanionApp ?: return Result.success()
        // W 级：真机上 D 级日志常被 ROM 丢弃，这两行是「周期到底跑没跑」的唯一证据
        Log.w(TAG, "周期检查开始 attempt=$runAttemptCount")
        // 心跳（2026-11-21）：记下「真的跑过」的时刻。每小时的 ENV 整点检查会读它，
        // 若超过 3 小时没跑（WorkManager 被冻结/被系统吞），就直接补跑一轮。
        runCatching {
            app.database.appStateDao().put(
                com.companion.chat.data.local.entity.AppStateEntity(
                    com.companion.chat.data.local.entity.AppStateEntity.KEY_PROACTIVE_LAST_RUN,
                    System.currentTimeMillis().toString()
                )
            )
        }
        // V2.1.5 兜底：每 2h 同步一次环境整点闹钟。正常路径由 onCreate +
        // ConfigRepository 保存回调覆盖；这里防「首次排钟失败 / 标记与实际闹钟不一致」这类
        // 漏网情况——最坏情况下 2 小时内自愈，而不是永久停摆。
        runCatching { app.syncEnvCheckSchedule() }
            .onFailure { Log.w(TAG, "环境排钟兜底同步失败", it) }
        val wakeLock = PowerWakeLock(applicationContext, WakeLockPolicy.TAG_CHECK)
        return try {
            val result = WakeLockScope.run(
                handle = wakeLock,
                timeoutMs = WakeLockPolicy.TIMEOUT_MS,
                log = { Log.w(TAG, "wake lock $it") }
            ) {
                app.proactiveCheckRunner.runOnce()
            }
            Log.w(TAG, "周期检查结束 outcome=${result.outcome} pendingId=${result.pendingId}")
            // 投递成功 → 立刻触发一次生成与送达（3.5）
            if (result.outcome == ProactiveCheckOutcome.ENQUEUED) {
                ProactiveDeliveryWorker.enqueue(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            // 重试上限后不标记永久失败，改为 success 让 WorkManager 下个周期继续（审查改进③）
            if (runAttemptCount + 1 >= MAX_RETRIES) {
                Log.w(TAG, "重试 $MAX_RETRIES 次仍失败，等待下个周期", e)
                Result.success()
            } else {
                Log.w(TAG, "周期检查异常，将重试", e)
                Result.retry()
            }
        } finally {
            // 调试链：无论成功失败都排下一轮，避免测试中途断链
            if (ProactiveDebugFlags.ENABLED) {
                Log.w(TAG, "调试链：${ProactiveDebugFlags.INTERVAL_SECONDS}s 后再跑一轮")
                CompanionApp.scheduleNextFastCheck(applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "ProactiveCheckWorker"
        private const val MAX_RETRIES = 5

        /**
         * 排程契约版本。**改 [PERIOD_HOURS] 或任何约束（网络/电量）时必须 +1。**
         *
         * 原因：`enqueueUniquePeriodicWork(..., KEEP, ...)` 在「同名 + 任务已存在」时**直接忽略新请求**，
         * 不看 interval 是否变了。所以只改 [PERIOD_HOURS] 而名字不变 = 老任务继续按旧周期跑，
         * 新周期永远不生效（改周期等于没改）。名字带上版本号，bump 后就是新 unique work。
         */
        const val SCHEDULE_SCHEMA = 2

        const val PERIOD_HOURS = 2L

        /** 当前排程的 unique work 名（含版本 + 周期，改任一项都要 bump [SCHEDULE_SCHEMA]）。 */
        const val WORK_NAME = "proactive_check_v${SCHEDULE_SCHEMA}_${PERIOD_HOURS}h"

        /**
         * 历史名字黑名单：启动时逐个取消，避免旧周期任务和新任务**同时**跑（等于周期减半）。
         * 每次 bump [SCHEDULE_SCHEMA] 或改 [PERIOD_HOURS]，把上一个名字追加到这里。
         */
        val LEGACY_WORK_NAMES = listOf("proactive_check_2h", "proactive_check_v1_${PERIOD_HOURS}h")

        /** 调试链的 unique work 名（与生产周期任务名分开，互不覆盖）。 */
        const val FAST_WORK_NAME = "proactive_check_fast"
    }
}