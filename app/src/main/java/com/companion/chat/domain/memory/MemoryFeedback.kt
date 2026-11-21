package com.companion.chat.domain.memory

/**
 * 记忆打分三态（回复 / 忽略 / 别提这个）纯逻辑。任务 2.8（可 JVM 单测）
 *
 * 规则（§任务 2.8）：
 *  - 「回复」：对应记忆 `weight += 0.2`（上限保护，避免无限膨胀）。
 *  - 「忽略」：不改任何记忆，仅上层不回复（此处无逻辑，仅作语义占位）。
 *  - 「别提这个」：第 1 次 `weight -= 0.5` + 打「冷却」标记（[COOLING_DAYS] 天后自动恢复）；
 *    第 2 次起打「拉黑」标记（永久，不主动召回但仍留库）。
 *
 * 召回侧：冷却中（`coolingUntil > now`）或已拉黑（`blacklisted=true`）的记忆一律跳过
 * （[isSuppressed]）。用户主动问仍走正常对话上下文，不受此限制。
 */
object MemoryFeedback {

    /** 「回复」权重增量。 */
    const val REPLY_WEIGHT_DELTA: Double = 0.2

    /** 「别提这个」权重减量。 */
    const val DISMISS_WEIGHT_DELTA: Double = 0.5

    /** 冷却天数（第 1 次「别提这个」后）。 */
    const val COOLING_DAYS: Int = 30

    /** 权重下限（不让它变负数，无意义）。 */
    const val WEIGHT_MIN: Double = 0.0

    /**
     * 权重上限（「回复」加分封顶）。提高到 10.0 让极重要记忆（如「对花生过敏」）不被久未提及加分/淡忘分压制：
     * 1.0 + 0.2×45 次才到顶，实测足够；如需再高只改此常量。
     */
    const val WEIGHT_MAX: Double = 10.0

    private const val MILLIS_PER_DAY: Long = 86_400_000L

    /** 「回复」后的新权重：`weight + 0.2`，夹在 [WEIGHT_MIN, WEIGHT_MAX]。 */
    fun afterReply(weight: Double): Double =
        (weight + REPLY_WEIGHT_DELTA).coerceIn(WEIGHT_MIN, WEIGHT_MAX)

    /** 「别提这个」计算结果（一次性回填到实体）。 */
    data class DismissResult(
        val weight: Double,
        val dismissCount: Int,
        val coolingUntil: Long?,
        val blacklisted: Boolean
    )

    /**
     * 「别提这个」计算：第 1 次减权 + 冷却 [COOLING_DAYS] 天；第 2 次起永久拉黑。
     *
     * @param currentWeight 当前权重
     * @param currentDismissCount 之前已点次数
     * @param currentCoolingUntil 当前冷却截止（第 2 次时原样保留，反正已拉黑）
     * @param nowMillis 当前时间（epoch millis）
     */
    fun afterDismiss(
        currentWeight: Double,
        currentDismissCount: Int,
        currentCoolingUntil: Long?,
        nowMillis: Long
    ): DismissResult {
        // 拉黑后（已点 ≥2 次）不再递增计数，避免 dismissCount 被误操作累积成 3、4、5…
        val newCount = if (currentDismissCount >= 2) currentDismissCount else currentDismissCount + 1
        val newWeight = (currentWeight - DISMISS_WEIGHT_DELTA).coerceAtLeast(WEIGHT_MIN)
        return if (newCount >= 2) {
            // 第 2 次：永久拉黑
            DismissResult(newWeight, newCount, currentCoolingUntil, blacklisted = true)
        } else {
            // 第 1 次：冷却 30 天
            val until = nowMillis + COOLING_DAYS * MILLIS_PER_DAY
            DismissResult(newWeight, newCount, until, blacklisted = false)
        }
    }

    /** 是否被抑制（冷却中或已拉黑）→ 召回要跳过；用户主动提问不受影响。 */
    fun isSuppressed(blacklisted: Boolean, coolingUntil: Long?, nowMillis: Long): Boolean =
        blacklisted || (coolingUntil != null && coolingUntil > nowMillis)

    /** 冷却是否已过期（到期即恢复召回）。 */
    fun isCoolingActive(coolingUntil: Long?, nowMillis: Long): Boolean =
        coolingUntil != null && coolingUntil > nowMillis
}
