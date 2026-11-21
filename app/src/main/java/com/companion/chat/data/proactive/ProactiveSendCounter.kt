package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.google.gson.Gson
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日已发「自发」消息计数（供 3.4 每日上限用）。任务 3.5
 *
 * 存 `app_state[proactive_sent_count] = {"day":"2026-11-20","count":2}`；跨天自动归零。
 * 指令提醒/追问不计入（由送达层按类别决定是否调用 [increment]）。
 */
class ProactiveSendCounter(
    private val appStateDao: AppStateDao,
    private val todayProvider: () -> String = { LocalDate.now(ZoneId.systemDefault()).toString() }
) {
    private val gson = Gson()

    private data class DayCount(val day: String, val count: Int)

    /** 今日已发条数（无记录/跨天 → 0）。 */
    suspend fun countToday(): Int {
        val raw = appStateDao.get(AppStateEntity.KEY_PROACTIVE_SENT) ?: return 0
        val dc = try { gson.fromJson(raw, DayCount::class.java) } catch (_: Exception) { null } ?: return 0
        return if (dc.day == todayProvider()) dc.count else 0
    }

    /** 记一条已发（同天累加；跨天从 1 起）。 */
    suspend fun increment() {
        val next = DayCount(todayProvider(), countToday() + 1)
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_PROACTIVE_SENT, gson.toJson(next)))
    }
}