package com.companion.chat.data.embedding

import java.text.Normalizer

/** BERT 分词结果：喂给 ONNX 模型的三个 int64 张量（all-MiniLM-L6-v2）。 */
data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
)

/**
 * BERT WordPiece 分词器（uncased，兼容 all-MiniLM-L6-v2）。任务 2.1
 *
 * 纯 Kotlin，不依赖 ONNX / Android，可在 JVM 单测。行为对齐 HuggingFace
 * `BertTokenizer(do_lower_case=true)`：
 *  1) 清洗控制字符、按空白切分；
 *  2) 每块转小写、去重音符（strip accents）；
 *  3) 把标点符号、CJK 单字拆成独立 token；
 *  4) 每个 token 贪心最长匹配 WordPiece（续接子词前缀 "##"）；
 *  5) 首尾加 [CLS]/[SEP]，超长截断到 maxLen。
 *
 * 注意：不追求与 HF 逐 token 完全一致，但要保证语义 embedding 达到验收阈值
 * （同句 >0.99 / 近义 >0.85 / 无关 <0.5）。
 */
class BertTokenizer(
    private val vocab: Map<String, Int>,
    private val maxLen: Int = 256
) {

    private val unkId = vocab["[UNK]"] ?: 100
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102

    /** 主入口：文本 → 三个张量（无 padding，长度可变，配合 ONNX 动态轴）。 */
    fun tokenize(text: String): TokenizedInput {
        val ids = ArrayList<Int>()
        val capacity = (maxLen - 2).coerceAtLeast(1) // 预留 [CLS]/[SEP]
        outer@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (ids.size >= capacity) break@outer
                ids.add(id)
            }
        }
        val len = ids.size + 2
        val inputIds = LongArray(len)
        val attn = LongArray(len) { 1L }
        val types = LongArray(len) // 全 0
        inputIds[0] = clsId.toLong()
        for (i in ids.indices) inputIds[i + 1] = ids[i].toLong()
        inputIds[len - 1] = sepId.toLong()
        return TokenizedInput(inputIds, attn, types)
    }

    /** 基础切分：清洗 → 按空白切 → 逐块（小写 / 去音符 / 拆标点 / CJK 单字）。 */
    fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() -> cleaned.append(' ')
                ch.code == 0 || ch.code == 0xFFFD || Character.isISOControl(ch) -> cleaned.append(' ')
                else -> cleaned.append(ch)
            }
        }
        // CJK 单字前后补空格，使每个汉字成为独立 token
        val spaced = StringBuilder(cleaned.length * 2)
        for (ch in cleaned) {
            if (isCjk(ch)) spaced.append(' ').append(ch).append(' ') else spaced.append(ch)
        }
        val out = ArrayList<String>()
        for (raw in spaced.toString().split(' ')) {
            if (raw.isEmpty()) continue
            val lower = stripAccents(raw.lowercase())
            for (part in splitOnPunctuation(lower)) {
                if (part.isNotEmpty()) out.add(part)
            }
        }
        return out
    }

    /** 去重音符：NFD 分解后删掉组合标记（Mn）。 */
    private fun stripAccents(s: String): String {
        if (s.isEmpty()) return s
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return nfd.replace(ACCENT_MARKS, "")
    }

    /** 把 token 中的标点拆成独立 token（BERT 定义）。 */
    private fun splitOnPunctuation(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in token) {
            if (isPunctuation(ch)) {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                out.add(ch.toString())
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 贪心最长匹配 WordPiece。整词无法切分时返回 [UNK]（与 HF 一致）。 */
    fun wordPiece(token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var curId = -1
            while (start < end) {
                val piece = token.substring(start, end)
                val candidate = if (start > 0) "##$piece" else piece
                val id = vocab[candidate]
                if (id != null) {
                    curId = id
                    break
                }
                end--
            }
            if (curId == -1) return listOf(unkId) // 整词无法切分
            out.add(curId)
            start = end
        }
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        val c = ch.code
        if ((c in 33..47) || (c in 58..64) || (c in 91..96) || (c in 123..126)) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        private val ACCENT_MARKS = Regex("\\p{M}+")

        /** 与 TtsTextChunker 一致的 CJK 判定（含 CJK 标点/假名/谚文）。 */
        fun isCjk(ch: Char): Boolean {
            val c = ch.code
            return (c in 0x4E00..0x9FFF) ||   // CJK 统一表意
                (c in 0x3400..0x4DBF) ||      // 扩展 A
                (c in 0xF900..0xFAFF) ||      // 兼容表意
                (c in 0x3000..0x303F) ||      // CJK 标点
                (c in 0x3040..0x30FF) ||      // 日文假名
                (c in 0xAC00..0xD7AF)         // 韩文谚文
        }
    }
}
