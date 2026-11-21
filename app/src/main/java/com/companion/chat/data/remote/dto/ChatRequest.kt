package com.companion.chat.data.remote.dto

import com.google.gson.annotations.SerializedName

/** 文本消息（内部上下文用，content 为纯文本）。 */
data class ChatMessageDto(
    val role: String,
    val content: String
)

/** 发给 API 的消息：content 既可以是纯文本字符串，也可以是内容块数组（含图片）。 */
data class ApiMessageDto(
    val role: String,
    val content: Any
)

/** 多模态内容块（OpenAI 兼容）。 */
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String,
    val detail: String? = null
)

/** stream_options：要求 API 在流末尾返回 usage（DeepSeek / OpenAI 均支持）。 */
data class StreamOptions(
    @SerializedName("include_usage") val includeUsage: Boolean = true
)

/** 结构化输出：{"type":"json_object"}。为空则不传。 */
data class ResponseFormat(val type: String = "json_object")

/** 会话标题生成：{"enabled":true}。仅新建会话首条请求开一次。 */
data class TitleOption(val enabled: Boolean = true)

/** 思考控制：{"type":"enabled"|"disabled"}。deepseek-flash 默认开，陪伴要显式关。 */
data class ThinkingOption(val type: String)

/** 对话补全请求体（OpenAI 兼容格式 + DeepSeek 新参数）。 */
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessageDto>,
    val stream: Boolean = true,
    @SerializedName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
    val temperature: Double? = null,
    /** 结构化输出，为空不传（Gson 默认跳过 null）。 */
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null,
    /** 会话标题，仅首条请求传。 */
    val title: TitleOption? = null,
    /** 思考控制，仅 DeepSeek 服务商传。 */
    val thinking: ThinkingOption? = null,
    /** 思考强度，仅 thinking=enabled 时传。 */
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null
)

/** 非流式补全响应（callJson 用）。 */
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<CompletionChoice> = emptyList(),
    val usage: UsageDto? = null
)

data class CompletionChoice(
    val index: Int = 0,
    val message: CompletionMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class CompletionMessage(
    val role: String? = null,
    val content: String? = null,
    /** 非流式返回的会话标题。 */
    val title: String? = null
)
