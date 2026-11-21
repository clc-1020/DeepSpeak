package com.companion.chat.data.power

/**
 * 持锁执行编排（任务 6.1）：**唯一**推荐用法，保证「拿到锁 → 跑任务 → 必释放」成对。
 *
 * 对应计划 6.1 的三条验收：
 *  1. 被唤醒（Worker / Receiver 启动）就先拿锁，保证 Doze 下也能跑完；
 *  2. 任务结束（生成完成 / TTS 播完 / 抛异常 / 协程取消）一律释放；
 *  3. 超时兜底：`acquire(timeoutMs)` 自带系统级超时（默认 [WakeLockPolicy.TIMEOUT_MS] = 5 分钟），
 *     即使进程被强杀也不会漏锁。
 *
 * 纯逻辑（只依赖 [WakeLockHandle] 接口），可用假锁做 JVM 单测。
 */
object WakeLockScope {

    /** acquire → 跑 block → **finally 必 release**。 */
    suspend fun <T> run(
        handle: WakeLockHandle,
        timeoutMs: Long = WakeLockPolicy.TIMEOUT_MS,
        log: (String) -> Unit = {},
        block: suspend () -> T
    ): T {
        var acquired = false
        try {
            handle.acquire(timeoutMs)
            acquired = true
            return block()
        } finally {
            if (acquired) {
                handle.release()
                log("released after task")
            }
        }
    }

    /**
     * 已经**提前同步拿到锁**时用（BroadcastReceiver：`goAsync()` 之后进程随时可能被冻结，
     * 必须在挂起前先 acquire）。这里只负责「保证释放」，不再重复 acquire，避免多余的锁抖动。
     */
    suspend fun <T> runHolding(
        handle: WakeLockHandle,
        log: (String) -> Unit = {},
        block: suspend () -> T
    ): T = try {
        block()
    } finally {
        handle.release()
        log("released (pre-acquired)")
    }
}