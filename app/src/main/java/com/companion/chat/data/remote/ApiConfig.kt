package com.companion.chat.data.remote

/**
 * API 配置。任务 1.2
 * @param providerId deepseek / openai / custom
 * @param baseUrl    形如 https://api.deepseek.com 或 https://api.openai.com/v1
 * @param apiKey     用户自填
 * @param model      模型名
 * @param contextWindow 模型上下文窗口（tokens），用于算底部状态栏「上下文%」
 * @param visionModel 识图模型；deepseek-flash 已原生视觉，留空即复用主模型
 * @param thinkingEnabled 主对话是否开思考（陪伴默认关，快且省）
 * @param deepSeekTtsEnabled TTS 是否用 DeepSeek 官方（联网计费），否则系统 TTS
 * @param ttsVoice 官方 TTS 音色：stella(女) / tide(男)
 * @param latestReminderEnabled 是否每轮注入 latest_reminder（日期/星期/地点…）
 * @param autoTitleEnabled 新建会话首条是否顺带生成标题
 * @param jsonModeDefault 结构化调用默认是否 json_object（按调用场景切换）
 */
data class ApiConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val contextWindow: Int = 65536,
    val visionModel: String = "",
    val thinkingEnabled: Boolean = false,
    val deepSeekTtsEnabled: Boolean = false,
    val ttsVoice: String = "stella",
    val latestReminderEnabled: Boolean = true,
    val autoTitleEnabled: Boolean = true,
    val jsonModeDefault: Boolean = false
)
