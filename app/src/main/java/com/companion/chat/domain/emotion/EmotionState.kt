package com.companion.chat.domain.emotion

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 他的情绪（P1 保持 3 档 + 强度，用于「怎么回他」）。 */
data class HisState(
    val mood: String = "normal",
    val intensity: Double = 0.0,
    val since: Long = 0L,
)

/** 她的情绪（可共存向量 + 诱因 + 起始时刻）。 */
data class HersState(
    val emotions: Map<String, Double> = emptyMap(),
    val trigger: String = "",
    val since: Long = 0L,
) {
    /** 主导情绪（强度最高）；空 map → null（= 平静）。 */
    fun dominant(): Pair<String, Double>? =
        emotions.maxByOrNull { it.value }?.let { it.key to it.value }
}

/**
 * 情绪状态的持久化形态（`app_state["emotion_state"]`，JSON）。**零迁移**。
 *
 * [updatedAt] 是**唯一的时间基准**：读状态时用 `now - updatedAt` 算衰减，
 * 所以「衰减」不需要定时任务，也不需要在每一轮落库。
 */
data class EmotionState(
    val his: HisState = HisState(),
    val hers: HersState = HersState(),
    val updatedAt: Long = 0L,
) {

    fun toJson(): String {
        val root = JsonObject()
        root.add("his", JsonObject().apply {
            addProperty("mood", his.mood)
            addProperty("intensity", his.intensity)
            addProperty("since", his.since)
        })
        root.add("hers", JsonObject().apply {
            val em = JsonObject()
            hers.emotions.forEach { (k, v) -> em.addProperty(k, v) }
            add("emotions", em)
            addProperty("trigger", hers.trigger)
            addProperty("since", hers.since)
        })
        root.addProperty("updated_at", updatedAt)
        return root.toString()
    }

    companion object {
        /** 解析失败一律返回 null（调用方用默认状态兜底，绝不影响主对话）。 */
        fun fromJson(raw: String?): EmotionState? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JsonParser.parseString(raw)
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject

                val hisObj = obj.getAsJsonObject("his")
                val his = HisState(
                    mood = hisObj?.get("mood")?.takeIf { it.isJsonPrimitive }?.asString ?: "normal",
                    intensity = hisObj?.get("intensity")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0,
                    since = hisObj?.get("since")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )

                val hersObj = obj.getAsJsonObject("hers")
                val emObj = hersObj?.getAsJsonObject("emotions")
                val raw = LinkedHashMap<String, Double>()
                emObj?.entrySet()?.forEach { (k, v) ->
                    val d = runCatching { v.asDouble }.getOrNull() ?: return@forEach
                    raw[k] = d
                }
                val hers = HersState(
                    emotions = Emotion.normalizeMap(raw),
                    trigger = hersObj?.get("trigger")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    since = hersObj?.get("since")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )

                EmotionState(
                    his = his,
                    hers = hers,
                    updatedAt = obj.get("updated_at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )
            }.getOrNull()
        }
    }
}