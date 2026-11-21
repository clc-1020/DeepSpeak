package com.companion.chat.domain.emotion

/**
 * 情绪事件的长期记忆（P3，2026-11-21）—— 让"她记得那天为什么开心"。
 *
 * 只允许在 **亲近** 阶段写，且只写**有温度**的四种情绪：
 * 开心 / 得意 / 想你 / 委屈。
 * 刻意**不写**烦躁、低落、心虚 —— 那三种最容易误判、也最容易变成"翻旧账"的证据。
 *
 * 为什么必须限量（[MAX_ENTRIES] = 3）：长期记忆是**每轮全量注入**的，
 * 情绪记忆攒多了会把上下文挤成"回忆录"，反而让她显得刻意。
 */
object EmotionMemory {

    const val MAX_ENTRIES = 3
    const val MIN_INTENSITY = 0.6

    /** 可写入长期记忆的情绪白名单。 */
    val WRITABLE: Set<String> = setOf(
        Emotion.HAPPY, Emotion.PROUD, Emotion.MISSING, Emotion.HURT
    )

    /**
     * 挑出该记的那一项：必须亲近阶段、trigger 非空、且强度 ≥ [MIN_INTENSITY]。
     * 多项都够格时取最强的那一项（一条记忆只记一件事）。
     */
    fun pick(emotions: Map<String, Double>, stage: String, trigger: String): Pair<String, Double>? {
        if (!RelationshipStage.allowsAffection(stage)) return null
        if (trigger.isBlank()) return null
        return emotions.entries
            .filter { it.key in WRITABLE && it.value >= MIN_INTENSITY }
            .maxByOrNull { it.value }
            ?.toPair()
    }

    /**
     * 记忆正文：`2026-11-21 你连夸我厉害可爱，我当时很开心`。
     *
     * 用**第一人称**而不是"用户balabala" —— 这条是她的回忆，不是档案。
     */
    fun format(emotion: String, trigger: String, dateLabel: String): String {
        val trig = trigger.trim().trimEnd('。', '，', '、', '.', ',')
        return "$dateLabel $trig，我当时${EmotionPhrase.label(emotion)}。"
    }
}