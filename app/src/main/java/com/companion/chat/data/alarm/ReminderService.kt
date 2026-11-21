package com.companion.chat.data.alarm

import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.domain.alarm.ReminderIntent
import com.companion.chat.domain.alarm.ReminderParser
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒闹钟编排：把「用户话 → 是否提醒意图 → 解析时间 → **落库** → 设精确闹钟」。任务 3.3 / B9
 *
 * 只负责**设闹钟**；真正到点投队列在 [com.companion.chat.worker.ReminderAlarmReceiver]。
 *
 * ### 为什么必须落库（B9 修复）
 * 原实现只 `scheduler.schedule(...)`、**从不写 `reminders` 表**，于是：
 *  - 提醒只活到「进程结束」——重启设备 / App 被系统杀掉即彻底消失；
 *  - `BootReceiver` 的重启恢复路径读 `getPending()` 恒为空 → 恢复的是不存在的东西。
 * 现在建行拿到 id，恢复路径才有数据源。
 *
 * ### 为什么 requestCode 由 id 派生（而不是触发时刻）
 * 恢复侧（`CompanionApp.restoreAlarmsAfterBoot`）只拿得到行（id/triggerTime），
 * 两边必须用**同一个** requestCode 才能命中同一个 `PendingIntent`：
 *  - 同一条提醒 → 同一个闹钟（重复设是覆盖，不堆叠）；
 *  - 取消入口将来能用 `AlarmCodes.cancelAll(id)` 一套取消干净。
 *
 * 建行失败（DB 异常）时才降级用触发时刻派生，并且**不传 reminderId**——这条提醒
 * 没有行、重启后无从恢复，是刻意的降级而不是正常路径。
 *
 * @param saveReminder 落库并返回新行 id；返回 0 或抛异常即视为「没落库」（降级）。
 *   注入 lambda 而不是直接接 `ReminderDao`：保持本类纯 JVM 可测（无需 Room/Robolectric）。
 */
class ReminderService(
    private val scheduler: AlarmScheduling,
    private val saveReminder: suspend (type: String, taskDesc: String, triggerAtMillis: Long) -> Long =
        { _, _, _ -> 0L },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val referenceDate: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    data class ScheduledReminder(
        val triggerAtMillis: Long,
        val taskDesc: String,
        val requestCode: Int,
        /** 落库得到的行 id；0 = 未落库（降级，重启后不可恢复）。 */
        val reminderId: Long = 0L
    )

    /**
     * 若 [text] 是提醒指令且能解析出时间 → 落库 + 设精确闹钟并返回结果；否则返回 null（不排）。
     *
     * `suspend` 是因为要先落库拿 id。调用方（`ChatViewModel.handleSend`）本来就在 IO 协程里，
     * 源兼容、无需改动。
     */
    suspend fun scheduleFromText(text: String): ScheduledReminder? {
        if (!ReminderIntent.matches(text)) return null
        val draft = ReminderParser.parse(text, referenceDate(), zone, now()) ?: return null

        // 落库失败不能连带丢掉「提醒」本身：仍要响这一次，只是不可恢复（降级并记日志）
        val reminderId = runCatching {
            saveReminder(ReminderType.INSTRUCTION, draft.taskDesc, draft.triggerAtMillis)
        }.getOrDefault(0L)

        val requestCode = if (reminderId > 0L) {
            AlarmCodes.first(reminderId)
        } else {
            derivedRequestCode(draft.triggerAtMillis)
        }
        scheduler.schedule(
            requestCode = requestCode,
            triggerAtMillis = draft.triggerAtMillis,
            taskDesc = draft.taskDesc,
            reminderId = reminderId.takeIf { it > 0L }
        )
        return ScheduledReminder(draft.triggerAtMillis, draft.taskDesc, requestCode, reminderId)
    }

    /**
     * 降级 requestCode（未落库时）：由触发时刻派生。
     * 保留旧公式 `triggerTime % Int.MAX_VALUE`——已排的旧闹钟按这个 requestCode 匹配，
     * 公式一改就取消不掉（但这条路径不会产生「可恢复」的行，不参与 `cancelAll`）。
     */
    private fun derivedRequestCode(triggerAtMillis: Long): Int =
        (triggerAtMillis % Int.MAX_VALUE).toInt()
}