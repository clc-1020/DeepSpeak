package com.companion.chat.domain.proactive

/**
 * 「没有短期记忆时说什么」的选择逻辑（2026-11-21 纯函数）。
 *
 * 背景：主动消息原本只有**短期记忆召回**一个话题源。短期记忆一空（新装、清过记忆、
 * 连续几天没好好聊），检查照跑但 `NO_HOOK`，用户看到的是「再也不主动找我」——
 * 而且分不清是「没话题」还是「坏了」。真机上就发生过：排程被冻 + 短期记忆被清，
 * 静默了 12 小时。
 *
 * 兜底顺序：
 *  1. **长期记忆**（过敏、喜好、重要关系这类稳定信息）：每条 **7 天内不重复**用，
 *     否则会反复念叨同一件事（「你花生过敏」说八遍比不说更烦）。
 *  2. **纯关心**（不问具体事）：**每天最多 1 次**，且必须距上次主动消息 ≥3 小时 ——
 *     没有内容支撑的问候高频了就变成催命。
 *
 * 纯函数、零 Android 依赖，可直接 JVM 单测。
 */
object ProactiveFallbackTopics {

    /** 同一条长期记忆的复用冷却（7 天）。 */
    const val LONG_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

    /** 纯关心与上一条主动消息的最小间隔（3 小时）。 */
    const val KINDNESS_MIN_GAP_MS = 3L * 60 * 60 * 1000

    /** 长期记忆兜底：挑一条最近 7 天没用过的，优先新加的。 */
    data class LongPick(val id: Long, val text: String)

    /**
     * @param longMemories 长期记忆（id + 正文），调用方按 createTime 倒序即可
     * @param lastUsedAt 每条长期记忆上次被当作话题的时刻（id → epoch millis）
     */
    fun pickLongMemory(
        longMemories: List<LongPick>,
        lastUsedAt: Map<Long, Long>,
        nowMillis: Long,
        cooldownMs: Long = LONG_COOLDOWN_MS,
    ): LongPick? = longMemories.firstOrNull { m ->
        val last = lastUsedAt[m.id]
        last == null || nowMillis - last >= cooldownMs || nowMillis < last
    }

    /**
     * 纯关心是否允许发。
     *
     * @param lastKindnessDate 上次发纯关心的本地日期（yyyy-MM-dd；null=从没发过）
     * @param today 今天本地日期（yyyy-MM-dd）
     * @param lastProactiveAt 上一条**任意**主动消息送达时刻（null=从没发过）
     */
    fun kindnessAllowed(
        lastKindnessDate: String?,
        today: String,
        lastProactiveAt: Long?,
        nowMillis: Long,
        minGapMs: Long = KINDNESS_MIN_GAP_MS,
    ): Boolean {
        if (lastKindnessDate == today) return false          // 今天已经发过
        val last = lastProactiveAt ?: return true            // 从没发过 → 可以
        if (nowMillis < last) return true                    // 时钟回拨 → 当作没发过，别永久堵死
        return nowMillis - last >= minGapMs
    }

    /** 纯关心的 hook 文本（交给模型写成一句话，不直接展示）。 */
    const val KINDNESS_HOOK: String =
        "没有具体话题。就用一句话自然地问候一下对方最近怎么样，语气像熟人，" +
            "不要说教、不要问工作进度、不要编造具体的事。"

    /** 长期记忆的 hook 文本模板。 */
    fun longMemoryHook(content: String): String =
        "关于对方的一件事：$content。如果跟最近的氛围搭得上，就自然地带一句关心；" +
            "搭不上就只随便聊一句，不要硬提这件具体的事，也不要罗列。"
}