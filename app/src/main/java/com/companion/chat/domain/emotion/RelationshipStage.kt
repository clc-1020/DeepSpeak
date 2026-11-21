package com.companion.chat.domain.emotion

/**
 * 关系阶段（P3，2026-11-21）。
 *
 * 只是**粗粒度**的三档，目的只有一个：决定"能不能撒娇、想你积累多快、说话多亲近"。
 * 不追求精确，宁可晚一点升级，也不要一上来就腻。
 *
 * 判定（纯函数，输入是**全库统计**，与时间无关，便于测试）：
 * ```
 * 亲近 close    : 消息 >= 200 且 认识 >= 3 天
 * 熟悉 familiar : 消息 >= 30  或 认识 >= 1 天
 * 生疏 stranger : 其它
 * ```
 */
object RelationshipStage {

    const val STRANGER = "stranger"
    const val FAMILIAR = "familiar"
    const val CLOSE = "close"

    const val CLOSE_MESSAGES = 200
    const val CLOSE_DAYS = 3
    const val FAMILIAR_MESSAGES = 30

    /** 全库统计：[messageCount] 总消息数，[daysKnown] 认识第几天（1 = 当天）。 */
    data class Stats(val messageCount: Int, val daysKnown: Int)

    fun of(stats: Stats): String = when {
        stats.messageCount >= CLOSE_MESSAGES && stats.daysKnown >= CLOSE_DAYS -> CLOSE
        stats.messageCount >= FAMILIAR_MESSAGES || stats.daysKnown >= CLOSE_DAYS -> FAMILIAR
        stats.daysKnown >= 2 -> FAMILIAR
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
}