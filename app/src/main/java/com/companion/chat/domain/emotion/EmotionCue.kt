package com.companion.chat.domain.emotion

/**
 * 「这一轮值不值得调模型判情绪」的闸门（纯规则，零成本，2026-11-21）。
 *
 * 为什么需要它：情绪要跟得上对话就得每轮可判，但每轮都调模型 = 每轮多花钱。
 * 用零成本的规则先筛，只有「明显有情绪信号」的轮次才调 —— 实测这类轮次约占 20~30%。
 *
 * ⚠️ 判定宽一点没关系（多调几次模型），但**漏判才是问题**（她该有反应却没反应）。
 * 所以关键词表宁可长，也不要为了省钱收窄到只剩感叹号。
 */
object EmotionCue {

    /** 连续 2 个以上感叹/问号：`！！` `？？` `!?` */
    private val STRONG_PUNCT = Regex("[!！?？]{2,}")

    /**
     * 情绪词表。刻意**不含**「你」这种高频虚词（否则命中率接近 100%，闸门就失效了）。
     */
    private val KEYWORDS = listOf(
        // 夸
        "厉害", "棒", "牛", "谢谢", "喜欢", "爱你", "想你", "好听", "好看", "可爱", "有用",
        // 骂/不满
        "傻", "笨", "烦", "滚", "讨厌", "废物", "垃圾", "气死", "无语", "算了", "随便",
        // 状态
        "累", "困", "哭", "难过", "开心", "高兴", "伤心", "焦虑", "压力", "崩", "难受", "无聊",
        // 关系信号
        "走了", "再见", "晚安", "在吗", "陪我", "不理我", "不理你", "别走", "分手",
        // 撒娇/试探
        "哼", "嘛", "啦", "好不好", "行不行", "爱不爱",
        // 测试语义（本项目的真实高频词）
        "测试", "试试", "试一下",
    )

    /**
     * @param text 最后一条用户消息原文
     * @param prevLen 上一条用户消息长度（用于「长度突变」判定；0 = 不知道）
     */
    fun isStimulus(text: String, prevLen: Int = 0): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        if (STRONG_PUNCT.containsMatchIn(t)) return true
        if (KEYWORDS.any { t.contains(it) }) return true
        if (hasEmoji(t)) return true
        if (prevLen > 0 && t.length >= 8 && t.length >= prevLen * 1.8) return true
        return false
    }

    /** emoji / 颜文字区域（含常见符号区段）。 */
    private fun hasEmoji(s: String): Boolean = s.codePoints().anyMatch { cp ->
        cp in 0x1F300..0x1FAFF || // 表情、符号
            cp in 0x2600..0x27BF || // 杂项符号、装饰
            cp in 0xFF5E..0xFF5E || // 全角波浪（颜文字高频）
            cp >= 0x1F000
    }
}