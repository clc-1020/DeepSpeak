package com.companion.chat.domain.reminder

/**
 * 追问意图闸门（V2.2.3，纯函数）。
 *
 * 为什么要有它：`ChatViewModel` 每轮都会调一次 `extractAndSchedule`。没有闸门就是每轮多一次
 * API 调用（记忆提取已经每轮一次），费用直接翻倍。这里先零成本判断「要不要尝试解析」——
 * 与 `ReminderIntent`（任务 3.3）同理：费用保护，不是新功能。
 *
 * 判定顺序：trim → 空白/否定词直接 false → 正向线索。
 */
object FollowupIntent {

    /** 命中任一才算「想让我监督/提醒我坚持」。 */
    private val CUES = listOf(
        "监督我", "管着我", "盯着我", "督促我", "催我", "看着我",
        "提醒我每天", "每天提醒我", "每天叫我", "叫我坚持"
    )

    /** 命中任一直接否掉（即使同时含正向线索）。与 CUES 一一对应，别只补「监督」。 */
    private val NEGATIONS = listOf(
        "别监督我", "不用提醒我", "不要催我", "别催我", "别再提醒",
        "别看着我", "别盯着我", "别管着我", "不用管我"
    )

    fun matches(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        if (NEGATIONS.any { s.contains(it) }) return false
        if (CUES.any { s.contains(it) }) return true
        // 「坚持」+「每天」组合：允许中间隔字（例：每天都要坚持下去）
        return s.contains("坚持") && s.contains("每天")
    }
}
