package com.companion.chat.data.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 唤醒锁抽象（任务 4.2 / 6.1）。接口化是为了让「持锁编排」可用假锁做 JVM 单测。
 */
interface WakeLockHandle {
    fun acquire(timeoutMs: Long)
    fun release()
    fun isHeld(): Boolean
}

/**
 * Android 部分唤醒锁实现（PARTIAL_WAKE_LOCK）。任务 4.2 / 6.1
 *
 * - tag 可配置（不同调用方在 `dumpsys power` 里可区分）；
 * - `setReferenceCounted(false)` — acquire 多次不需要 release 多次；
 * - **系统级超时兜底**：即使异常/进程崩溃未 release，到点系统自动回收；
 * - 所有系统异常吞掉并记日志（锁失败不该让业务挂掉，最差只是可能被 Doze 拖慢）。
 */
class PowerWakeLock(
    context: Context,
    private val tag: String = WakeLockPolicy.TAG_TTS,
    private val log: (String) -> Unit = { Log.d(TAG_LOG, it) }
) : WakeLockHandle {

    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    override fun acquire(timeoutMs: Long) {
        release() // 先释放旧的，避免叠加引用
        try {
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
            wakeLock = wl
            log("acquire tag=$tag timeout=${timeoutMs}ms")
        } catch (e: Exception) {
            wakeLock = null
            log("acquire 失败 tag=$tag: ${e.message}")
        }
    }

    @Synchronized
    override fun release() {
        val wl = wakeLock ?: return
        wakeLock = null
        try {
            if (wl.isHeld) {
                wl.release()
                log("release tag=$tag")
            }
        } catch (e: Exception) {
            log("release 失败 tag=$tag: ${e.message}")
        }
    }

    override fun isHeld(): Boolean = wakeLock?.isHeld == true

    private companion object {
        const val TAG_LOG = "WakeLock"
    }
}