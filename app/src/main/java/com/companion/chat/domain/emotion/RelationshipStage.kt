package com.companion.chat.domain.emotion

/**
 * 关系阶段（P3，2026-11-21；门槛 1.9.3.2 收紧）。
 *
 * 只是**粗粒度**的三档，目的只有一个：决定"能不能撒娇、想你积累多快、说话多亲近"。
 * 不追求精确，宁可晚一点升级，也不要一上来就腻。
 *
 * 判定（纯函数，输入是**全库统计**，与时间无关，便于测试）：
 * ```
 * 亲近 close    : 消息 >= 200 且 认识 >= 5 天
 * 熟悉 familiar : 消息 >= 60  或 (认识 >= 2 天 且 消息 >= 30)
 * 生疏 stranger : 其它
 * ```
 * 1.9.3.2 前：亲近 200 且 3 天、熟悉 30 条或第 2 天 —— 用户反馈太容易升级，收紧。
 */
object RelationshipStage {

    const val STRANGER = "stranger"
    const val FAMILIAR = "familiar"
    const val CLOSE = "close"

    const val CLOSE_MESSAGES = 200
    const val CLOSE_DAYS = 5

    /** 熟悉：消息数单独达标的门槛（不看认识天数）。 */
    const val FAMILIAR_MESSAGES = 60
    /** 熟悉：配合天数达标时的天数门槛。 */
    const val FAMILIAR_DAYS = 2
    /** 熟悉：配合天数达标时的消息数门槛。 */
    const val FAMILIAR_DAYS_MESSAGES = 30

    /** 全库统计：[messageCount] 总消息数，[daysKnown] 认识第几天（1 = 当天）。 */
    data class Stats(val messageCount: Int, val daysKnown: Int)

    fun of(stats: Stats): String = when {
        stats.messageCount >= CLOSE_MESSAGES && stats.daysKnown >= CLOSE_DAYS -> CLOSE
        stats.messageCount >= FAMILIAR_MESSAGES ||
            (stats.daysKnown >= FAMILIAR_DAYS && stats.messageCount >= FAMILIAR_DAYS_MESSAGES) -> FAMILIAR
        else -> STRANGER
    }

    fun label(stage: String): String = when (stage) {
        CLOSE -> "亲近"
        FAMILIAR -> "熟悉"
        else -> "生疏"
    }

    /** 委屈时能不能撒娇 —— 只有亲近阶段允许（P3 的核心开关）。 */
    fun allowsAffection(stage: String): Boolean = stage == CLOSE

    /** 想你开始积累的门槛（小时）：越亲近越早开始想。 */
    fun missingFloorHours(stage: String): Double = when (stage) {
        CLOSE -> 1.0
        FAMILIAR -> 3.0
        else -> 6.0
    }

    /** 写进主对话**稳定前缀**的关系定位（变化极慢，几乎不影响前缀缓存）。 */
    fun promptHint(stage: String): String = when (stage) {
        CLOSE -> "你们已经很亲近了，可以自然地说想他、撒点娇，但别腻、别黏。"
        FAMILIAR -> "你们已经比较熟了，语气可以随意一些，可以开点小玩笑。"
        else -> "你们认识还不久，保持礼貌和分寸，别用过于亲昵的称呼。"
    }

    // ── 1.9.4.1：关系阶段影响主动消息节奏（间隔从"上次说话"算起，随机）──

    /**
     * 该关系档主动消息的间隔区间（单位：小时）。
     * 生疏 3~4h、熟悉 2~4h、亲近 2~3h —— 越亲近找得越勤。
     */
    fun proactiveIntervalHoursRange(stage: String): IntRange = when (stage) {
        CLOSE -> 2..3
        FAMILIAR -> 2..4
        else -> 3..4
    }

    /**
     * 本轮"距上次说话"需要等待的最小间隔（毫秒）。
     *
     * 用 [seedMillis]（= 上次用户说话时刻）做随机种子：**同一段"没说"期间，等多久是固定的**
     * （不会每 2h 检查一次就重掷导致忽长忽短）；用户一旦再说话，seed 变、间隔重新掷。
     * 纯函数、确定性，可 JVM 单测。
     */
    fun proactiveGapMillis(stage: String, seedMillis: Long): Long {
        val r = proactiveIntervalHoursRange(stage)
        val hours = kotlin.random.Random(seedMillis).nextInt(r.first, r.last + 1)
        return hours * 3_600_000L
    }
}