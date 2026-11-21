package com.companion.chat.domain

import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.dto.ChatMessageDto

/**
 * 多轮上下文裁剪（纯逻辑，可单测）。任务 1.4
 *
 * 估算 token ≈ 字符数（中文大致 1 字 1 token），预算 = 上下文窗口 × ratio。
 * 策略：**只把第一条 system（人设 + 能力 + 长期摘要）钉在最前**，其余消息（含 2026-11-21 新增的
 * 记忆块 system）按位置从最新往旧保留，超预算即停。
 *
 * 为什么不再「所有 system 都钉住」：记忆块也是 system，钉住会把它提到人设之前，
 * 既打乱「稳定前缀 → 历史 → 记忆 → user」的顺序，也让前缀缓存失效。
 */
object ContextTrimmer {

    fun trim(
        messages: List<ChatMessageDto>,
        contextWindow: Int,
        budgetRatio: Double = 0.8
    ): List<ChatMessageDto> {
        if (contextWindow <= 0) return messages
        val budget = (contextWindow * budgetRatio).toInt().coerceAtLeast(1)

        val pinnedIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        val pinned = messages.getOrNull(pinnedIndex)
        val others = if (pinnedIndex >= 0) {
            messages.filterIndexed { i, _ -> i != pinnedIndex }
        } else {
            messages
        }

        var used = pinned?.content?.length ?: 0
        val kept = ArrayDeque<ChatMessageDto>()
        for (m in others.asReversed()) {
            val len = m.content.length
            if (used + len > budget && kept.isNotEmpty()) break
            used += len
            kept.addFirst(m)
        }
        return listOfNotNull(pinned) + kept
    }
}
