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
import com.companion.chat.domain.work.TimeChangeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 系统时间被手动改动 / 时区变更后的排程自愈（1.9.4.2）。
 *
 * **背景**：WorkManager 的周期任务与 AlarmManager 的整点闹钟都按「墙上时间」记 next/trigger。
 * 用户把系统时间跳到未来再调回来（真机事故：调到 2027-02-14 又改回 2026-11-20），
 * 两者都会被算到遥远的未来 → 主动消息、环境检查静默停摆。
 *
 * 旧的自愈（[CompanionApp.ensureWorkSchedulesHealthy]）只在 App **冷启动**（onCreate）才跑；
 * 如果改时间时进程还活着、又没彻底重开 App，就永远不恢复。这里监听系统时间变更广播，
 * **即时**触发重排，覆盖那段漏网。
 *
 * 实际重排全在 [CompanionApp.healSchedulesAfterTimeChange]，且全程 runCatching：
 * 失败最多是排程又错一次，绝不让接收器抛异常（否则会记成 ANR/崩溃）。
 *
 * 持锁契约与 [BootReceiver] 一致：goAsync 后同步拿锁 → 协程里 runHolding 只负责释放 →
 * finally 必 release + finish → launch 失败外层兜底释放。极端情况由系统 10s/30s 超时回收。
 */
class SystemTimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!TimeChangeSignals.isTimeChangeAction(action)) {
            Log.w(TAG, "忽略非时间变更广播：$action")
            return
        }
        val app = context.applicationContext as? CompanionApp
        if (app == null) {
            return
        }

        val pending = goAsync()
        // 同步拿锁（挂起前）：冷启动打开 Room、读全表、重排多个闹钟都可能超过接收器窗口
        val wakeLock: WakeLockHandle = PowerWakeLock(context, WakeLockPolicy.TAG_BOOT)
        wakeLock.acquire(WakeLockPolicy.BOOT_TIMEOUT_MS)

        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    WakeLockScope.runHolding(
                        handle = wakeLock,
                        log = { Log.d(TAG, "wake lock $it") }
                    ) {
                        runCatching { app.healSchedulesAfterTimeChange() }
                            .onFailure { Log.w(TAG, "时间变更重排失败（忽略，不抛）", it) }
                        Log.w(TAG, "系统时间变更已重排排程：$action")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "时间变更重排协程异常", e)
                } finally {
                    wakeLock.release()
                    pending.finish()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动协程失败，立即释放锁", e)
            wakeLock.release()
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "SystemTimeChangeReceiver"
    }
}
