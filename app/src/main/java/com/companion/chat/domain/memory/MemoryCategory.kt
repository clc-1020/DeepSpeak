package com.companion.chat.domain.memory

/**
 * 长期记忆 category 标准化（纯函数，可 JVM 单测）。任务 2.3 审查修复·问题 4。
 *
 * LLM 生成的 category 可能是英文/中文/拼写变体（"allergy"/"过敏"/"preferrence"…），
 * 若直接用于「同类别才比较」，会导致本应去重的记忆因 category 不匹配而插入重复。
 * 这里把常见写法归一到固定枚举：fact / preference / allergy / relationship / other。
 *
 * 未知值保留小写原值（不强行塞 other，避免把不相关记忆堆进同一类互相误合并）。
 */
object MemoryCategory {
    const val FACT = "fact"
    const val PREFERENCE = "preference"
    const val ALLERGY = "allergy"
    const val RELATIONSHIP = "relationship"
    const val OTHER = "other"

    /** 提示词里给 LLM 的固定枚举（供 MemoryPromptBuilder 复用，保持单一真源）。 */
    val ALL: List<String> = listOf(FACT, PREFERENCE, ALLERGY, RELATIONSHIP, OTHER)

    fun normalize(raw: String?): String {
        val c = (raw ?: "").trim().lowercase()
        if (c.isEmpty()) return FACT
        when (c) {
            "allergy", "allergies", "allergic", "intolerance", "过敏", "过敏源", "忌口" -> return ALLERGY
            "preference", "preferences", "preferrence", "prefer", "like", "likes", "偏好", "喜好", "口味" -> return PREFERENCE
            "relationship", "relationships", "relation", "family", "friend", "friends", "关系", "家人", "朋友" -> return RELATIONSHIP
            "fact", "facts", "info", "information", "profile", "事实", "信息", "个人信息" -> return FACT
            "other", "misc", "其他", "其它" -> return OTHER
        }
        return when {
            c.contains("allerg") || c.contains("过敏") -> ALLERGY
            c.contains("prefer") || c.contains("偏好") || c.contains("喜好") -> PREFERENCE
            c.contains("relat") || c.contains("关系") -> RELATIONSHIP
            c.contains("fact") || c.contains("info") -> FACT
            else -> c // 未知保留小写原值
        }
    }
}
