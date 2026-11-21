package com.companion.chat.data.tts

/**
 * 官方 TTS 计费字符分片。任务 4.1
 *
 * 官方单次请求 ≤10000 计费字符，中文 1 字算 2、其它字符算 1。
 * 这里按「计费单位」切分，默认上限 9000（留安全余量），保证每片都不超限。
 */
object TtsTextChunker {

    const val DEFAULT_MAX_BILLABLE = 9000

    fun split(text: String, maxBillable: Int = DEFAULT_MAX_BILLABLE): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var units = 0
        for (ch in text) {
            val c = billableOf(ch)
            if (units + c > maxBillable && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
                units = 0
            }
            sb.append(ch)
            units += c
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 计费单位：CJK 汉字算 2，其它算 1。 */
    fun billableOf(ch: Char): Int = if (isCjk(ch)) 2 else 1

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||   // CJK 统一表意文字
            (code in 0x3400..0x4DBF) ||      // 扩展 A
            (code in 0xF900..0xFAFF) ||      // 兼容表意
            (code in 0x3000..0x303F)         // CJK 标点
    }
}
