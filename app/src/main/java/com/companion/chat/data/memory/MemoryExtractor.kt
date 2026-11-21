package com.companion.chat.data.memory

import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.NoopEmbeddingService
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.memory.MemoryExtraction
import com.companion.chat.domain.memory.MemoryExtractionParser
import com.companion.chat.domain.memory.MemoryPromptBuilder
import com.companion.chat.domain.memory.TimeAnchorConverter
import com.google.gson.Gson
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记忆提取与存储。任务 2.2；短期/长期落库走语义去重。任务 2.3
 *
 * 流程：最近 3 轮对话 → callJson(json_object) → 解析 {short,long,daily} →
 *   short/long 走 [MemoryDeduplicator]（语义合并 or 插入）→ daily 按日合并。
 *
 * 关键约束：
 * - **独立调用、不复用会话 ID**：callJson 是一次全新无状态的 /chat/completions 请求。
 * - **失败保持原记忆**：解析/网络失败返回 failure，绝不写库、不覆盖旧记忆；上层 fire-and-forget 忽略。
 * - **语义去重（2.3）**：embedding 就绪则按余弦相似度合并；未就绪退回「完全相同文本」跳过，不阻断。
 */
class MemoryExtractor(
    private val apiClient: ChatApiClient,
    private val memoryShortDao: MemoryShortDao,
    private val memoryLongDao: MemoryLongDao,
    private val memoryDailyDao: MemoryDailyDao,
    private val embeddingServiceProvider: suspend () -> EmbeddingService = { NoopEmbeddingService() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val today: () -> String = { LocalDate.now().toString() },
    private val referenceDate: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }
) {
    private val gson = Gson()

    private val deduplicator = MemoryDeduplicator(
        shortDao = memoryShortDao,
        longDao = memoryLongDao,
        embeddingProvider = embeddingServiceProvider,
        now = now
    )

    /** 仅调用模型并解析，不落库。失败返回 failure。 */
    suspend fun extract(config: ApiConfig, recent: List<MessageEntity>): Result<MemoryExtraction> {
        val transcript = MemoryPromptBuilder.buildTranscript(recent)
        if (transcript.isBlank()) return Result.success(MemoryExtraction())
        val messages = listOf(ChatMessageDto("user", transcript))
        return apiClient.callJson(config, messages, schemaHint = MemoryPromptBuilder.SYSTEM)
            .fold(
                onSuccess = { raw ->
                    val parsed = MemoryExtractionParser.parse(raw)
                    if (parsed == null) Result.failure(IllegalStateException("记忆提取返回非法 JSON"))
                    else Result.success(parsed)
                },
                onFailure = { Result.failure(it) }
            )
    }

    /** 提取并落库；返回新写入条数。任何异常都被收敛为 failure，绝不外抛。 */
    suspend fun extractAndStore(config: ApiConfig, recent: List<MessageEntity>): Result<Int> {
        return try {
            val ex = extract(config, recent).getOrElse { return Result.failure(it) }
            Result.success(store(ex))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 把一次提取结果落库，返回**新增**条数（合并/跳过不计入）。
     * short/long 走语义去重；daily 按日期合并。拆成独立方法便于单测。
     */
    suspend fun store(extraction: MemoryExtraction): Int {
        val ref = referenceDate()
        var added = 0
        for (raw in extraction.short) {
            added += deduplicator.upsertShort(anchor(raw, ref))
        }
        for (m in extraction.long) {
            added += deduplicator.upsertLong(anchor(m.content, ref), m.category)
        }
        if (extraction.daily.isNotEmpty()) {
            added += mergeDaily(extraction.daily.map { anchor(it, ref) })
        }
        return added
    }

    /** 时间锚点转换（相对时间→绝对日期）。异常时原样返回，绝不影响落库。 */
    private fun anchor(text: String, ref: LocalDate): String =
        try { TimeAnchorConverter.convert(text, ref) } catch (_: Exception) { text }

    /** 把新事件合并进今天的日志（去重、保序）。返回新增事件数。 */
    private suspend fun mergeDaily(newEvents: List<String>): Int {
        val date = today()
        val existing = memoryDailyDao.getByDate(date)
        val events = if (existing != null) parseStringList(existing.events) else ArrayList()
        var added = 0
        for (raw in newEvents) {
            val e = raw.trim()
            if (e.isEmpty() || events.contains(e)) continue
            events.add(e)
            added++
        }
        if (added == 0 && existing != null) return 0
        memoryDailyDao.insert(
            MemoryDailyEntity(id = existing?.id ?: 0, date = date, events = gson.toJson(events))
        )
        return added
    }

    private fun parseStringList(json: String?): MutableList<String> {
        if (json.isNullOrBlank()) return ArrayList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            val list: List<String>? = gson.fromJson(json, type)
            ArrayList(list ?: emptyList())
        } catch (_: Exception) {
            ArrayList()
        }
    }
}
