package com.companion.chat.domain.proactive

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.domain.mood.MoodTone

/**
 * 主动消息生成的提示词构建（纯函数，可 JVM 单测）。任务 3.5 / 5.2
 *
 * 输入：人设、当前 mood、可选话题线索（记忆钩子/提醒内容）、最近几条对话。
 * 输出 system + user 两段提示词，交给模型生成一条「主动搭话」消息。
 *
 * 任务 5.2：mood 写进 **system**（「你现在心情是 {mood}，据此调整语气」），
 * 由 [MoodTone] 给出各 mood 的语气指引；user 只放上下文，不重复 mood。
 */
object ProactivePromptBuilder {

    /**
     * system：人设 + 当前 mood 与语气要求 + **她的情绪状态** + 生成规则。
     *
     * P2（2026-11-21）：情绪除了改语气，还**改长度**：
     *  - 兴奋/想你 → 允许多说两句（她高兴了话就密）
     *  - 低落/烦躁/委屈 → 只说一句（她没劲）
     * 这就是用户说的"情绪影响频率与长度"里的"长度"。
     */
    fun system(
        persona: String,
        mood: String = "normal",
        emotions: Map<String, Double> = emptyMap(),
        allowAffection: Boolean = com.companion.chat.domain.emotion.EmotionPhrase.ALLOW_AFFECTION,
    ): String = buildString {
        append("你是一个有温度、会主动关心人的陪聊伙伴。")
        if (persona.isNotBlank()) {
            append("\n人设：")
            append(persona.trim())
        }
        append("\n你现在心情是：")
        append(MoodTone.label(mood))
        append("，请据此调整语气。\n")
        append(MoodTone.directive(mood))

        val dominant = emotions.maxByOrNull { it.value }
        val active = dominant?.takeIf { it.value >= 0.2 }
        if (active != null) {
            append("\n你此刻还带着情绪：")
            append(com.companion.chat.domain.emotion.EmotionPhrase.label(active.key))
            append("（")
            append(com.companion.chat.domain.emotion.EmotionPhrase.fmt(active.value))
            append("）。")
            append(com.companion.chat.domain.emotion.EmotionPhrase.directive(active.key, allowAffection))
        }

        append("\n任务：生成**一条**你主动发给用户的闲聊消息，像朋友自然搭话，口语化、可带一句关心或提问。")
        append("\n" + lengthHint(active?.key))
        append("\n要求：")
        append("\n- 只输出这条消息本身，不要加引号、前缀、解释或「我根据……」。")
        append("\n- 不要暴露你在使用记忆或提醒。")
        append("\n- 不要重复最近对话里已经说过的内容。")
    }

    /** 情绪 → 长度要求（P2）。默认 1-2 句，与旧行为一致。 */
    private fun lengthHint(emotion: String?): String = when (emotion) {
        com.companion.chat.domain.emotion.Emotion.EXCITED -> "这次可以多说两句（2-3 句），节奏快一点。"
        com.companion.chat.domain.emotion.Emotion.MISSING -> "这次只说一句，轻一点。"
        com.companion.chat.domain.emotion.Emotion.LOW,
        com.companion.chat.domain.emotion.Emotion.ANNOYED,
        com.companion.chat.domain.emotion.Emotion.HURT -> "这次只说一句，短一点，别勉强热闹。"
        else -> "长度 1-2 句。"
    }

    /** user：把话题线索 + 最近对话组织成一段。 */
    fun build(hookText: String, recent: List<MessageEntity>): String = buildString {
        val recentLines = recent
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(3)
            .map { if (it.role == MessageRole.USER) "用户：${it.content.trim()}" else "你：${it.content.trim()}" }
        if (recentLines.isNotEmpty()) {
            append("最近对话：\n")
            append(recentLines.joinToString("\n"))
        }
        if (hookText.isNotBlank()) {
            if (recentLines.isNotEmpty()) append("\n")
            append("可以自然切入的话题：")
            append(hookText.trim())
        }
        append("\n请生成你要主动发出的那条消息。")
    }
}