package com.companion.chat.domain.proactive

/**
 * 主动消息链路的**临时调试开关**。
 *
 * 只在真机联调「2 小时周期到底会不会发」时打开，**平时必须保持 false**。
 *
 * 为什么不用 `PeriodicWorkRequest(1, MINUTES)`：WorkManager 的周期任务有硬下限
 * 15 分钟（小于会被静默夹到 15min），做不到 1 分钟。所以调试模式下改用
 * 「一次性任务每跑完一轮就自排下一轮」的链式调度（[ProactiveCheckWorker] 末尾处理），
 * 间隔由 [INTERVAL_SECONDS] 控制，可以做到 1 分钟。
 *
 * [BYPASS_GATE] 打开时闸门直接放行，否则 1 分钟一轮也会被「冷却 1h + 每日上限」全部拦掉，
 * 测试期间看不到任何消息。**这会真的花钱**：1 分钟一条 = 1440 条/天。
 *
 * 2026-11-20 真机联调已完成（确认消息能正常发出），现已全部关闭。
 */
object ProactiveDebugFlags {

    /** 是否启用快速自循环检查。 */
    const val ENABLED: Boolean = false

    /** 自循环间隔（秒）。仅在 [ENABLED] 为 true 时生效。 */
    const val INTERVAL_SECONDS: Long = 60L

    /** 是否绕过闸门（冷却 / 每日上限 / 回复率）。 */
    const val BYPASS_GATE: Boolean = false
}