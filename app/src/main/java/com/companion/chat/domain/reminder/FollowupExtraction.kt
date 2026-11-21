package com.companion.chat.domain.reminder

import com.companion.chat.data.reminder.FollowupPortImpl
import com.companion.chat.domain.JsonExtract

/**
 * 追问任务的结构化提取结果（V2.2.3，纯数据）。
 *
 * @param task           任务描述（必填）
 * @param intervalMinutes 追问间隔（分钟），1..1440
 * @param stopCondition  停止条件（语义描述），可空
 * @param startHint      首次触发时间的自然语言提示（如「今晚8点」），可空
 */
data class FollowupExtraction(
    val task: String,
    val intervalMinutes: Int,
    val stopCondition: String?,
    val startHint: String?
)

/**
 * 模型 JSON → [FollowupExtraction]（纯 JVM，容错解析，绝不外抛）。
 *
 * 契约：
 * ```json
 * {"is_followup":true,"task":"背30个单词","start":"今晚8点","interval_minutes":15,"stop_condition":"用户说背完了"}
 * ```
 *
 * 容错沿用 mood / 记忆：取首 `{` 到末 `}`，容忍 ```json 包裹和前后说明。
 */
object FollowupExtractionParser {

    /** 间隔上限（分钟）：模型给 100000 会导致永久静默，必须钳住。 */
    private const val MAX_INTERVAL_MINUTES = 1440

    fun parse(raw: String): FollowupExtraction? {
        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val obj = JsonExtract.parseObject(raw.substring(start, end + 1)) ?: return null

            // is_followup：布尔 true 或字符串 "true" 都算
            val flag = when (val v = obj.get("is_followup")) {
                null -> false
                else -> when {
                    v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                    v.isJsonPrimitive && v.asJsonPrimitive.isString ->
                        v.asString.equals("true", ignoreCase = true)
                    else -> false
                }
            }
            if (!flag) return null

            val task = JsonExtract.string(obj, "task")?.trim().orEmpty()
            if (task.isEmpty()) return null

            val parsedInterval = obj.get("interval_minutes")?.let { v ->
                when {
                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asInt
                    v.isJsonPrimitive && v.asJsonPrimitive.isString -> v.asString.trim().toIntOrNull()
                    else -> null
                }
            }
            val minutes = when {
                parsedInterval == null || parsedInterval <= 0 -> FollowupPortImpl.DEFAULT_INTERVAL_MINUTES
                parsedInterval > MAX_INTERVAL_MINUTES -> MAX_INTERVAL_MINUTES
                else -> parsedInterval
            }

            FollowupExtraction(
                task = task,
                intervalMinutes = minutes,
                stopCondition = JsonExtract.string(obj, "stop_condition")?.trim()?.ifEmpty { null },
                startHint = JsonExtract.string(obj, "start")?.trim()?.ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }
}
