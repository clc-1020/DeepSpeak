package com.companion.chat.domain.emotion

import kotlin.random.Random

/**
 * 颜文字（2026-11-21，P1）。
 *
 * **由代码追加，不让模型自己写**：可控、零 token、能测，也不会某天突然开始刷屏。
 *
 * 规则（已与用户拍板）：
 *  - 主导情绪强度 ≥0.5 才考虑；≥0.7 → 90% 概率，0.5~0.7 → 60%
 *  - **正负并存时一个都不加**（那种状态加颜文字像在假笑）
 *  - 一条消息最多 1 个，追加在**整段最后**，前面一个半角空格
 *  - 末尾已有表内颜文字 → 不重复追加（防「历史回传 → 模型学会 → 代码再追加」变成双份）
 *  - [strip] 供 TTS 朗读用：否则会念成「括号 点 笑脸」
 */
object EmotionKaomoji {

    const val THRESHOLD = 0.5
    private const val HIGH = 0.7

    /** 概率档：>=0.7 → 0.9；0.5~0.7 → 0.6。 */
    fun probability(intensity: Double): Double = when {
        intensity >= HIGH -> 0.9
        intensity >= THRESHOLD -> 0.6
        else -> 0.0
    }

    private val TABLE: Map<String, List<String>> = mapOf(
        Emotion.HAPPY to listOf("(◕‿◕)", "(*´▽`*)"),
        Emotion.EXCITED to listOf("ヽ(°▽°)ノ", "(ﾉ≧∀≦)ﾉ"),
        Emotion.PROUD to listOf("(￣▽￣)", "(⌐■_■)"),
        Emotion.MISSING to listOf("(´-ω-`)", "(｡･ω･｡)"),
        Emotion.LOW to listOf("(´･_･`)", "(－_－)"),
        Emotion.HURT to listOf("(｡•́︿•̀｡)", "(｡╯︵╰｡)"),
        Emotion.ANNOYED to listOf("(￣ヘ￣)", "(눈_눈)"),
        Emotion.GUILTY to listOf("(￣ω￣;)", "(・・; )"),
    )

    /** 全部颜文字（供 [strip] 精确匹配用）。 */
    val ALL: List<String> = TABLE.values.flatten()

    fun palette(emotion: String?): List<String> = TABLE[Emotion.normalize(emotion) ?: ""] ?: emptyList()

    /**
     * 给一段文本按当前情绪追加颜文字。
     *
     * @param emotions 已衰减后的情绪 map（调用方保证是"此刻"的值）
     */
    fun append(
        text: String,
        emotions: Map<String, Double>,
        random: Random = Random.Default,
    ): String {
        val t = text.trimEnd()
        if (t.isEmpty()) return text
        if (endsWithKaomoji(t)) return text

        val dominant = emotions.maxByOrNull { it.value } ?: return text
        if (dominant.value < THRESHOLD) return text

        // 正负并存 → 不加（避免"假笑"）
        val hasPos = emotions.any { it.key !in Emotion.NEGATIVE && it.value >= EmotionPhrase.TWO_MIN }
        val hasNeg = emotions.any { it.key in Emotion.NEGATIVE && it.value >= EmotionPhrase.TWO_MIN }
        if (hasPos && hasNeg) return text

        val candidates = palette(dominant.key)
        if (candidates.isEmpty()) return text

        if (random.nextDouble() >= probability(dominant.value)) return text

        return t + " " + candidates[random.nextInt(candidates.size)]
    }

    /** TTS 用：去掉所有表内颜文字（句中、末尾都算）。 */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (k in ALL) out = out.replace(k, "")
        return out.replace(Regex(" {2,}"), " ").trim()
    }

    private fun endsWithKaomoji(text: String): Boolean {
        val t = text.trimEnd()
        return ALL.any { t.endsWith(it) }
    }
}