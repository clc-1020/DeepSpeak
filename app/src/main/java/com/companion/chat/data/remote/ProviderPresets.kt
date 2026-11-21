package com.companion.chat.data.remote

import java.net.URI

/** 预设服务商与模型清单。任务 1.2 */
object ProviderPresets {

    const val DEEPSEEK = "deepseek"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    private val modelMap = mapOf(
        // deepseek-chat / deepseek-reasoner 已于 2026-07-24 下线，统一用 deepseek-flash
        DEEPSEEK to listOf("deepseek-flash", "deepseek-v4-pro"),
        OPENAI to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"),
        CUSTOM to emptyList()
    )

    /** 旧模型名 → 现役模型名。读配置时把历史遗留的无效名兜底映射过来。 */
    private val legacyModelMap = mapOf(
        "deepseek-chat" to "deepseek-flash",
        "deepseek-reasoner" to "deepseek-flash",
        "deepseek-4.1-flash" to "deepseek-flash",
        "deepseek-v4-flash-vision-exp" to "deepseek-flash"
    )

    /** 把可能已下线的旧模型名映射为现役模型；无映射则原样返回。 */
    fun migrateModel(model: String): String = legacyModelMap[model] ?: model

    private val baseUrlMap = mapOf(
        DEEPSEEK to "https://api.deepseek.com",
        OPENAI to "https://api.openai.com/v1",
        CUSTOM to ""
    )

    fun models(providerId: String): List<String> = modelMap[providerId] ?: emptyList()

    fun defaultBaseUrl(providerId: String): String = baseUrlMap[providerId] ?: ""

    /** 识图（多模态）模型。deepseek-flash 已原生支持视觉，直接复用；OpenAI 用 gpt-4o。 */
    fun defaultVisionModel(providerId: String): String = when (providerId) {
        DEEPSEEK -> "deepseek-flash"
        OPENAI -> "gpt-4o"
        else -> ""
    }

    /** 依据模型名估一个上下文窗口（tokens）。用于「上下文%」。 */
    fun defaultContextWindow(model: String): Int = when {
        // deepseek-flash / deepseek-v4-pro 均为 1M 上下文
        model.startsWith("deepseek") -> 1_000_000
        model.startsWith("gpt-4o") || model.startsWith("o1") -> 128000
        else -> 65536
    }

    /**
     * 是否走 DeepSeek 专有能力（thinking / title / latest_reminder 等）。
     * 显式选 DeepSeek，或自定义服务商的 BaseUrl **host** 指向 deepseek.com 时视为兼容。
     *
     * 只比对解析后的 host（等于 deepseek.com 或 .deepseek.com 子域），不再用 substring 匹配，
     * 避免 notdeepseek.com / deepseek.com.evil.com / https://evil.com/?x=deepseek.com 之类被误判。
     */
    fun isDeepSeekCompatible(providerId: String, baseUrl: String): Boolean {
        if (providerId == DEEPSEEK) return true
        val host = extractHost(baseUrl) ?: return false
        return host == "deepseek.com" || host.endsWith(".deepseek.com")
    }

    /** 从 baseUrl 解析 host（容忍缺 scheme、带端口/路径；解析失败返回 null）。 */
    private fun extractHost(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            URI(withScheme).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
