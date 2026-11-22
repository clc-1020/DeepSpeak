package com.companion.chat.domain.work

/**
 * 系统时间变更信号（1.9.4.2）。
 *
 * 为什么单独抽出来：接收器（[com.companion.chat.worker.SystemTimeChangeReceiver]）是 Android 类、
 * JVM 跑不起来；把「哪些 action 算时间变更」提成纯函数就能直接单测，也避免接收器里散落魔法字符串。
 *
 * 覆盖：手动改时间（TIME_SET）/ 换时区（TIMEZONE_CHANGED）。
 * **刻意不含 DATE_CHANGED**——它每天零点都会发，用它触发整体重排会每天重置一次排程，
 * 反而把 2h 周期越推越远（踩过的坑：周期任务被反复重置 = 永远到不了点）。
 */
object TimeChangeSignals {

    /** 手动修改系统时间。 */
    const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"

    /** 切换系统时区。 */
    const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"

    private val ACTIONS = setOf(ACTION_TIME_CHANGED, ACTION_TIMEZONE_CHANGED)

    /** 该 action 是否属于「需要重排排程」的时间变更。null / 其它一律 false。 */
    fun isTimeChangeAction(action: String?): Boolean = action != null && action in ACTIONS
}
