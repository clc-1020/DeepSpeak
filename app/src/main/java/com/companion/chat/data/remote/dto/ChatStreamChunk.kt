package com.companion.chat.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** 流式返回的单帧（`data: {...}` 里的对象）。任务 1.2 */
data class ChatStreamChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: UsageDto? = null
)

data class StreamChoice(
    val delta: DeltaDto? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    /** 会话标题增量（仅首条 title 请求会带，通常在最后一个 chunk 给出）。 */
    val title: String? = null,
    /**
     * 思考内容增量（thinking=enabled 时才有）。当前 UI 只显示正文，这里解析出来避免丢失，
     * 便于将来做「思考过程」展示；不解析也不影响正文流。
     */
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    /**
     * 联网搜索引用（2026-11-23）：`deepseek_web_search` 工具返回的来源注解。
     * 用 JsonElement 宽松承载——结构由服务端决定，强类型化一旦不匹配会连正文一起丢帧，
     * 这里交给 CitationParser 逐项取 url/title/site_name。
     */
    val annotations: JsonElement? = null
)

/**
 * usage 统计。DeepSeek：prompt_cache_hit_tokens / prompt_cache_miss_tokens；
 * OpenAI：prompt_tokens_details.cached_tokens。二者都兼容。
 */
data class UsageDto(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0,
    @SerializedName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerializedName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerializedName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
)

data class PromptTokensDetails(
    @SerializedName("cached_tokens") val cachedTokens: Int? = null
)
