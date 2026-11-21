package com.companion.chat.data.remote

import com.companion.chat.data.remote.dto.UsageDto

/**
 * 归一化后的用量信息（对应底部状态栏三项 + 原始 token 数）。任务 1.2
 * @param tokPerSec      生成速度 = completion_tokens / 生成用时（首 token → 末 token）
 * @param cacheHitRatio  缓存命中率 = 命中缓存 prompt tokens / 总 prompt tokens（0~1）
 * @param contextUsage   上下文占用 = total_tokens / 模型上下文窗口（0~1）；total_tokens 为 0 时返回 null
 */
data class UsageInfo(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedTokens: Int,
    val tokPerSec: Double?,
    val cacheHitRatio: Double?,
    val contextUsage: Double?
) {
    companion object {
        /**
         * @param generationMs 生成用时（首 token → 末 token 的毫秒数），用于算 tok/s。
         *                     之所以不用「请求总耗时」，是为了排除网络延迟与首 token 延迟。
         */
        fun from(usage: UsageDto?, contextWindow: Int, generationMs: Long): UsageInfo? {
            if (usage == null) return null

            // 命中 token：优先 DeepSeek 字段，其次 OpenAI cached_tokens
            val hit = usage.promptCacheHitTokens
                ?: usage.promptTokensDetails?.cachedTokens
                ?: 0
            val miss = usage.promptCacheMissTokens
                ?: (usage.promptTokens - hit).coerceAtLeast(0)
            val cacheDenom = hit + miss
            val cacheRatio = if (cacheDenom > 0) hit.toDouble() / cacheDenom else null

            val tokPerSec = if (generationMs > 0 && usage.completionTokens > 0) {
                usage.completionTokens * 1000.0 / generationMs
            } else null

            // total_tokens 为 0（部分模型不返回）时给 null，UI 显示 ??，不误报 0%
            val ctx = if (contextWindow > 0 && usage.totalTokens > 0) {
                usage.totalTokens.toDouble() / contextWindow
            } else null

            return UsageInfo(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
                cachedTokens = hit,
                tokPerSec = tokPerSec,
                cacheHitRatio = cacheRatio,
                contextUsage = ctx
            )
        }
    }
}
