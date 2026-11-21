package com.companion.chat.domain.memory

import com.companion.chat.domain.JsonExtract
import com.google.gson.JsonObject

/** 一条长期记忆（含分类，如 fact / preference / allergy / relationship）。 */
data class LongMemory(
    val content: String,
    val category: String = "fact"
)

/** 一次记忆提取的结果：短期 / 长期 / 每日日志。 */
data class MemoryExtraction(
    val short: List<String> = emptyList(),
    val long: List<LongMemory> = emptyList(),
    val daily: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = short.isEmpty() && long.isEmpty() && daily.isEmpty()
}

/**
 * 记忆提取 JSON 解析（纯函数，可 JVM 单测）。任务 2.2
 *
 * 期望模型在 json_object 模式下返回：
 * `{"short":["..."],"long":[{"content":"...","category":"allergy"}],"daily":["..."]}`
 *
 * 容错策略（模型输出不总是规整）：
 * - `short`/`daily`：元素可为字符串，或对象（取 content/text/value/event 之一）。
 * - `long`：元素可为字符串（默认类别）或对象（content + category/type）。
 * - 缺失/类型不符的字段按空数组处理；空串丢弃；同一列表内去重（保序）。
 * - 顶层不是 JSON 对象 → 返回 null（调用方据此"本次跳过、不动旧记忆"）。
 */
object MemoryExtractionParser {

    fun parse(raw: String?, defaultCategory: String = "fact"): MemoryExtraction? {
        val obj = JsonExtract.parseObject(raw) ?: return null
        return MemoryExtraction(
            short = readStrings(obj, "short"),
            long = readLong(obj, "long", defaultCategory),
            daily = readStrings(obj, "daily")
        )
    }

    private fun readStrings(obj: JsonObject, key: String): List<String> {
        val arr = obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (e in arr) {
            val s = when {
                e.isJsonPrimitive -> e.asString
                e.isJsonObject -> pickString(e.asJsonObject)
                else -> null
            }?.trim().orEmpty()
            if (s.isNotEmpty() && seen.add(s)) out.add(s)
        }
        return out
    }

    private fun readLong(obj: JsonObject, key: String, defaultCategory: String): List<LongMemory> {
        val arr = obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<LongMemory>()
        val seen = HashSet<String>()
        for (e in arr) {
            val content: String?
            val category: String
            when {
                e.isJsonPrimitive -> {
                    content = e.asString
                    category = defaultCategory
                }
                e.isJsonObject -> {
                    val o = e.asJsonObject
                    content = pickString(o)
                    category = pickFirst(o, "category", "type").orEmpty().ifBlank { defaultCategory }
                }
                else -> {
                    content = null
                    category = defaultCategory
                }
            }
            val c = content?.trim().orEmpty()
            if (c.isNotEmpty() && seen.add(c)) out.add(LongMemory(c, category.trim().ifBlank { defaultCategory }))
        }
        return out
    }

    private fun pickString(o: JsonObject): String? = pickFirst(o, "content", "text", "value", "event")

    private fun pickFirst(o: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.get(k) ?: continue
            if (v.isJsonPrimitive) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }
}
