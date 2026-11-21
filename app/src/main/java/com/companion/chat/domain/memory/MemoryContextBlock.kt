package com.companion.chat.domain.memory

/**
 * 把三类记忆（短期 / 长期 / 日记）拼成**一段可注入主对话的文本**（2026-11-21 纯函数）。
 *
 * 用户诉求：AI 回复前能看到「一些短期记忆 + 一些长期记忆 + 近 3 天日记」，
 * 相关的自然提一句，不相关的就别提；并且**按 token 预算截断**，不能把上下文撑爆。
 *
 * 三条硬规则（每条都对应一次白花钱或一次尴尬）：
 *  1. **没相关就一个字都不注入**：返回 null，由调用方跳过。否则每轮塞一句「暂无相关记忆」
 *     纯烧 token，还会让模型刻意去提。
 *  2. **短期只留过相似度阈值的**：阈值以下的记忆跟当前话题无关，注入了只会让模型硬凑。
 *  3. **超预算按「日记 → 短期 → 长期」的顺序整段砍**：长期记忆（过敏、喜好、关系）最稳、
 *     最不该丢；日记最可替代。砍完为空就返回 null。
 *
 * 纯函数、零 Android 依赖，可直接 JVM 单测。
 */
object MemoryContextBlock {

    /** 短期记忆最多注入几条。 */
    const val SHORT_LIMIT = 4

    /** 长期记忆最多注入几条。 */
    const val LONG_LIMIT = 12

    /** 日记最多注入几天。 */
    const val DAILY_DAYS = 3

    /**
     * 短期/日记的相似度阈值（余弦；bge-small-zh 归一化向量点积）。
     *
     * 2026-11-21 从 0.35 提到 0.45：0.35 时「你觉得我今晚会尿床吗」把 4 条「背单词/喝水」
     * 短期记忆全放进去了 —— 虽然那次正好切题，但同类话题很容易变成「硬蹭」。
     * 调高后命中率下降、误提率也下降；宁可少提一句，也别东拉西扯。
     */

    const val SIMILARITY_THRESHOLD = 0.45f

    /** 一条短期候选：文本 + 与本轮用户话的相似度（null = 没算出来，一律不注入）。 */
    data class ShortEntry(val text: String, val score: Float?)

    /**
     * 一条日记：日期（yyyy-MM-dd）+ 事件正文 + 与本轮输入的相似度。
     *
     * [score] 为 null（模型未就绪 / 没算）时**不注入** —— 与短期同一口径：
     * 日记现在也走「相关才注入」，闲聊轮次直接省掉整段（2026-11-21 用户要求）。
     */
    data class DailyEntry(val date: String, val events: String, val score: Float? = null)

    /**
     * 提示语（2026-11-21 压缩到 25 字内）。
     *
     * 原文 69 字，每轮固定白花 40+ token —— 它是全段里信息密度最低的一句。
     * 压缩后意思不变：相关才提、别硬提、别罗列。
     */
    private const val HEADER = "【我记得的事】相关就自然提一句，不相关别硬提、别罗列。"

    /**
     * @param short 短期候选，**调用方应已按 score 降序**排好
     * @param long 长期记忆正文（未去重，本函数会去重）
     * @param daily 日记（调用方已按日期降序，最多取 [DAILY_DAYS] 天）
     * @param budgetChars 这段文本的字符预算（中文 ≈ 1 字 1 token）
     * @return 可用于注入的文本；无可注入内容或预算不足 → null
     */
    fun build(
        short: List<ShortEntry>,
        long: List<String>,
        daily: List<DailyEntry>,
        budgetChars: Int,
        shortLimit: Int = SHORT_LIMIT,
        longLimit: Int = LONG_LIMIT,
        dailyDays: Int = DAILY_DAYS,
        threshold: Float = SIMILARITY_THRESHOLD,
    ): String? {
        if (budgetChars <= 0) return null
        if (budgetChars <= HEADER.length) return null

        val shortKept = short
            .filter { it.score != null && it.score >= threshold && it.text.isNotBlank() }
            .map { it.text.trim() }
            // 2026-11-21：**同主题只留一条**。库里 4 条短期可能全是「背单词/喝水」，
            // 只按相似度过滤的话，只要有一条过线就会把 4 个名额全占满 ——
            // 记忆块变成「单一话题广播」，模型不管聊什么都往那上面拐。
            .let { dedupeByTopic(it) }
            .take(shortLimit)

        val longKept = long
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(longLimit)

        val dailyKept = daily
            .filter { it.score != null && it.score >= threshold && it.events.isNotBlank() }
            .take(dailyDays)
            .map { "${it.date} ${readableDailyEvents(it.events)}" }
            .filter { it.isNotBlank() }

        // 分段拼装；超预算时按**固定优先级**砍：日记 → 短期 → 长期
        // （注意：不能按拼接顺序 removeLast —— 那会先砍掉"关于你"，与设计相反）
        val parts = LinkedHashMap<String, String>(3)
        if (shortKept.isNotEmpty()) parts[SECTION_SHORT] = "- 最近：" + shortKept.joinToString("；")
        if (longKept.isNotEmpty()) parts[SECTION_LONG] = "- 关于你：" + longKept.joinToString("；")
        if (dailyKept.isNotEmpty()) parts[SECTION_DAILY] = "- 前几天：" + dailyKept.joinToString("；")
        if (parts.isEmpty()) return null

        fun assemble(): String =
            HEADER + "\n" + listOfNotNull(
                parts[SECTION_SHORT], parts[SECTION_LONG], parts[SECTION_DAILY]
            ).joinToString("\n")

        // 按「最可替代 → 最不该丢」的顺序整段丢弃
        val dropOrder = listOf(SECTION_DAILY, SECTION_SHORT, SECTION_LONG)
        var i = 0
        while (parts.isNotEmpty() && assemble().length > budgetChars && i < dropOrder.size) {
            parts.remove(dropOrder[i])
            i++
        }
        if (parts.isEmpty()) return null
        return assemble()
    }

    private const val SECTION_SHORT = "最近"
    private const val SECTION_LONG = "关于你"
    private const val SECTION_DAILY = "前几天"

    /** 同主题判定：剥停用词后**最长公共子串**至少这么长，且占较短那条 60% 以上。 */
    const val TOPIC_MIN_COMMON = 3

    /**
     * 同主题去重（保序，保留先出现的 = 相似度更高的那条）。
     *
     * 为什么用字符 bigram 而不是再算一次向量：短期候选本来就已经带着向量分数了，
     * 再来一轮 embedding 纯属浪费；而中文短句的 bigram 重合度对「换词不换事」非常敏感
     * （「每隔5分钟提醒一次背单词」vs「从现在起要求助教每5分钟提醒背单词」→ 高度重合）。
     */
    fun dedupeByTopic(texts: List<String>): List<String> {
        val kept = ArrayList<String>(texts.size)
        for (t in texts) {
            if (kept.none { sameTopic(it, t) }) kept += t
        }
        return kept
    }

    /**
     * 同主题判定（2026-11-21 第二版）。
     *
     * 第一版用纯 bigram Jaccard，实测**过严**：两条背单词记忆一条长一条短，
     * Jaccard 只有 0.28 → 没合并，问题依旧。改用「**先去停用词，再看包含关系**」：
     *  - 剥掉「用户/助手/提醒/每/分钟/次/一次/现在/开始…」这些所有提醒类句子都有的词
     *  - 剩下的是**主题词**（背单词 / 喝水 / 面试…）
     *  - 一方包含另一方 → 同一件事（「5背单词」包含「背单词」✓）
     *  - 若剩下的太短（<2 字）→ 退回完全相等判定，避免把「喝水」「睡觉」这类单词误合并
     *
     * 反例保护：「提醒我喝水」vs「提醒我背单词」剥完是「喝水」vs「背单词」→ 不合并 ✓
     */
    private val STOP_WORDS = listOf(
        "用户", "助手", "提醒", "监督", "现在", "从当前", "从这", "开始", "之后", "以后",
        "每隔", "分钟", "小时", "一次", "一下", "一个", "要求", "自己", "的时候", "我", "要",
        "每", "次", "个", "钟", "的", "了", "说", "让", "起", "隔", "得", "着",
    )

    fun sameTopic(a: String, b: String): Boolean {
        if (a == b) return true
        val ca = stripStopWords(a)
        val cb = stripStopWords(b)
        if (ca.length < 2 || cb.length < 2) return a == b
        if (ca.contains(cb) || cb.contains(ca)) return true
        // 兜底：**最长公共子串**够长且占比够高才算同一件事。
        // 为什么不用 Jaccard / Dice：两者都会把「喜欢猫」和「喜欢狗」（共享"喜欢"）
        // 判成同主题 —— 那是两条不同的事实。而「主题词」至少应有 3 个字重合
        // （背单词 / 花生过敏 / 明天面试），且占较短那条的 60% 以上。
        val lcs = longestCommonSubstring(ca, cb)
        if (lcs < TOPIC_MIN_COMMON) return false
        val shorter = minOf(ca.length, cb.length)
        return shorter > 0 && lcs.toDouble() / shorter >= 0.6
    }

    private fun stripStopWords(s: String): String {
        var out = s.filter { it.isLetterOrDigit() }
        for (w in STOP_WORDS) out = out.replace(w, "")
        return out
    }

    /** 最长公共子串长度（DP，字符串都是短句，O(n²) 足够）。 */
    private fun longestCommonSubstring(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            for (j in 1..b.length) {
                if (a[i - 1] == b[j - 1]) {
                    cur[j] = prev[j - 1] + 1
                    if (cur[j] > best) best = cur[j]
                }
            }
            prev = cur
        }
        return best
    }

    /** 日记里单条事件最多这么多字，超出截断（防止一条长流水把预算吃光）。 */
    private const val EVENT_MAX_CHARS = 60

    /**
     * 把 `memory_daily.events` 转成能直接读的句子（2026-11-21 真机发现）。
     *
     * 库里存的是 **JSON 数组字符串**（`["…","…"]`），原样注进上下文既占预算又难看：
     * 一条日记能吃掉上百字，还带一堆引号和逗号。这里解析成「；」连接的句子。
     * 解析失败（历史脏数据/普通文本）→ 原样返回，绝不让注入因此失败。
     */
    fun readableDailyEvents(raw: String): String {
        val text = raw.trim()
        val parsed = runCatching {
            val el = com.google.gson.JsonParser.parseString(text)
            if (!el.isJsonArray) return@runCatching null
            el.asJsonArray.mapNotNull { item ->
                runCatching { item.asString.trim() }.getOrNull()
            }.filter { it.isNotBlank() }
        }.getOrNull()

        val items = parsed ?: return text.take(EVENT_MAX_CHARS)
        if (items.isEmpty()) return ""
        return items.joinToString("；") { it.take(EVENT_MAX_CHARS) }
    }
}