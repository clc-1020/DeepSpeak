package com.companion.chat.data.remote

/**
 * 单次对话调用的可选项。任务 1.2 增补（DeepSeek 新 API）
 *
 * @param jsonMode       开启 response_format={"type":"json_object"}（mood / 记忆提取等结构化场景）
 * @param titleEnabled   开启 title={"enabled":true}（仅新建会话首条请求）
 * @param thinkingEnabled 是否开思考（deepseek-flash 默认开，陪伴要显式关）
 * @param reasoningEffort 思考强度 low/high/max，仅 thinkingEnabled=true 时生效
 * @param temperature    采样温度，null 用服务端默认
 */
data class ChatOptions(
    val jsonMode: Boolean = false,
    val titleEnabled: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val reasoningEffort: String? = null,
    val temperature: Double? = null
)
