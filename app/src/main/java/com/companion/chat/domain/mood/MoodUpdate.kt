package com.companion.chat.domain.mood

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.companion.chat.domain.emotion.Emotion
import com.companion.chat.domain.reminder.FollowupStatus

/** 用户心情取值（任务 5.1）。 */
object Mood {
    const val NORMAL = "normal"
    const val HAPPY = "happy"
    const val LOW = "low"
    val ALL = listOf(NORMAL, HAPPY, LOW)
}

/** 一次 mood 判定结果（V2.2.2 增补追问状态；老调用点零改动靠默认值）。 */
data class MoodUpdate(
    val mood: String,
    val reason: String,
    val followupStatus: FollowupStatus? = null,
    val followupReason: String = "",
    /**
     * 多链（2026-11-20）：`{"followups":[{"id":3,"status":"completed"},…]}` 解析结果。
     *
     * 单链时代模型只输出一个 `followup_status`（无法区分是哪条链），多链并存后必须带上 id，
     * 否则「我说喝完了」会被随便套到某一条上。[followupStatus] 保留做**单链向下兼容**。
     */
    val followupStatuses: Map<Long, FollowupStatus> = emptyMap(),

    /**
     * 她的情绪变化项（2026-11-21 情绪系统 P1）：`{"hurt":0.6,"missing":0.3}`。
     *
     * **只含"这次有变化"的 1~3 项**，不是全量向量 —— 全量会让模型在相近情绪间乱抖。
     * 键已归一到 [com.companion.chat.domain.emotion.Emotion] 的 8 个英文枚举；未知键丢弃。
     */
    val emotions: Map<String, Double> = emptyMap(),

    /** 引起变化的那件事（一句话，≤24 字）。负面情绪**没有它就不采信**。 */
    val emotionTrigger: String = "",
)

/**
 * mood JSON 解析（纯函数，可 JVM 单测）。任务 5.1 / V2.2.2
 *
 * 容错：截取首尾花括号之间的 JSON（模型可能加 ```json 代码块/前后说明）；mood 必须在
 * normal/happy/low 内，否则返回 null（调用方保持原 mood）。
 * followup_status 未知/缺失 → null（不让整条 parse 失败，mood 有效就保留 mood）。
 */
object MoodUpdateParser {

    private val gson = Gson()

    fun parse(raw: String): MoodUpdate? {
        if (raw.isBlank()) return null
        val json = extractJsonObject(raw) ?: return null
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
            val mood = obj.get("mood")?.asString?.trim()?.lowercase() ?: return null
            if (mood !in Mood.ALL) return null
            val reason = runCatching { obj.get("reason")?.asString?.trim().orEmpty() }.getOrDefault("")
            val followupStatus = runCatching {
                FollowupStatus.fromRaw(obj.get("followup_status")?.asString)
            }.getOrDefault(null)
            val followupReason = runCatching {
                obj.get("followup_reason")?.asString?.trim().orEmpty()
            }.getOrDefault("")
            // 多链：followups = [{"id":3,"status":"completed"}, …]。id/status 任缺则跳过该条，
            // 不因单个坏元素让整条解析失败（mood 有效就保留 mood）。重复 id 取第一次。
            val statuses = LinkedHashMap<Long, FollowupStatus>()
            runCatching {
                val arr = obj.getAsJsonArray("followups") ?: return@runCatching
                arr.forEach { el ->
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val id = runCatching { o.get("id")?.asLong }.getOrNull() ?: return@forEach
                    val st = FollowupStatus.fromRaw(
                        runCatching { o.get("status")?.asString }.getOrNull()
                    ) ?: return@forEach
                    if (id > 0L && !statuses.containsKey(id)) statuses[id] = st
                }
            }
            // 情绪变化项（2026-11-21）：只取模型报的那几项；中文别名归一；越界/NaN 丢弃
            val emotions = LinkedHashMap<String, Double>()
            var trigger = ""
            runCatching {
                trigger = obj.get("trigger")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val em = obj.get("emotions")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching
                em.entrySet().forEach { (key, v) ->
                    val d = runCatching { v.asDouble }.getOrNull() ?: return@forEach
                    if (d.isNaN() || d <= 0.0) return@forEach
                    val norm = Emotion.normalize(key) ?: return@forEach
                    emotions[norm] = maxOf(emotions[norm] ?: 0.0, d.coerceAtMost(1.0))
                }
            }
            MoodUpdate(mood, reason, followupStatus, followupReason, statuses, emotions, trigger)
        } catch (_: Exception) {
            null
        }
    }

    /** 取首个 '{' 到末个 '}' 之间的内容，容忍模型额外包裹。 */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}

/** 对话轮数计数 → 是否该更新 mood（每 [UPDATE_EVERY_TURNS] 轮一次）。任务 5.1 */
object MoodCounter {
    const val UPDATE_EVERY_TURNS = 5

    fun shouldUpdate(turnCount: Int, every: Int = UPDATE_EVERY_TURNS): Boolean =
        every > 0 && turnCount > 0 && turnCount % every == 0
}
