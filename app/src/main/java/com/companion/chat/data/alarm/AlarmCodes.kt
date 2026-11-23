package com.companion.chat.data.alarm

/**
 * 闹钟用途（V2.1.5 起）。
 *
 * 每个 kind 决定两件事：
 * 1. [AlarmScheduling.schedule] 的 `kind` 参数 → [AlarmScheduler] 选 API：
 *    用户提醒类用 `setAlarmClock`（状态栏闹钟图标符合预期）；环境检查用
 *    `setAndAllowWhileIdle`（每小时一次，若刷 `setAlarmClock` 会亮一整夜图标）
 * 2. [com.companion.chat.worker.ReminderAlarmReceiver] 收到后按 kind 分流
 *
 * [INSTRUCTION] 是默认值：现有 3 参调用点（用户指令提醒）零改动。
 */
enum class AlarmKind { INSTRUCTION, FOLLOWUP, TIMEOUT, ENV_CHECK, DAILY_TASK }

/**
 * requestCode 分配表（唯一出处，带单测）。任务 3.5
 *
 * 公式定死：一旦改动，**已排的旧闹钟会取消不掉**（`PendingIntent` 按 requestCode 匹配）。
 * 首条提醒必须沿用 `reminderId.toInt()` 的现有约定，不能改。
 */
object AlarmCodes {

    /** 环境检查的固定 requestCode（每小时排一次，不占 reminderId 空间）。 */
    const val ENV_CHECK: Int = 999_999

    /** 首次 / 指令提醒：沿用现有约定。 */
    fun first(reminderId: Long): Int = reminderId.toInt()

    /** 追问：`1_000_000 + id`。 */
    fun followup(reminderId: Long): Int = 1_000_000 + reminderId.toInt()

    /** 超时：`2_000_000 + id`。 */
    fun timeout(reminderId: Long): Int = 2_000_000 + reminderId.toInt()

    /**
     * 每日任务（2026-11-23）：`3_000_000 + 任务id`。
     * 用独立号段，绝不与上面三类提醒 id 派生码 / 环境检查 999_999 撞车。
     */
    fun dailyTask(taskId: Long): Int = 3_000_000 + taskId.toInt()

    /** 三个提醒 code 成套取消，漏一个就留僵尸闹钟。 */
    fun cancelAll(reminderId: Long): List<Int> =
        listOf(first(reminderId), followup(reminderId), timeout(reminderId))
}