package com.companion.chat.data.daily

import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.AlarmScheduling
import com.companion.chat.data.local.dao.DailyTaskDao
import com.companion.chat.data.local.entity.DailyTaskEntity
import com.companion.chat.domain.daily.DailyTaskSchedule
import java.time.ZoneId

/**
 * 每日任务的闹钟编排（2026-11-23）。
 *
 * 每条启用的任务排一个「每天一次」的精确闹钟（[AlarmKind.DAILY_TASK] → setAlarmClock）；
 * 到点由 [com.companion.chat.worker.ReminderAlarmReceiver] 续排下一天 + 触发 [DailyTaskWorker]。
 *
 * 三处调用：
 * 1. [com.companion.chat.CompanionApp.onCreate] / 开机恢复 / 改时间恢复：全量 [syncAll]（幂等）；
 * 2. UI 增删改 / 开关任务：单条 [scheduleTask] / [cancelTask]。
 */
class DailyTaskScheduler(
    private val scheduler: AlarmScheduling,
    private val dao: DailyTaskDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * 全量同步：被禁用的取消闹钟、启用的按「下一次」重排。
     * 幂等——重复排同一个 requestCode 只是覆盖。开机/启动/改时间都调它来兜底「闹钟被系统清掉」。
     */
    suspend fun syncAll() {
        val all = dao.getAll()
        val enabled = all.filter { it.enabled }
        val enabledIds = enabled.map { it.id }.toSet()
        // 先取消已禁用/被删的（cancel 幂等，不存在也无害）
        all.filter { it.id !in enabledIds }.forEach { t ->
            runCatching { scheduler.cancel(AlarmCodes.dailyTask(t.id)) }
        }
        enabled.forEach { scheduleTask(it) }
    }

    /** 排单条任务的下一次；[DailyTaskEntity.enabled]=false 等价于取消。 */
    fun scheduleTask(task: DailyTaskEntity) {
        if (!task.enabled) {
            cancelTask(task.id)
            return
        }
        val at = DailyTaskSchedule.nextOccurrence(task.hour, task.minute, now(), zone)
        scheduler.schedule(
            requestCode = AlarmCodes.dailyTask(task.id),
            triggerAtMillis = at,
            taskDesc = task.text,
            reminderId = task.id,
            kind = AlarmKind.DAILY_TASK
        )
    }

    fun cancelTask(taskId: Long) {
        runCatching { scheduler.cancel(AlarmCodes.dailyTask(taskId)) }
    }
}
