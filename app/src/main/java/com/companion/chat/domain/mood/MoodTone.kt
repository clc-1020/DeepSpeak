package com.companion.chat.domain.mood

/**
 * mood → 中文标签 + 语气指引（任务 5.2）。
 *
 * 生成主动消息时把当前 mood 写进 system，让模型据此调整语气：
 *  - happy → 活泼轻快
 *  - low   → 温和、关心，不追问不打趣
 *  - 其它/未知 → 自然平稳（兜底 normal，绝不抛错、绝不影响生成）
 */
object MoodTone {

    /** 归一化：未知/空/大小写混杂 → [Mood.NORMAL]。 */
    fun normalize(mood: String?): String {
        val m = mood?.trim()?.lowercase().orEmpty()
        return if (m in Mood.ALL) m else Mood.NORMAL
    }

    /** 中文标签（写进提示词，不给模型英文枚举）。 */
    fun label(mood: String?): String = when (normalize(mood)) {
        Mood.HAPPY -> "心情不错"
        Mood.LOW -> "有点低落"
        else -> "平常"
    }

    /** 语气指引（一句话，明确可执行）。 */
    fun directive(mood: String?): String = when (normalize(mood)) {
        Mood.HAPPY ->
            "语气活泼轻快，可以有笑意和感叹，主动分享一点小开心；别用力过猛、别一直刷感叹号。"
        Mood.LOW ->
            "语气温和、放缓一点，句子短一些，多关心和体谅；不追问、不打趣、不倒苦水。"
        else ->
            "语气自然平和，像平常朋友随口聊两句。"
    }
}