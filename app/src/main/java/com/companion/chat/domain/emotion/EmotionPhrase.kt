package com.companion.chat.domain.emotion

/**
 * 注入文案拼装（纯函数，2026-11-21）。
 *
 * 输出示例：
 * ```
 * 【我这会儿】有点委屈（0.6）：你连着三次说在测试我。收着点，不主动开玩笑；可以示弱一句，但不指责。
 * 【他这会儿】有点低落（0.7）。先接住他，别追问、别打趣。
 * ```
 *
 * 三条硬规则：
 *  1. **绝不罗列**：只取 top-2，强度只保留 1 位小数
 *  2. **正负并存时负面主导**（directive 取负面那条）—— 防"笑着说气话"
 *  3. 全部 < [MIN_SHOW] 或他为 normal → 对应行整段不出现（一个多余的字都不注入）
 */
object EmotionPhrase {

    /** 显示/注入阈值。 */
    const val MIN_SHOW = 0.2

    /** 双项并存阈值。 */
    const val TWO_MIN = 0.35

    /** 主项阈值（决定 directive 用谁、颜文字用谁）。 */
    const val DOMINANT = 0.5

    /** 他的情绪低于这个值就别提（normal 且 0.3 以下不注入）。 */
    const val HIS_MIN = 0.3

    private val LABEL = mapOf(
        Emotion.HAPPY to "开心",
        Emotion.EXCITED to "兴奋",
        Emotion.PROUD to "得意",
        Emotion.MISSING to "想你",
        Emotion.LOW to "低落",
        Emotion.HURT to "委屈",
        Emotion.ANNOYED to "烦躁",
        Emotion.GUILTY to "心虚",
    )

    /** 九档 directive（每句 ≤40 字、可直接执行）。 */
    private val DIRECTIVE = mapOf(
        Emotion.HAPPY to "语气轻快，可以多接一句玩笑，句尾带点笑意。",
        Emotion.EXCITED to "话变多，主动分享，节奏快一点；但别刷屏。",
        Emotion.PROUD to "嘴上可以小得意，允许自夸一句；不贬低他。",
        Emotion.MISSING to "语气软一点。",
        Emotion.LOW to "句子短，少打趣，不主动提要求；允许话停在一半。",
        // 委屈（P1）：只「软」，不撒娇、不示弱 —— 撒娇版见 ALLOW_AFFECTION（P3）
        Emotion.ANNOYED to "话更短、直给；可以吐槽事件本身，不阴阳他。",
        Emotion.GUILTY to "先认下，别辩解；一两句就够，随后用行动补偿。",
    )

    /**
     * P3 已开启（2026-11-21）：撒娇**总闸**打开。
     *
     * 但真正能不能撒娇由 [RelationshipStage.allowsAffection] 决定（只有"亲近"才行）。
     * 这个常量留作全局 kill switch：改回 false 就彻底回到「只软不撒娇」。
     */
    const val ALLOW_AFFECTION = true

    /** P3 的委屈文案（撒娇版）。P1 不生效。 */
    private const val HURT_AFFECTION = "语气软一点，可以跟他撒撒娇；别得寸进尺、别翻旧账。"

    /** P1 的委屈文案：只软，不撒娇、不示弱。 */
    private const val HURT_SOFT = "语气软一点。"

    /** 对他的通用应对（不按 3 档穷举，避免文案膨胀）。 */
    private const val HIS_RULE = "先接住他，别追问、别打趣。"

    fun label(emotion: String?): String = LABEL[Emotion.normalize(emotion) ?: ""] ?: "平静"

    fun directive(emotion: String?, allowAffection: Boolean = ALLOW_AFFECTION): String {
        val e = Emotion.normalize(emotion) ?: return "自然平和，像平时一样。"
        if (e == Emotion.HURT) return if (allowAffection) HURT_AFFECTION else HURT_SOFT
        return DIRECTIVE[e] ?: "自然平和，像平时一样。"
    }

    /**
     * 渲染整块（可能只有一行、也可能是 null）。
     *
     * @param hers   已衰减后的情绪 map
     * @param trigger 诱因（空串不带）
     * @param allowAffection P3 起：委屈时能否撒娇（P1 恒 false，见 ALLOW_AFFECTION）
     */
    fun render(
        hers: Map<String, Double>,
        trigger: String,
        hisMood: String = "normal",
        hisIntensity: Double = 0.0,
        allowAffection: Boolean = ALLOW_AFFECTION,
    ): String? {
        val lines = ArrayList<String>(2)

        buildHersLine(hers, trigger, allowAffection)?.let { lines += it }
        buildHisLine(hisMood, hisIntensity)?.let { lines += it }

        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    private fun buildHersLine(
        hers: Map<String, Double>,
        trigger: String,
        allowAffection: Boolean,
    ): String? {
        val sorted = hers.entries
            .filter { it.value >= MIN_SHOW }
            .sortedByDescending { it.value }
            .take(2)
        if (sorted.isEmpty()) return null

        val first = sorted[0]
        val second = sorted.getOrNull(1)?.takeIf { it.value >= TWO_MIN }

        // 正负并存 → 负面主导（directive 用负面那条）
        val hasPos = sorted.any { it.key !in Emotion.NEGATIVE }
        val hasNeg = sorted.any { it.key in Emotion.NEGATIVE }
        val directiveKey = if (hasPos && hasNeg) {
            (sorted.firstOrNull { it.key in Emotion.NEGATIVE } ?: first).key
        } else {
            first.key
        }

        val phrase = when {
            second == null -> "有点${label(first.key)}（${fmt(first.value)}）"
            hasPos && hasNeg -> "${label(first.key)}但还有点${label(second.key)}（${fmt(first.value)}/${fmt(second.value)}）"
            else -> "有点${label(first.key)}又有点${label(second.key)}（${fmt(first.value)}/${fmt(second.value)}）"
        }

        val trig = EmotionMerge.trimTrigger(trigger)
        val head = if (trig.isNotBlank()) "【我这会儿】$phrase：$trig。" else "【我这会儿】$phrase。"
        return head + directive(directiveKey, allowAffection)
    }

    private fun buildHisLine(mood: String, intensity: Double): String? {
        val m = mood.trim().lowercase()
        if (m == "normal" || m.isBlank()) return null
        if (intensity < HIS_MIN) return null
        val label = when (m) {
            "happy" -> "开心"
            "low" -> "低落"
            else -> return null
        }
        return "【他这会儿】有点$label（${fmt(intensity)}）。$HIS_RULE"
    }

    /** 强度统一 1 位小数。 */
    fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}