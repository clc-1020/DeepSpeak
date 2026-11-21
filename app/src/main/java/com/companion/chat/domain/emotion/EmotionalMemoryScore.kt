package com.companion.chat.domain.emotion

/**
 * 情绪记忆打分 / 冷却 / boost 规则（纯函数，1.9.4.0）。
 *
 * 全部函数不依赖 Android、不读库、不读当前时间——所有时间都从参数传入，可被 JVM 单测直接调用。
 */
object EmotionalMemoryScore {

    /** 单次 boost 加分。 */
    const val BOOST_STEP = 0.05

    /** boost 上限，净 boost 范围 [-BOOST_CAP, BOOST_CAP]。 */
    const val BOOST_CAP = 0.2

    /** 单次厌恶扣分，下限 -BOOST_CAP。 */
    const val DISLIKE_STEP = 0.1

    /** 打分衰减半衰期（天）。 */
    const val DECAY_HALF_LIFE_DAYS = 30.0

    /**  pivotal 记忆注入冷却：12 小时。 */
    const val PIVOTAL_COOLDOWN_MS = 12 * 60 * 60 * 1000L

    /** 普通记忆注入冷却：48 小时。 */
    const val NORMAL_COOLDOWN_MS = 48 * 60 * 60 * 1000L

    /** 厌恶记忆的冷却窗口：7 天（由调用方据此写 [canInject] 的 coolingUntil）。 */
    const val DISLIKE_COOLING_MS = 7 * 24 * 60 * 60 * 1000L

    private const val DAY_MS = 86_400_000L

    /** 严格否定词表：命中任一子串即视为「用户明确表示不想再提」。 */
    private val NEGATIVE_SUBSTRINGS = listOf(
        "别提", "不想提", "没说过", "你怎么又", "烦不烦", "别念叨", "别说了", "别再提"
    )

    /** 把值限制到 [lo, hi]；NaN 当作 0。 */
    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v.isNaN()) 0.0.coerceIn(lo, hi) else v.coerceIn(lo, hi)

    /**
     * 情绪记忆强度打分。
     *
     * `(clamp(intensity + boost, -0.2, 1.0)) * 0.5^(daysAgo / 30)`，
     * `daysAgo = max(0, now - createTime) / 86400000.0`，结果 clamp 到 [0, 1]。
     *
     * 健壮性：负 intensity 当 0；NaN 当 0；时钟回拨（now < createTime）时 daysAgo=0，不衰减。
     */
    fun score(intensity: Double, boost: Double, createTimeMillis: Long, nowMillis: Long): Double {
        val safeIntensity = if (intensity.isNaN() || intensity < 0.0) 0.0 else intensity
        val safeBoost = if (boost.isNaN()) 0.0 else boost
        val base = clamp(safeIntensity + safeBoost, -0.2, 1.0)

        // 用 Double 做差，避免 Long 极值相减溢出；差为负（回拨/溢出）时按 0 天处理。
        val delta = (nowMillis - createTimeMillis).toDouble()
        val daysAgo = maxOf(0.0, delta) / DAY_MS.toDouble()
        val factor = Math.pow(0.5, daysAgo / DECAY_HALF_LIFE_DAYS)
        val value = base * factor
        return if (value.isNaN()) 0.0 else clamp(value, 0.0, 1.0)
    }

    /** 加一次 boost：入参先 clamp 到 [-BOOST_CAP, BOOST_CAP]，再 +BOOST_STEP，封顶 BOOST_CAP。NaN 当 0。 */
    fun applyBoost(current: Double): Double {
        val c = clamp(current, -BOOST_CAP, BOOST_CAP)
        return minOf(BOOST_CAP, c + BOOST_STEP)
    }

    /** 加一次厌恶扣分：入参先 clamp 到 [-BOOST_CAP, BOOST_CAP]，再 -DISLIKE_STEP，下限 -BOOST_CAP。NaN 当 0。 */
    fun applyDislike(current: Double): Double {
        val c = clamp(current, -BOOST_CAP, BOOST_CAP)
        return maxOf(-BOOST_CAP, c - DISLIKE_STEP)
    }

    /**
     * 是否允许注入该条记忆。
     *
     * - blacklisted → false；coolingUntil > now → false；
     * - pivotal：必须「不是同一天」且「距上次注入 >= 12h」（两条都卡）；
     * - 非 pivotal：只需距上次注入 >= 48h，不看日期；
     * - lastInjectedAt <= 0（从未注入）或时钟回拨（now < lastInjectedAt）→ 时间差视为无穷大，直接通过。
     *
     * @param dislikeCount 预留给调用方：厌恶冷却已折算进 [coolingUntil]，本函数不重复判断。
     */
    fun canInject(
        pivotal: Boolean,
        lastInjectedAt: Long,
        lastInjectedDay: String?,
        today: String,
        nowMillis: Long,
        blacklisted: Boolean,
        coolingUntil: Long,
        dislikeCount: Int
    ): Boolean {
        if (blacklisted) return false
        if (coolingUntil > nowMillis) return false
        if (lastInjectedAt <= 0L || nowMillis < lastInjectedAt) return true

        val elapsed = nowMillis - lastInjectedAt
        return if (pivotal) {
            lastInjectedDay != today && elapsed >= PIVOTAL_COOLDOWN_MS
        } else {
            elapsed >= NORMAL_COOLDOWN_MS
        }
    }

    /** 是否为用户明确拒绝再提的否定文本；空串/空白 false，不做任何其它推断。 */
    fun isNegativeUserText(text: String): Boolean {
        if (text.isBlank()) return false
        return NEGATIVE_SUBSTRINGS.any { text.contains(it) }
    }

    /** 合并重复记忆文本：1 次以内只 trim；多次则去掉句尾标点并追加次数说明。 */
    fun mergeText(base: String, times: Int): String {
        if (times <= 1) return base.trim()
        return base.trim().trimEnd('。', '，', '、') + "。（你不止一次这样，共${times}次）"
    }

    /**
     * 记忆注入排序：相似度降序，相似度相同则 boost 降序；相似度 NaN 当 -1f 排最后。
     *
     * @param items Triple(id, similarity, boost)
     * @return 按注入优先级排好的 id 列表
     */
    fun rankForInjection(items: List<Triple<Long, Float, Double>>): List<Long> {
        return items.sortedWith(
            compareByDescending<Triple<Long, Float, Double>> { t ->
                val s = t.second
                if (s.isNaN()) -1f else s
            }.thenByDescending { t ->
                val b = t.third
                if (b.isNaN()) 0.0 else b
            }
        ).map { it.first }
    }
}
