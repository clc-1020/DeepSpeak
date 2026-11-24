package com.companion.chat.domain.emotion

import kotlin.random.Random

/**
 * 颜文字（2026-11-21，P1；2026-11-23 扩池+强度分档）。
 *
 * **由代码追加，不让模型自己写**：可控、零 token、能测，也不会某天突然开始刷屏。
 *
 * 规则（已与用户拍板）：
 *  - 主导情绪强度 ≥0.5 才考虑；≥0.7 → 90% 概率，0.5~0.7 → 60%
 *  - **强度分两档选脸**：≥0.7 用「强烈档」更夸张的脸，0.5~0.7 用「温和档」收敛的脸；
 *    分档边界与概率档都切在 0.7，避免出现两套「强」的定义
 *  - **正负并存时一个都不加**（那种状态加颜文字像在假笑）
 *  - 一条消息最多 1 个，追加在**整段最后**，前面一个半角空格
 *  - 末尾已有表内颜文字 → 不重复追加（防「历史回传 → 模型学会 → 代码再追加」变成双份）
 *  - [strip] 供 TTS 朗读用：否则会念成「括号 点 笑脸」
 *
 * 池子：每种情绪温和档 3 个 + 强烈档 3 个 = 6 个，全库 8 种 = 48 个（原 16 个全部保留）。
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

    /** 温和档（0.5~0.7）：收敛、不外放的脸色，每种 3 个。 */
    private val MILD: Map<String, List<String>> = mapOf(
        Emotion.HAPPY to listOf("(◕‿◕)", "(´｡• ᵕ •｡`)", "(¬‿¬)"),
        Emotion.EXCITED to listOf("(≧▽≦)", "(ﾉ´ヮ`)ﾉ*:･ﾟ✧", "(｀・ω・´)ゞ"),
        Emotion.PROUD to listOf("(￣▽￣)", "(￣ω￣)", "(⌐■_■)"),
        Emotion.MISSING to listOf("(´-ω-`)", "(｡･ω･｡)", "(´,,•ω•,,)♡"),
        Emotion.LOW to listOf("(´･_･`)", "(－_－)", "(´-_-`)"),
        Emotion.HURT to listOf("(｡•́︿•̀｡)", "(´｡•́︿•̀｡`)", "(￣^￣)"),
        Emotion.ANNOYED to listOf("(￣ヘ￣)", "(눈_눈)", "(￣～￣\")"),
        Emotion.GUILTY to listOf("(￣ω￣;)", "(・・; )", "(・_・;)"),
    )

    /** 强烈档（≥0.7）：夸张、外放的脸色，每种 3 个。 */
    private val STRONG: Map<String, List<String>> = mapOf(
        Emotion.HAPPY to listOf("(*´▽`*)", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "ヽ(>∀<☆)ノ"),
        Emotion.EXCITED to listOf("ヽ(°▽°)ノ", "(ﾉ≧∀≦)ﾉ", "＼(≧▽≦)／"),
        Emotion.PROUD to listOf("(｀∀´)Ψ", "ヽ(￣▽￣)ノ", "(✧∀✧)ノ"),
        Emotion.MISSING to listOf("(つ﹏⊂)", "(´；ω；`)", "(◞‸◟)"),
        Emotion.LOW to listOf("(╥﹏╥)", "(´；д；`)", "(ಥ﹏ಥ)"),
        Emotion.HURT to listOf("(｡╯︵╰｡)", "(｡╥﹏╥｡)", "(இ﹏இ`｡)"),
        Emotion.ANNOYED to listOf("(╬￣皿￣)", "(＃`д´)", "(╯°□°）╯︵ ┻━┻"),
        Emotion.GUILTY to listOf("(￣□￣;)!!", "(lll￣□￣)", "(ｰ ﾛ ｰ;;)"),
    )

    /** 全部颜文字（供 [strip] 精确匹配用）。 */
    val ALL: List<String> = MILD.values.flatten() + STRONG.values.flatten()

    /** 某情绪的全部颜文字（温和 + 强烈，共 6 个）；未知情绪返回空。 */
    fun palette(emotion: String?): List<String> {
        val key = Emotion.normalize(emotion) ?: return emptyList()
        return MILD[key].orEmpty() + STRONG[key].orEmpty()
    }

    /**
     * 某情绪在给定强度下会用的池子：强度 ≥0.7 取强烈档，否则取温和档。
     * 供 [append] 与单测共用，保证「分档选脸」可验证。
     */
    fun pool(emotion: String?, intensity: Double): List<String> {
        val key = Emotion.normalize(emotion) ?: return emptyList()
        return (if (intensity >= HIGH) STRONG[key] else MILD[key]).orEmpty()
    }

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

        // 按强度分档选池子；分档取不到再退回整张表兜底
        val candidates = pool(dominant.key, dominant.value).ifEmpty { palette(dominant.key) }
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
