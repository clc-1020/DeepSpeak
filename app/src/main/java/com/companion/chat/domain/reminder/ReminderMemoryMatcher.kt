package com.companion.chat.domain.reminder

/**
 * 「这条短期记忆是不是某条提醒留下的」判定（2026-11-20 纯函数）。
 *
 * 背景（真机数据）：用户点「已完成」后，短期记忆里还躺着
 * 「用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）」
 * 「喝水，每隔1分钟提醒我一次」这类**已结束的提醒**，越攒越多。
 *
 * 为什么不能按 id / 前缀删：记忆有两类来源，措辞都对不上提醒表的原文 ——
 *  - `ReminderAlarmReceiver.handleInstruction` 写的是提醒原文（与 `reminders.taskDesc` 一致）；
 *  - `MemoryExtractor` 从对话里提炼，措辞完全不同（「用户要求…提醒喝水…」）。
 * `memory_short` 也没有 reminderId 列可关联。所以只能按**内容相似度**判断。
 *
 * 判定规则：
 *  1. 归一化（去空白/标点）后**互相包含** → 删（覆盖措辞相同与短任务名 `喝水` ⊂ `…提醒喝水…`）；
 *  2. 否则**剥掉停用词**（`提醒/每隔/分钟/开始/…`）再判一次包含；
 *  3. 仍不中 → **最长公共子串 ≥ 2** 判同主题。
 *
 * 为什么必须剥停用词（真机踩到）：长任务名
 * 「现在我要背单词，提醒我。每隔5分钟提醒一次」与**喝水**那条记忆的公共片段是
 * 「分钟提醒」，不剥词就会把别的主题一起删掉。剥离后前者只剩「要背单词5个」，
 * 与喝水记忆无交集，而它与「…提醒一次背单词…」仍靠「背单词」命中。
 *
 * 为什么用最长公共子串而不是 2-gram 计数：`背30个单词` ↔ `…提醒一次背单词…`
 * 只共有一个 2-gram（`单词`），按计数会判成不同主题；LCS 直接回答「有没有 ≥2 字共同片段」。
 *
 * 倾向**宁可漏删**：多留一条记忆只是脏，误删一条用户的话就没了。
 *
 * 纯函数、零 Android 依赖，可直接 JVM 单测。
 */
object ReminderMemoryMatcher {

    /** 归一化：去掉空白与中英文标点。 */
    private fun normalize(s: String): String =
        s.replace(Regex("[\\s\\p{Punct}，。、；：？！「」『』（）【】《》…—～·]"), "")

    /**
     * 提醒类套话：这些词几乎出现在每条提醒里，留着会让不同主题「看起来相似」。
     * 剥离顺序无关（都是整词删除）。
     */
    private val STOP_WORDS = listOf(
        "提醒", "监督", "每隔", "分钟", "小时", "开始", "一次", "以后", "之后",
        "要求", "现在", "今天", "明天", "晚上", "早上", "已经", "任务", "一下", "记得"
    )

    private fun stripStopWords(s: String): String {
        var out = s
        for (w in STOP_WORDS) out = out.replace(w, "")
        return out
    }

    /**
     * @param memoryContent 短期记忆正文
     * @param task 提醒/追问的任务原文（`reminders.taskDesc`）
     * @return true = 这条记忆属于该任务，提醒结束时应当删掉
     */
    fun shouldClear(memoryContent: String, task: String): Boolean {
        val t = normalize(task)
        val c = normalize(memoryContent)
        if (t.isEmpty() || c.isEmpty()) return false

        // ① 原始文本互相包含
        if (c.contains(t) || t.contains(c)) return true

        // ② 剥掉套话后再看包含（「…提醒我喝水…」vs「喝水」剥完仍成立）
        val ts = stripStopWords(t)
        val cs = stripStopWords(c)
        if (ts.isNotEmpty() && cs.isNotEmpty() && (cs.contains(ts) || ts.contains(cs))) return true

        // ③ 最长公共子串 ≥ 2
        if (ts.isEmpty() || cs.isEmpty()) return false
        return longestCommonSubstringLength(ts, cs) >= MIN_COMMON_LEN
    }

    /** 最长公共子串长度（滚动一维 DP，O(n·m)，两侧都是短中文串）。 */
    private fun longestCommonSubstringLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            val tmp = prev
            prev = cur
            cur = tmp
            cur.fill(0)
        }
        return best
    }

    /** 共同片段下限（剥掉套话后，2 字同主题即可）。 */
    private const val MIN_COMMON_LEN = 2
}