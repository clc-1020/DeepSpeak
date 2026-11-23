package com.companion.chat.data.remote

import android.util.Log
import com.companion.chat.data.remote.dto.ChatCompletionResponse
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.data.remote.dto.ChatRequest
import com.companion.chat.data.remote.dto.ChatStreamChunk
import com.companion.chat.data.remote.dto.ResponseFormat
import com.companion.chat.data.remote.dto.ThinkingOption
import com.companion.chat.data.remote.dto.TitleOption
import com.companion.chat.data.remote.dto.ToolOption
import com.companion.chat.data.remote.dto.UsageDto
import com.companion.chat.domain.chat.CitationParser
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的流式聊天客户端（DeepSeek / OpenAI / 自建兼容网关通用）。任务 1.2
 *
 * tok/s 采用「首 token → 末 token」的生成用时，不含网络与首 token 延迟。
 * 任务 1.2 增补：支持 response_format(json_object) / title / thinking / reasoning_effort；
 * 并提供非流式 callJson() 供 mood、记忆提取等结构化调用。
 */
class ChatApiClient(
    private val client: OkHttpClient = defaultClient()
) {
    private val gson = Gson()

    fun streamChat(
        config: ApiConfig,
        messages: List<ChatMessageDto>,
        images: List<ImageAttachment> = emptyList(),
        opts: ChatOptions = ChatOptions()
    ): Flow<StreamEvent> = callbackFlow {
        if (config.apiKey.isBlank()) {
            trySend(StreamEvent.Failed(IllegalStateException("未填写 API Key")))
            close()
            return@callbackFlow
        }
        if (config.baseUrl.isBlank() || config.model.isBlank()) {
            trySend(StreamEvent.Failed(IllegalStateException("Base URL 或模型未配置")))
            close()
            return@callbackFlow
        }

        var lastUsage: UsageDto? = null
        var firstTokenMs: Long? = null
        var lastTokenMs: Long = 0L

        // 联网搜索：只有 DeepSeek 官方兼容端点 + deepseek-flash 才认这个服务端工具。
        // UI 层已置灰过一遍，这里**再判一次**（防御性重复）：即使 UI 漏判也绝不把工具发给不认识的端点。
        val webSearchSupported = opts.webSearchEnabled &&
            ProviderPresets.isDeepSeekCompatible(config.providerId, config.baseUrl) &&
            config.model == "deepseek-flash"

        val payload = ChatRequest(
            model = config.model,
            messages = MultimodalRequestBuilder.build(messages, images),
            temperature = opts.temperature,
            responseFormat = if (opts.jsonMode) ResponseFormat() else null,
            // 官方契约：联网工具（deepseek_web_search）**不能和 title 同传**，同传会 400。
            // 所以联网轮次强制丢掉 title；title 生成的兜底（前缀标题）由 ChatViewModel 负责。
            title = if (opts.titleEnabled && !webSearchSupported) TitleOption() else null,
            thinking = buildThinking(config, opts),
            reasoningEffort = if (opts.thinkingEnabled) opts.reasoningEffort else null,
            tools = if (webSearchSupported) listOf(ToolOption("deepseek_web_search")) else null
        )
        // V1.8.0 诊断（临时）：打印真实请求体规模与最后一条消息 content 的结构。
        // android.util.Log 在 JVM 单测里是未实现 stub，必须 runCatching 兜住。
        val payloadJson = gson.toJson(payload)
        runCatching {
            val last = payload.messages.lastOrNull()
            Log.w(
                "MMDiag",
                "model=${config.model} images=${images.size} payloadChars=${payloadJson.length} " +
                    "apiMsgs=${payload.messages.size} lastRole=${last?.role} " +
                    "lastIsArray=${last?.content is List<*>} " +
                    "imgUrlHead=${images.firstOrNull()?.dataUrl?.take(40)}"
            )
        }
        val request = Request.Builder()
            .url(buildEndpoint(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val trimmed = data.trim()
                if (trimmed.isEmpty() || trimmed == "[DONE]") return
                try {
                    val chunk = gson.fromJson(trimmed, ChatStreamChunk::class.java) ?: return
                    chunk.usage?.let { lastUsage = it }
                    val deltaDto = chunk.choices.firstOrNull()?.delta
                    deltaDto?.title?.takeIf { it.isNotBlank() }?.let {
                        trySend(StreamEvent.Title(it))
                    }
                    // 联网引用：从 delta.annotations 提取。解析容错，坏帧只影响本帧引用，不影响正文。
                    deltaDto?.annotations?.let { ann ->
                        val cites = CitationParser.parse(ann)
                        if (cites.isNotEmpty()) {
                            trySend(StreamEvent.Citations(cites))
                        }
                    }
                    val delta = deltaDto?.content
                    if (!delta.isNullOrEmpty()) {
                        val now = System.currentTimeMillis()
                        if (firstTokenMs == null) firstTokenMs = now
                        lastTokenMs = now
                        trySend(StreamEvent.Delta(delta))
                    }
                } catch (e: JsonSyntaxException) {
                    // 单帧解析失败则跳过，不影响整条流
                }
            }

            override fun onClosed(eventSource: EventSource) {
                val generationMs = firstTokenMs?.let { (lastTokenMs - it).coerceAtLeast(0L) } ?: 0L
                trySend(StreamEvent.Completed(UsageInfo.from(lastUsage, config.contextWindow, generationMs)))
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val err: Throwable = when {
                    response != null -> IOException("请求失败：HTTP ${response.code} ${response.message}")
                    t != null -> t
                    else -> IOException("未知网络错误")
                }
                // ⚠️ 顺序与兜底都是必须的（V2.1.8 现场教训，代价是测试挂死 5 分钟）：
                // 本回调跑在 OkHttp 线程上，任何未捕获异常都会静默杀死它 —— 一旦死在 trySend/close
                // 之前，callbackFlow 永不结束，真机表现为「发消息一直转」，JVM 单测表现为 runBlocking 挂死。
                // 而 android.util.Log 在单元测试里是**未实现的 stub**（会抛 "not mocked"），
                // 所以日志只能放在最后，并且必须 runCatching 兜住。
                trySend(StreamEvent.Failed(err))
                close()
                runCatching { Log.w(TAG, "流式请求失败: ${err.message}", err) }
            }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /**
     * 非流式结构化调用：返回模型输出的原始 JSON 串（由上层 Gson 解析）。
     * 用于 mood 更新、记忆提取等需要稳定 JSON 的场景。
     *
     * @param schemaHint 非空时作为额外 system 消息插在最前，明确「只输出 JSON」的要求。
     */
    suspend fun callJson(
        config: ApiConfig,
        messages: List<ChatMessageDto>,
        schemaHint: String = "",
        opts: ChatOptions = ChatOptions(jsonMode = true, thinkingEnabled = false)
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未填写 API Key"))
        }
        if (config.baseUrl.isBlank() || config.model.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Base URL 或模型未配置"))
        }
        val fullMessages = ArrayList<ChatMessageDto>(messages.size + 1)
        if (schemaHint.isNotBlank()) {
            fullMessages.add(ChatMessageDto("system", schemaHint.trim()))
        }
        fullMessages.addAll(messages)

        val payload = ChatRequest(
            model = config.model,
            messages = MultimodalRequestBuilder.build(fullMessages, emptyList()),
            stream = false,
            streamOptions = null,
            temperature = opts.temperature,
            responseFormat = if (opts.jsonMode) ResponseFormat() else null,
            title = null,
            thinking = buildThinking(config, opts),
            reasoningEffort = if (opts.thinkingEnabled) opts.reasoningEffort else null
        )
        val request = Request.Builder()
            .url(buildEndpoint(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("请求失败：HTTP ${resp.code} ${resp.message}")
                    )
                }
                val parsed = gson.fromJson(bodyStr, ChatCompletionResponse::class.java)
                val content = parsed?.choices?.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) {
                    Result.failure(IOException("空响应"))
                } else {
                    Result.success(content)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** thinking 仅对 DeepSeek 兼容端点发送，避免其它服务商因未知参数报 400。 */
    private fun buildThinking(config: ApiConfig, opts: ChatOptions): ThinkingOption? {
        if (!ProviderPresets.isDeepSeekCompatible(config.providerId, config.baseUrl)) return null
        return ThinkingOption(if (opts.thinkingEnabled) "enabled" else "disabled")
    }

    private fun buildEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    companion object {
        private const val TAG = "ChatApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // 流式读取，给足时间
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
