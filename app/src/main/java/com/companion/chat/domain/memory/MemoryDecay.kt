package com.companion.chat.domain.memory

/**
 * 淡忘衰减计算（纯函数，可 JVM 单测）。任务 2.5
 *
 * 短期记忆每天 `fadeScore += 因子`（默认 0.1）。为容忍 App 未运行的间隔，
 * 按「距上次衰减的天数 × 因子」补齐（如 3 天没开 → +0.3），批处理由 [com.companion.chat.data.memory.MemoryDecayService] 执行。
 */
object MemoryDecay {

    /** 每日淡忘增量。 */
    const val DAILY_FACTOR: Double = 0.1

    /** 给定天数应增加的淡忘分（天数 ≤0 返回 0）。 */
    fun increment(daysElapsed: Int, factor: Double = DAILY_FACTOR): Double =
        if (daysElapsed <= 0) 0.0 else daysElapsed * factor

    /** 计算新的 fadeScore。 */
    fun nextFadeScore(currentFadeScore: Double, daysElapsed: Int, factor: Double = DAILY_FACTOR): Double =
        currentFadeScore + increment(daysElapsed, factor)

    /** 两个 epochDay 之间的天数（负数按 0）。 */
    fun daysBetween(fromEpochDay: Long, toEpochDay: Long): Int =
        (toEpochDay - fromEpochDay).coerceAtLeast(0L).toInt()
}
