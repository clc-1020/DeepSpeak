package com.companion.chat.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.companion.chat.CompanionApp
import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockHandle
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.power.WakeLockScope
import com.companion.chat.data.proactive.ProactiveCheckOutcome
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.work.WorkScheduleHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟统一入口（任务 3.3 / 6.1 / 3.6）。类名**不改**——改名字要动 manifest 和已排的
 * PendingIntent，会让旧闹钟失联。
 *
 * 到点 → 拿短时 WakeLock → 按 [AlarmKind] 分流 → 释放 WakeLock → finish。
 *
 * | kind | 行为 |
 * |---|---|
 * | [AlarmKind.INSTRUCTION] | 建短期记忆 → 投 `INSTRUCTION` → 触发送达 |
 * | [AlarmKind.ENV_CHECK] | `EnvironmentHookService.recall()` → 有钩子投队列 → 无论有无排下一次整点 |
 * | [AlarmKind.FOLLOWUP] | `FollowupChainRunner.tickFollowup(id)`（V2.2.4）→ 真的投出追问才触发送达 |
 * | [AlarmKind.TIMEOUT] | `FollowupChainRunner.tickTimeout(id)`（V2.2.4）→ 只重排下一次追问，不投队列 |
 *
 * 边界说明（审查）：
 *  - **勿扰**：本接收器只负责「入队」；是否受勿扰约束在送达层统一判断（任务 7.2 已实现顺延），
 *    故此处不做勿扰过滤。
 *  - **WakeLock 时长**：这里只做一次快速 DB 入队（亚秒级），用 [WakeLockPolicy.RECEIVER_TIMEOUT_MS]（10s）；
 *    真正生成 + TTS 的耗时工作在 `ProactiveDeliveryWorker`，由它自己持 5 分钟锁。
 *
 * **任务 6.1 的持锁契约（逐条对应 Claude 审查项）**：
 *  1. `goAsync()` 之后**同步 acquire**（协程还没跑起来时进程随时可能被冻结）；
 *  2. 进入协程后一律走 [WakeLockScope.runHolding]（**不再重复 acquire**，只保证释放）；
 *  3. 无论正常/异常/取消，`finally` 里都 `release()` + `pending.finish()`，
 *     且 `PowerWakeLock.release()` 本身幂等（未持有则 no-op）；
 *  4. 万一 `launch` 都失败（如进程收尾），外层 `catch` 兜底释放并 finish —— 锁不会漏；
 *  5. 极端情况（进程被强杀）由系统 10s 超时回收。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: "提醒"
        // B9：恢复/设钟两侧都会传真实 id；环境检查传 -1（AlarmScheduler 用 -1L 而非 null 哨兵）
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        // 旧 PendingIntent（无 EXTRA_KIND）→ 按 INSTRUCTION 处理，兼容已排的闹钟
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }
            .getOrDefault(AlarmKind.INSTRUCTION)
        val pending = goAsync()
        val app = context.applicationContext as? CompanionApp
        if (app == null) {
            pending.finish()
            return
        }

        // ① 同步拿锁（在挂起前），后续 finally 一定释放
        val wakeLock: WakeLockHandle = PowerWakeLock(context, WakeLockPolicy.TAG_REMINDER)
        wakeLock.acquire(WakeLockPolicy.RECEIVER_TIMEOUT_MS)

        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // ② 已提前持锁 → runHolding 只负责 finally release
                    WakeLockScope.runHolding(
                        handle = wakeLock,
                        log = { Log.d(TAG, "wake lock $it") }
                    ) {
                        when (kind) {
                            AlarmKind.INSTRUCTION -> handleInstruction(app, context, task, reminderId)
                            AlarmKind.ENV_CHECK -> handleEnvCheck(app, context)
                            // V2.2.4：业务全在 FollowupChainRunner（纯 JVM 可测），这里只做适配。
                            // 返回 true = 真的投出了一条追问 → 才触发送达（V2.2.4 坑 #12）。
                            AlarmKind.FOLLOWUP -> runCatching { app.followupChainRunner.tickFollowup(reminderId) }
                                .onSuccess { if (it) ProactiveDeliveryWorker.enqueue(context) }
                                .onFailure { Log.w(TAG, "追问 tick 失败 id=$reminderId", it) }
                            // TIMEOUT 只重排下一次 FOLLOWUP：**不投队列、不发通知**，
                            // 所以这里刻意不碰 ProactiveDeliveryWorker。
                            AlarmKind.TIMEOUT -> runCatching { app.followupChainRunner.tickTimeout(reminderId) }
                                .onFailure { Log.w(TAG, "追问超时 tick 失败 id=$reminderId", it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "闹钟处理失败 kind=$kind", e)
                } finally {
                    // ③ 幂等释放 + 一定要 finish（否则系统认为接收器卡死）
                    wakeLock.release()
                    pending.finish()
                }
            }
        } catch (e: Exception) {
            // ④ launch 失败（进程收尾/线程池拒绝）→ 立刻释放，不漏锁
            Log.w(TAG, "启动协程失败，立即释放锁", e)
            wakeLock.release()
            pending.finish()
        }
    }

    /**
     * 指令提醒（任务 3.7 + B9）：建短期记忆 → 投 INSTRUCTION → **标 done** → 触发送达。
     *
     * **`updateState(DONE)` 必须在 `enqueue` 成功之后**：反过来的话 enqueue 抛异常会留下
     * 「已 done 但没发出去」的行 —— 永久静默丢一条提醒，比重复发更糟。
     * 标 done 是 B9 的前提：不标的话该行永远 `pending`，每次重启都会被恢复路径当成
     * 「关机期间错过」再补发一遍。
     *
     * @param reminderId 关联的 `reminders` 行 id；-1 = 没有行（环境检查/旧闹钟/落库降级），不标
     */
    private suspend fun handleInstruction(
        app: CompanionApp,
        context: Context,
        task: String,
        reminderId: Long
    ) {
        val dao = app.database.memoryShortDao()
        val reusable = if (task.length >= 2) {
            runCatching { dao.findLatestContaining(task) }.getOrNull()
        } else null
        val memoryId = reusable?.id ?: runCatching {
            dao.insert(
                MemoryShortEntity(content = task, timestamp = System.currentTimeMillis())
            )
        }.getOrNull()
        app.pendingMessageQueue.enqueue(
            type = PendingMessageType.INSTRUCTION,
            content = PendingMessageContent.Instruction(task, memoryId),
            triggerTime = System.currentTimeMillis()
        )

        // 入队成功 → 才算「这条提醒完成了」。DB 异常不能让接收器抛（会记 ANR/崩溃）。
        if (reminderId > 0L) {
            runCatching {
                app.database.reminderDao().updateState(reminderId, ReminderState.DONE)
            }.onSuccess {
                Log.d(TAG, "提醒已完成，标记 done id=$reminderId")
            }.onFailure {
                Log.w(TAG, "标记 done 失败 id=$reminderId（下次重启会重发这一条）", it)
            }
        }

        Log.d(TAG, "提醒到点，已投队列：$task")
        // 入队后立刻触发一次生成与送达（3.5）
        ProactiveDeliveryWorker.enqueue(context)
        // 2026-11-20：这条提醒刚往短期记忆写了一条，顺手做容量淘汰（攒满 50 清 20）。
        // runCatching：清理是后台维护，绝不阻断提醒送达。
        runCatching { app.memoryCapacityEnforcer.enforceOnce() }
            .onSuccess { if (it > 0) Log.d(TAG, "短期记忆淘汰 $it 条") }
            .onFailure { Log.w(TAG, "短期记忆淘汰失败", it) }
    }

    /**
     * 环境整点检查（V2.1.5）。
     *
     * **执行顺序是刻意的，不能调换**：
     * 1. **先排下一次整点**——这是「续命」。若放在后面，recall() 抛异常（走网络拿天气）、
     *    或进程在 10s WakeLock 到期被冻结，下一小时的闹钟就不会排；而
     *    `env_check_scheduled` 已是 "1"，下次启动的首排逻辑会直接跳过 →
     *    环境检查**永久停摆**，只能重装或清数据才能恢复。
     * 2. 再读**环境总开关**：关掉就取消闹钟并直接返回（设置页文案承诺「关闭后不再提醒」）。
     * 3. recall() 全程 runCatching：任何失败都不能阻断第 1 步已排好的下一次。
     * 4. 命中 → 投队列 → **触发送达**（计划书 L603「有则投队列 + 触发送达」）。
     *    不触发就只能等下轮 2h 的 ProactiveCheckWorker，熬夜 23:00 的提醒可能 01:00 才发，
     *    饭点 ±30min 的窗口直接错过。
     *
     * 「无论有无钩子都排下一次」是关键：环境检查是**周期性**闹钟，不能因为这次没钩子、
     * 天气失败或抛异常就停掉。
     */
    private suspend fun handleEnvCheck(app: CompanionApp, context: Context) {
        // 1. 先续命：无论后面发生什么都已排好下一次
        val next = EnvironmentCheckScheduler.nextWholeHour(System.currentTimeMillis())
        runCatching {
            app.alarmScheduler.schedule(
                requestCode = AlarmCodes.ENV_CHECK,
                triggerAtMillis = next,
                taskDesc = "环境检查",
                reminderId = null,
                kind = AlarmKind.ENV_CHECK
            )
            Log.d(TAG, "已排下一次环境检查 @ $next")
        }.onFailure { Log.w(TAG, "排下一次环境检查失败", it) }

        // 2. 环境总开关：关掉 → 取消闹钟，不再投递
        val environmentEnabled = runCatching {
            app.configRepository.getEnvironmentSettings().environmentEnabled
        }.getOrDefault(true)
        if (!environmentEnabled) {
            runCatching { app.alarmScheduler.cancel(AlarmCodes.ENV_CHECK) }
            Log.d(TAG, "环境感知已关闭，取消整点检查")
            return
        }

        // 3. recall：失败只记日志，不影响已排好的下一次
        //    （2026-11-21：不再「无钩子就 return」—— 后面还有主动检查兜底要跑）
        val hook = runCatching { app.environmentHookService.recall() }
            .onFailure { Log.w(TAG, "环境钩子 recall 失败", it) }
            .getOrNull()

        // 4. 投队列 + 触发送达（有钩子才投）
        if (hook != null) {
            runCatching {
                app.environmentHookService.enqueue(hook)
                Log.d(TAG, "环境钩子已投队列：${hook.stableId}")
                ProactiveDeliveryWorker.enqueue(context)
            }.onFailure { Log.w(TAG, "环境钩子投递失败", it) }
        }

        // 5. 主动检查心跳兜底（2026-11-21）
        //    WorkManager 的 2h 周期任务会被「系统时间被改」「MIUI 冻结后台」搞停摆；
        //    这个闹钟每小时必响，是唯一可靠的节拍，所以由它盯着主动检查有没有真的在跑。
        runCatching { proactiveHeartbeatFallback(app, context) }
            .onFailure { Log.w(TAG, "主动检查兜底失败", it) }
    }

    /**
     * 主动检查兜底：距上次 `ProactiveCheckWorker` **实际运行**超过 3 小时（或从没跑过）→ 补跑一轮。
     *
     * 为什么需要：2026-11-20 22:47 用户把系统时间改到 2027-02-14，WorkManager 把 2h 周期任务的
     * `last_enqueue_time` 冻结在那里；时间改回后下次运行被算到 2027-02-15 → 主动消息静默停摆。
     * WorkManager 修好后仍可能被 MIUI 省电策略延后，所以用「整点闹钟 + 心跳」做第二道保险。
     */
    private suspend fun proactiveHeartbeatFallback(app: CompanionApp, context: Context) {
        val dao = app.database.appStateDao()
        val lastRun = dao.get(AppStateEntity.KEY_PROACTIVE_LAST_RUN)?.toLongOrNull()
        val now = System.currentTimeMillis()
        // 以 1 小时为周期、3 个周期为阈值 → 超过 3 小时没跑就补
        if (!WorkScheduleHealth.needsReschedule(lastRun, now, 60L * 60 * 1000)) return

        Log.w(TAG, "主动检查已停摆（上次运行=${lastRun ?: "无"}），整点兜底补跑一轮")
        val result = app.proactiveCheckRunner.runOnce()
        Log.w(TAG, "兜底检查结果 outcome=${result.outcome} pendingId=${result.pendingId}")
        if (result.outcome == ProactiveCheckOutcome.ENQUEUED) {
            ProactiveDeliveryWorker.enqueue(context)
        }
        dao.put(AppStateEntity(AppStateEntity.KEY_PROACTIVE_LAST_RUN, now.toString()))
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"
        const val EXTRA_TASK = "extra_task"
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_KIND = "extra_kind"
    }
}