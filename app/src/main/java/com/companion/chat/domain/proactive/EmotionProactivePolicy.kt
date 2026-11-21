package com.companion.chat.domain.proactive

import com.companion.chat.domain.emotion.Emotion

/**
 * 情绪 → 主动消息**每日上限**的微调（P2，2026-11-21）。
 *
 * ```
 * 兴奋 / 想你 ≥0.6 → +1 条
 * 低落 / 烦躁 ≥0.6 且没有任何正面情绪 ≥0.6 → -1 条（下限 0）
 * 其它 → 0
 * ```
 *
 * 为什么用「+1/-1」而不是按强度连续调：每日上限本身就是个粗粒度参数（0~10 整数），
 * 再细分没有可感知差别，只会让"今天到底能发几条"变得不可预测。
 *
 * ⚠️ 这里**不碰勿扰、冷却、回复率**，只动上限那一个数 —— 情绪不该能绕过勿扰。
 *
 * ### 为什么有个 @Volatile 缓存
 * 闸门（`DefaultProactiveGate`）的 `configProvider` 是**非 suspend** 的 lambda，
 * 而读情绪状态要查 `app_state`（suspend）。所以由 [com.companion.chat.data.mood.MoodService]
 * 在每次情绪落库后调用 [updateCache] 把结果缓存成整数，闸门直接读 [currentBonus]。
 * 缓存最多滞后一轮对话，对"每天多发/少发一条"这种粒度完全够用。
 */
object EmotionProactivePolicy {

    const val THRESHOLD = 0.6

    @Volatile
    private var cachedBonus: Int = 0

    /** 纯函数：由情绪 map 算出上限增量。 */
    fun limitBonus(emotions: Map<String, Double>): Int {
        fun level(k: String) = emotions[k] ?: 0.0
        val positiveHigh = maxOf(
            level(Emotion.HAPPY), level(Emotion.EXCITED), level(Emotion.PROUD), level(Emotion.MISSING)
        ) >= THRESHOLD
        val wantsMore = level(Emotion.EXCITED) >= THRESHOLD || level(Emotion.MISSING) >= THRESHOLD
        val wantsLess = !positiveHigh &&
            (level(Emotion.LOW) >= THRESHOLD || level(Emotion.ANNOYED) >= THRESHOLD)

        return when {
            wantsMore -> 1
            wantsLess -> -1
            else -> 0
        }
    }

    /** 情绪落库后刷新缓存（MoodService 调用）。 */
    fun updateCache(emotions: Map<String, Double>) {
        cachedBonus = limitBonus(emotions)
    }

    /** 闸门读这里（非 suspend）。 */
    fun currentBonus(): Int = cachedBonus

    /** 测试用：重置缓存，避免用例之间互相污染。 */
    fun resetCacheForTest() {
        cachedBonus = 0
    }
}