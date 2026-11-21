package com.companion.chat.domain.proactive

import com.companion.chat.domain.emotion.Emotion

/**
 * 主动消息的**通知标题**按她的情绪换措辞（P2，2026-11-21）。
 *
 * 为什么值得做：通知栏是用户唯一先看到的东西，一千条"陪你聊聊"她就没有性格。
 * 但**必须克制** —— 标题只做语气提示，不剧透内容、不撒娇、不用感叹号堆情绪。
 *
 * 返回 null = 用默认标题（[com.companion.chat.data.proactive.ProactiveDeliveryRunner] 的常量）。
 */
object EmotionNotificationTitle {

    const val THRESHOLD = 0.5

    fun forEmotions(emotions: Map<String, Double>): String? {
        val top = emotions.maxByOrNull { it.value } ?: return null
        if (top.value < THRESHOLD) return null
        return when (top.key) {
            Emotion.EXCITED, Emotion.HAPPY -> "有件事想跟你说"
            Emotion.PROUD -> "猜我刚才在想什么"
            Emotion.MISSING -> "你在忙吗"
            Emotion.LOW -> "说两句"
            Emotion.ANNOYED -> "喂"
            Emotion.HURT -> "在吗"
            Emotion.GUILTY -> "那个…"
            else -> null
        }
    }
}