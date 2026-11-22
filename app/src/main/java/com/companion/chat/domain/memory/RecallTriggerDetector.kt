package com.companion.chat.domain.memory

/**
 * 「深度回忆」触发检测（2026-11-22，纯函数、零依赖、可 JVM 单测）。
 *
 * 用户诉求：当提问里出现「你还记不记得 / 我上次 / 之前说的」这类**明确过去回忆词**时，
 * App 先专门去三张记忆表里多翻一遍（放宽窗口/阈值），再把结果递给模型，让她答得"像真记得"。
 *
 * 两条关键取舍（都是坑）：
 *  1. **只认真实"回忆过去"**：像「你还记不记得我**明天**要开会」「记不记得**答应**我什么」
 *     其实是**未来/待办**，不是回忆。这类要是也去翻日记，就会闹笑话（拿旧事硬答）。
 *     所以命中触发词后，**只要句子里还带未来/待办词，就一律不算回忆**（一律 [Signal.triggered] = false），
 *     走原来的普通路径。
 *  2. **不碰模糊指代**（"那个 / 之前那家"）：这些天天出现，误伤最多；本轮只做"明确词"。
 *     第二期若要支持，也只应在"虚词旁边还有具体名词"时才触发 —— 那是另一件事，这里刻意不做。
 *
 * 纯字符串匹配，天然好测；漏判的最坏后果只是"这轮少翻一次记忆"，绝不打断主流程。
 */
object RecallTriggerDetector {

    /** 检测结果。[matched] 命中到的过去回忆词（诊断/测试用）。 */
    data class Signal(val triggered: Boolean, val matched: List<String> = emptyList())

    /** 明确"过去回忆"词。注意**不含**裸「记得」——它太常出现在「记得明天叫我」这种提醒里。 */
    private val PAST_TRIGGERS = listOf(
        "还记得", "记不记得", "记得吗", "你记得", "还记着", "仍记得",
        "想起来", "想得起",
        "我上次", "上次", "上回", "那次",
        "之前说的", "之前提到", "之前讲过", "之前跟你说",
        "以前说", "以前提",
        "我跟你说过", "我说过", "我们聊过", "提过"
    )

    /** 未来 / 待办 / 要求提醒 —— 出现任意一个就**不算回忆**（坑1）。 */
    private val FUTURE_EXCLUSIONS = listOf(
        "明天", "后天", "下次", "下周", "下个月", "下礼拜",
        "待会", "等会", "一会儿", "一会", "马上", "稍后", "回头",
        "待办", "计划", "安排", "答应", "要约", "要交", "要做",
        "要我", "叫你", "提醒我", "记得要", "记得别", "别忘了", "别忘记",
        "以后", "将来", "未来"
    )

    /**
     * @param text 本轮用户原始输入
     * @return 命中 = 含明确过去回忆词 **且** 不含任何未来/待办词
     */
    fun detect(text: String): Signal {
        val t = text.trim()
        if (t.length < 2) return Signal(false)

        val hits = PAST_TRIGGERS.filter { t.contains(it) }
        if (hits.isEmpty()) return Signal(false)

        // 未来/待办优先排除：「你还记不记得明天要开会」→ 是提醒，不是回忆
        if (FUTURE_EXCLUSIONS.any { t.contains(it) }) return Signal(false)

        return Signal(true, hits)
    }
}
