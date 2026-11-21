package com.companion.chat.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 结构化输出（json_object）安全解析。任务 1.2 增补（§3.3 解析失败 fallback）
 *
 * 约定（调用方遵守）：
 * - 解析失败（模型没吐合法 JSON / 被截断）→ 返回 null。
 * - mood 更新（任务 5.1）：null 时保持原 mood 不变（不写入）。
 * - 记忆提取（任务 2.2）：null 时本次跳过（不落库、不覆盖旧记忆）。
 *
 * 这样模型偶发不返回 JSON 时不会污染已有状态，也不会崩。
 */
object JsonExtract {

    /** 解析成 JSON 对象；失败返回 null（绝不抛异常）。 */
    fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            val el = JsonParser.parseString(raw)
            if (el.isJsonObject) el.asJsonObject else null
        } catch (_: Exception) {
            null
        }
    }

    /** 取字符串字段，缺失/类型不符返回 null。 */
    fun string(obj: JsonObject?, key: String): String? {
        val v = obj?.get(key) ?: return null
        return if (v.isJsonPrimitive) v.asString else null
    }
}
