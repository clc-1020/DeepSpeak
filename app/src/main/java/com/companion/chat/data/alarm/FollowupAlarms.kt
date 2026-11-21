package com.companion.chat.data.alarm

/**
 * 追问闹钟薄封装（V2.2.2）。只依赖 [AlarmScheduling] + [AlarmCodes]，不需要 Context。
 *
 * requestCode 一律走 [AlarmCodes]，不许自己算——公式改动会让已排的旧闹钟取消不掉。
 * `reminderId` 传真实 id（Long，非 null），不要用 -1L 哨兵。
 * taskDesc 用常量：Receiver 靠 EXTRA_REMINDER_ID 回查 DB 拿任务原文，不需要拼文案。
 */
class FollowupAlarms(private val scheduling: AlarmScheduling) {

    fun scheduleFollowup(reminderId: Long, at: Long) {
        scheduling.schedule(
            requestCode = AlarmCodes.followup(reminderId),
            triggerAtMillis = at,
            taskDesc = TASK_FOLLOWUP,
            reminderId = reminderId,
            kind = AlarmKind.FOLLOWUP
        )
    }

    fun scheduleTimeout(reminderId: Long, at: Long) {
        scheduling.schedule(
            requestCode = AlarmCodes.timeout(reminderId),
            triggerAtMillis = at,
            taskDesc = TASK_TIMEOUT,
            reminderId = reminderId,
            kind = AlarmKind.TIMEOUT
        )
    }

    fun cancelTimeout(reminderId: Long) {
        scheduling.cancel(AlarmCodes.timeout(reminderId))
    }

    fun cancelAll(reminderId: Long) {
        AlarmCodes.cancelAll(reminderId).forEach(scheduling::cancel)
    }

    /**
     * 常量对外公开（V2.2.4）：Receiver / `FollowupChainRunner` 要用 [FOLLOWUP_TIMEOUT_MS]
     * 排超时；taskDesc 仍是常量，谁都不许拿用户任务原文覆盖它（Receiver 靠 EXTRA_REMINDER_ID 回查）。
     */
    companion object {
        const val TASK_FOLLOWUP = "追问"
        const val TASK_TIMEOUT = "追问超时"

        /** 追问发出后多久没回应就算「超时」（计划书 §3.6 定死 30 分钟）。 */
        const val FOLLOWUP_TIMEOUT_MS = 30 * 60_000L
    }
}
