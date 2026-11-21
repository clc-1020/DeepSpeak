package com.companion.chat.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockHandle
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.power.WakeLockScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开机 / 应用升级后恢复闹钟（B9）。
 *
 * **为什么必须有这一层**：AlarmManager 里的闹钟在设备重启后会被系统**全部清空**，但本 App
 * 的持久化标记还在——
 * - 环境检查：`app_state[env_check_scheduled]` 仍是 "1"，而 `CompanionApp.syncEnvCheckSchedule()`
 *   见到标记就 `return`（「已在跑」）→ 重启后是**假阳性**，环境检查永久停摆，只能清数据恢复；
 * - 用户提醒：`reminders` 表里 `state='pending'` 的行还在，对应的 PendingIntent 已消失，
 *   到点永不触发。
 *
 * 所以开机（以及应用升级，升级同样清闹钟）后必须把这两类闹钟**重新排一遍**。
 *
 * **只收 BOOT_COMPLETED，刻意不收 LOCKED_BOOT_COMPLETED**：本 App 用
 * `EncryptedSharedPreferences(MasterKey)` + Room，用户在首次解锁前拿不到密钥，
 * 提前跑只会崩或降级成空数据。`ACTION_MY_PACKAGE_REPLACED` 只在解锁态发生，安全。
 *
 * **持锁契约与 [ReminderAlarmReceiver] 完全一致（5 条）**：
 *  1. `goAsync()` 之后**同步 acquire**（协程还没跑起来时进程随时可能被冻结）；
 *  2. 进入协程后一律走 [WakeLockScope.runHolding]（**不再重复 acquire**，只保证释放）；
 *  3. 无论正常/异常/取消，`finally` 里都 `release()` + `pending.finish()`，
 *     且 [PowerWakeLock.release] 本身幂等（未持有则 no-op）；
 *  4. 万一 `launch` 都失败（如进程收尾），外层 `catch` 兜底释放并 finish —— 锁不会漏；
 *  5. 极端情况（进程被强杀）由系统 10s 超时回收。
 *
 * 恢复逻辑本身全在 [CompanionApp.restoreAlarmsAfterBoot] 里，且全程 runCatching：
 * **恢复失败最多是闹钟又丢一次，绝不能让接收器抛异常**（那会记成 ANR/崩溃）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.w(TAG, "忽略非开机广播：$action")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as? CompanionApp
        if (app == null) {
            pending.finish()
            return
        }

        // ① 同步拿锁（在挂起前），后续 finally 一定释放
        //    用 BOOT_TIMEOUT_MS（30s）而不是 Receiver 的 10s：本接收器要冷启动打开 Room、
        //    读全表、连排 N 个闹钟，10s 档在行数多/开机 IO 抢占时会被系统回收
        val wakeLock: WakeLockHandle = PowerWakeLock(context, WakeLockPolicy.TAG_BOOT)
        wakeLock.acquire(WakeLockPolicy.BOOT_TIMEOUT_MS)

        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // ② 已提前持锁 → runHolding 只负责 finally release
                    WakeLockScope.runHolding(
                        handle = wakeLock,
                        log = { Log.d(TAG, "wake lock $it") }
                    ) {
                        val report = runCatching { app.restoreAlarmsAfterBoot() }
                            .onFailure { Log.w(TAG, "重启恢复失败（忽略，不抛）", it) }
                            .getOrNull()
                        if (report == null) {
                            Log.w(TAG, "重启恢复未完成，闹钟可能缺失，下次启动/巡检会再补")
                        } else {
                            Log.d(
                                TAG,
                                "重启恢复完成｜提醒 ${report.remindersRestored}/${report.remindersPending} 条" +
                                    "（关机期间错过 ${report.remindersOverdue} 条已改立即补发）｜" +
                                    "过期追问补 TIMEOUT 钟 ${report.followupTimeouts} 条｜" +
                                    "环境检查 ${report.envScheduledAt?.let { "下一次 @" + formatTime(it) } ?: "未排（环境感知关闭或失败）"}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "重启恢复协程异常", e)
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

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

    companion object {
        private const val TAG = "BootReceiver"
    }
}