package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.FloatArrayCodec
import com.companion.chat.data.embedding.VectorMath
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.domain.memory.MemoryContextBlock

/**
 * 从三张记忆表挑出**本轮该给模型看**的内容（2026-11-21）。
 *
 * 用户诉求：主对话里 AI 能看到「若干短期记忆 + 若干长期记忆 + 近 3 天日记」，
 * 相关的自然提一句，不相关就别提；按 token 预算截断。
 *
 * 职责边界：**挑选与拼装**在 [MemoryContextBlock]（纯函数、可单测）；这里只负责
 * 「读表 → 算相似度 → 交给纯函数 → 返回文本」。
 *
 * 为什么相关性用**本地向量**而不是再调一次大模型：
 *  - 每轮对话调一次模型判断「这条记忆相不相关」，等于把省下的 token 又花回去，还多 1~2s 首字延迟；
 *  - 本地 bge-small-zh 已经接入（去重功能在用），一次相似度计算是毫秒级、零 API 成本。
 *
 * 降级策略（任何一步失败都不影响主对话）：
 *  - embedding 未就绪 → 短期记忆**全部不注入**（宁可不提，也不要塞不相关的话）；
 *    长期/日记是稳定内容，照常注入。
 *  - 单条记忆算相似度失败 → 该条跳过。
 *  - 整体异常 → 返回 null（= 本轮不注入记忆）。
 *
 * 深度回忆（2026-11-22，[build] 的 `recallMode`）：
 *  命中「记不记得/上次」这类明确过去回忆词时，普通轮"跟这句话像不像"的召回会失准
 *  （问句本身很空，相似度低于阈值 → 召不回）。于是放宽：日记窗口 3→14 天、短期阈值 0.45→0.32、
 *  短期名额 4→6；第一档**一条都没过线**再放到"全部日记（限 [RECALL_DAILY_MAX] 天）+ 阈值 0.28"。
 *  ⚠️ 翻不到时**不塞"最近+重要度"凑数**，只由 [MemoryContextBlock.RECALL_NOTE] 让模型坦白记不清。
 */
class MemoryContextProvider(
    private val shortDao: MemoryShortDao,
    private val longDao: MemoryLongDao,
    private val dailyDao: MemoryDailyDao,
    private val embedding: () -> EmbeddingService,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param userText 本轮用户输入（算相似度用）
     * @param budgetChars 这段文本允许占用的字符数（≈ token）
     * @param recallMode 深度回忆（2026-11-22）：命中过去回忆词时为 true，放宽窗口/阈值多翻一遍。
     * @return 可注入的文本；无内容 → null（深度回忆轮即便无匹配也会返回"记不清别编"的提示）
     */
    suspend fun build(
        userText: String,
        budgetChars: Int,
        /**
         * 诊断回调（2026-11-21 新增）：把「每条候选的相似度分数」交出去，供 W 级日志打点。
         *
         * 为什么必须打分数：调阈值时只有「注入 N 字」根本看不出 0.45 拦住了谁 ——
         * 阈值只能靠真机分数分布来定，拍脑袋改只会来回试。
         */
        onDiagnostics: ((String) -> Unit)? = null,
        /**
         * 1.9.4.0：本轮筛选出的情绪回忆候选正文（已经过冷却/情绪加权，最多 2 条）。
         * 并进 long 列表一起按预算截断；空 = 本轮没有可回忆的情绪记忆。
         */
        emotional: List<String> = emptyList(),
        /** 深度回忆模式（见类注释）；仅命中过去回忆词且开关打开时为 true。 */
        recallMode: Boolean = false,
    ): String? = runCatching {
        if (budgetChars <= 0) return null

        // 本轮的查询向量只算一次，短期与日记共用
        val query = queryVector(userText)
        val short = if (query != null) shortEntries(query) else emptyList()
        // 1.9.4.0：情绪记忆（intensity>0）不再随 long 全量注入，只走 emotional 候选（已过冷却/情绪筛选）。
        // ⚠️ 1.9.4.1 修复：情绪候选必须放在普通长期记忆**前面**——MemoryContextBlock 对 long 做
        // distinct().take(LONG_LIMIT=12)，追加在末尾会在普通记忆 ≥12 条时被静默切掉（功能无声失效）。
        val long = emotional + runCatching {
            longDao.getAll().filter { it.intensity <= 0.0 }.map { it.content }
        }.getOrDefault(emptyList())

        // 日记窗口：普通轮只扫近 3 天；深度回忆先扫到 RECALL_DAILY_MAX 天（下面再按档位截）。
        val dailyScanLimit = if (recallMode) RECALL_DAILY_MAX else MemoryContextBlock.DAILY_DAYS
        val daily = dailyEntries(query, dailyScanLimit)

        // 定阈值 / 名额 / 日记天数（普通轮固定；深度回忆两档渐进放宽）
        val threshold: Float
        val shortLimit: Int
        val dailyDays: Int
        if (recallMode) {
            val stage1Pass = short.count { it.score != null && it.score >= RECALL_THRESHOLD_STAGE1 } +
                daily.take(RECALL_STAGE1_DAYS).count { it.score != null && it.score >= RECALL_THRESHOLD_STAGE1 }
            if (stage1Pass == 0) {
                // 第一档啥都没捞到 → 放到"全部日记 + 更低阈值"再翻一次
                threshold = RECALL_THRESHOLD_STAGE2
                dailyDays = RECALL_DAILY_MAX
            } else {
                threshold = RECALL_THRESHOLD_STAGE1
                dailyDays = RECALL_STAGE1_DAYS
            }
            shortLimit = RECALL_SHORT_LIMIT
        } else {
            threshold = MemoryContextBlock.SIMILARITY_THRESHOLD
            shortLimit = MemoryContextBlock.SHORT_LIMIT
            dailyDays = MemoryContextBlock.DAILY_DAYS
        }

        onDiagnostics?.invoke(diagnostics(short, daily.take(dailyDays), query != null, threshold))

        MemoryContextBlock.build(
            short = short,
            long = long,
            daily = daily,
            budgetChars = budgetChars,
            shortLimit = shortLimit,
            dailyDays = dailyDays,
            threshold = threshold,
            recallNote = if (recallMode) MemoryContextBlock.RECALL_NOTE else null,
        )
    }.getOrNull()

    /** 分数快照：短期前 6 条 / 日记（按档位） / 阈值；embedding 未就绪时显式说明。 */
    private fun diagnostics(
        short: List<MemoryContextBlock.ShortEntry>,
        daily: List<MemoryContextBlock.DailyEntry>,
        embeddingReady: Boolean,
        threshold: Float,
    ): String {
        if (!embeddingReady) return "向量未就绪（短期与日记都不注入）"
        fun f(v: Float?) = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"
        val s = short.take(6).joinToString(" ") { "${f(it.score)}|${it.text.take(8)}" }
        val d = daily.joinToString(" ") { "${f(it.score)}|${it.date}" }
        return "短期[$s] 日记[$d] 阈值$threshold"
    }

    /** 用户输入 → 向量；模型未就绪/空输入 → null（此时短期与日记都不注入）。 */
    private suspend fun queryVector(userText: String): FloatArray? {
        if (userText.isBlank()) return null
        val svc = runCatching { embedding() }.getOrNull() ?: return null
        if (!svc.isReady) return null
        return runCatching { svc.embed(userText) }.getOrNull()
    }

    /** 短期：算每条与本轮输入的相似度，按降序返回。 */
    private suspend fun shortEntries(query: FloatArray): List<MemoryContextBlock.ShortEntry> {
        val rows = runCatching { shortDao.getAll() }.getOrDefault(emptyList())
        return rows.mapNotNull { row -> score(row, query) }
            .sortedByDescending { it.score ?: 0f }
    }

    private suspend fun score(
        row: MemoryShortEntity,
        query: FloatArray
    ): MemoryContextBlock.ShortEntry? {
        if (row.content.isBlank()) return null
        val vec = cachedVector(row) ?: return null
        val sim = runCatching { VectorMath.cosineSimilarity(query, vec) }.getOrNull() ?: return null
        return MemoryContextBlock.ShortEntry(row.content, sim)
    }

    /** 优先用行里已存的向量（去重时写过），没有再现场算（不落库，避免每轮写放大）。 */
    private suspend fun cachedVector(row: MemoryShortEntity): FloatArray? {
        row.embedding?.let { bytes ->
            runCatching { FloatArrayCodec.fromBytes(bytes) }.getOrNull()?.let { return it }
        }
        val svc = runCatching { embedding() }.getOrNull() ?: return null
        if (!svc.isReady) return null
        return runCatching { svc.embed(row.content) }.getOrNull()
    }

    /**
     * 日记（按日期降序取前 [limit] 天）+ 每天的**相似度**（2026-11-21 用户要求：相关才注入）。
     *
     * 向量用展平后的正文（`readableDailyEvents`）算 —— 与最终注入的文本一致，
     * 否则 JSON 引号/逗号会稀释语义。query 为 null（模型未就绪）→ score 全 null → 不注入。
     *
     * @param limit 扫描天数。普通轮只扫近 3 天（省钱省时）；深度回忆轮扫到 [RECALL_DAILY_MAX] 天。
     */
    private suspend fun dailyEntries(
        query: FloatArray?,
        limit: Int = MemoryContextBlock.DAILY_DAYS,
    ): List<MemoryContextBlock.DailyEntry> {
        val rows = runCatching { dailyDao.getAll() }.getOrDefault(emptyList())
            .sortedByDescending { it.date }
            .take(limit)

        val svc = if (query != null) runCatching { embedding() }.getOrNull() else null
        return rows.map { row ->
            val text = MemoryContextBlock.readableDailyEvents(row.events)
            val sim = if (query != null && svc?.isReady == true && text.isNotBlank()) {
                runCatching {
                    VectorMath.cosineSimilarity(query, svc.embed(text))
                }.getOrNull()
            } else {
                null
            }
            MemoryContextBlock.DailyEntry(row.date, row.events, sim)
        }
    }

    companion object {
        /** 深度回忆第一档：日记扫描/截取天数（≈ 两周）。 */
        private const val RECALL_STAGE1_DAYS = 14

        /**
         * 深度回忆第二档最多扫多少天日记（≈ 半年）。
         * 设上限是为了防止"日记攒了好几年"时一次全量 embedding 卡住首字；够长了，代价可控。
         */
        private const val RECALL_DAILY_MAX = 180

        /** 第一档相似度阈值（比普通 0.45 松，问句再空也能捞到一些）。 */
        private const val RECALL_THRESHOLD_STAGE1 = 0.32f

        /** 第二档相似度阈值（第一档全落空才降到这；再低就离题了）。 */
        private const val RECALL_THRESHOLD_STAGE2 = 0.28f

        /** 深度回忆短期名额（比普通 4 条多给两条）。 */
        private const val RECALL_SHORT_LIMIT = 6
    }
}
