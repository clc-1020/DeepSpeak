package com.companion.chat.domain.emotion

/**
 * 情绪键与中文别名归一（2026-11-21，P1）。
 *
 * 为什么用英文枚举做存储键：模型每次可能写「委屈 / 受伤 / 被冷落」，中文当键会让去重与合并全部失效。
 * 归一之后库里只有 8 个稳定键，**平静 = 空 map**（不存键），所以「9 档」= 8 键 + 1 兜底语义。
 */
object Emotion {

    const val HAPPY = "happy"
    const val EXCITED = "excited"
    const val PROUD = "proud"
    const val MISSING = "missing"
    const val LOW = "low"
    const val HURT = "hurt"
    const val ANNOYED = "annoyed"
    const val GUILTY = "guilty"

    /** 可持久化的 8 个键（平静不在其中）。 */
    val ALL: List<String> = listOf(HAPPY, EXCITED, PROUD, MISSING, LOW, HURT, ANNOYED, GUILTY)

    /**
     * 负面集合。**只有这四个能带"她现在不好受"的语义**；
     * 它们受「总和 ≤0.9」「必须有 trigger」两道护栏约束。
     *
     * ⚠️ 这里刻意**没有**「对他愤怒」这一档 —— 负面只能指向事情或她自己。
     */
    val NEGATIVE: Set<String> = setOf(LOW, HURT, ANNOYED, GUILTY)

    /** 中文/英文别名的**只读**映射；键为归一后的枚举，值为可接受的写法。 */
    private val ALIASES: Map<String, String> = buildMap {
        listOf("happy", "开心", "高兴", "快乐", "愉快", "开心了").forEach { put(it, HAPPY) }
        listOf("excited", "兴奋", "激动", "上头", "来劲").forEach { put(it, EXCITED) }
        listOf("proud", "得意", "骄傲", "得瑟", "自豪").forEach { put(it, PROUD) }
        listOf("missing", "想你", "想念", "思念", "想他").forEach { put(it, MISSING) }
        listOf("low", "sad", "低落", "难过", "伤心", "沮丧", "疲惫", "失落").forEach { put(it, LOW) }
        listOf("hurt", "委屈", "受伤", "被冷落", "受伤了").forEach { put(it, HURT) }
        listOf("annoyed", "烦躁", "烦", "恼火", "不耐烦", "生气").forEach { put(it, ANNOYED) }
        listOf("guilty", "心虚", "内疚", "抱歉", "亏心").forEach { put(it, GUILTY) }
    }

    /**
     * 归一化。未知值返回 **null**（不塞 other，避免把不相关情绪堆进同一类互相误合并）。
     */
    fun normalize(raw: String?): String? {
        val k = raw?.trim()?.lowercase().orEmpty()
        if (k.isEmpty()) return null
        if (k in ALL) return k
        return ALIASES[k]
    }

    /** 归一化整个 map：丢弃未知键、按单值上限夹取、去掉 <=0 的项。 */
    fun normalizeMap(raw: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in raw) {
            val key = normalize(k) ?: continue
            if (v.isNaN() || v <= 0.0) continue
            val clamped = v.coerceAtMost(EmotionMerge.MAX_SINGLE)
            // 同一键重复出现（英文 + 中文别名）取较大值
            out[key] = maxOf(out[key] ?: 0.0, clamped)
        }
        return out
    }
}