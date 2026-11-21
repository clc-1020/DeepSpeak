package com.companion.chat.domain.work

/**
 * 周期任务排程健康判定（2026-11-21 纯函数）。
 *
 * 为什么需要它：WorkManager 的 `PeriodicWorkRequest` 用 **`System.currentTimeMillis()`**
 * 记录「上次入队时刻」，下次运行 = 上次 + 周期。而 `ExistingPeriodicWorkPolicy.KEEP`
 * 一旦发现同名任务存在就**不重置**这个时刻。
 *
 * 真机事故（本函数就是它的解药）：
 *  1. 2026-11-20 22:47，用户为测节日把系统时间改成 **2027-02-14 22:47**；
 *  2. 那之后 App 启动，KEEP 排下 2h 周期任务 → `last_enqueue_time` 冻结在 2027-02-14 22:47；
 *  3. 22:50 用户把时间改回 2026-11-20；
 *  4. WorkManager 认为下次运行 = 2027-02-15 00:47 → **要等 3 个月**，期间一条主动消息都不发。
 *
 * 判定规则（宁多重排一次，也不要静默停摆）：
 *  - 从没记录过（升级/首次安装）→ 需要重排；
 *  - **时钟回拨**（now < 上次排程时刻）→ 需要重排（本次事故的形态）；
 *  - 距上次排程超过 [STALE_PERIODS] 个周期 → 需要重排（WorkManager 被系统吞掉/长期未跑）。
 *
 * 注意阈值别设太小：频繁重排会把周期不断重置成「现在 + 周期」，反而永远到不了点。
 */
object WorkScheduleHealth {

    /** 超过这么多个周期没重排，就认为排程可能已失效（2h 周期 → 6h）。 */
    const val STALE_PERIODS = 3

    /**
     * @param lastScheduledAt 上次成功排程的时刻（epoch millis，存 `app_state`；null = 从未记录）
     * @param nowMillis 当前时刻
     * @param periodMs 任务周期（毫秒）
     */
    fun needsReschedule(lastScheduledAt: Long?, nowMillis: Long, periodMs: Long): Boolean {
        if (lastScheduledAt == null || lastScheduledAt <= 0L) return true
        if (nowMillis < lastScheduledAt) return true // 时钟回拨：周期任务的 next 已被推到未来
        if (periodMs <= 0L) return false
        return nowMillis - lastScheduledAt > STALE_PERIODS * periodMs
    }
}