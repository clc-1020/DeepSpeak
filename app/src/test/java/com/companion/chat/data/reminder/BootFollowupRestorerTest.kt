package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.FakeAlarmScheduling
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2.2.4 审查 R1：「恢复路径上过期追问行」必须有闹钟，两条路径（boot / force-stop）都要。
 *
 * 为什么非写不可：`CompanionApp` 里那两段是 Android Application 的副作用，JVM 测不到；而
 * 「丢掉过期行」的写法看起来完全合理（注释还写着「等 TIMEOUT 自己推进」），实际上
 * 重启/force-stop 之后 `AlarmManager` 全空、根本没有人会推进它 —— 该行永久 pending，
 * `anyPendingFollowup()` 恒真，之后所有新建监督任务被静默挡住。原 `BootRestorePlannerTest`
 * 只覆盖纯计划函数，覆盖不到这个副作用。
 *
 * 两类行的语义（混成一条都会出 bug）：
 *  | 行 | 判据 | 期望 |
 *  |---|---|---|
 *  | 已发过追问、等回复 | `attemptCount > 0` 且 `triggerTime` 过去 | 补 **TIMEOUT** 钟（绝对时刻），不进 pending |
 *  | 首钟就错过 | `attemptCount == 0` 且 `triggerTime` 过去 | **不进**补钟分支，留在 pending 里由 planner 重放第 1 条 |
 */
class BootFollowupRestorerTest {

    private companion object {
        const val T0 = 1_000_000L
        const val TEN_MIN = 10 * 60_000L
        const val THIRTY_MIN = 30 * 60_000L
    }

    private fun row(
        id: Long,
        triggerTime: Long,
        type: String,
        attemptCount: Int = 1
    ) = ReminderEntity(
        id = id,
        type = type,
        taskDesc = "背单词",
        triggerTime = triggerTime,
        state = ReminderState.PENDING,
        followupInterval = 15,
        attemptCount = attemptCount
    )

    private fun fixture(): Triple<FakeAlarmScheduling, FollowupAlarms, MutableList<String>> {
        val scheduling = FakeAlarmScheduling()
        val logs = mutableListOf<String>()
        return Triple(scheduling, FollowupAlarms(scheduling), logs)
    }

    private fun restorer(alarms: FollowupAlarms, logs: MutableList<String>) =
        BootFollowupRestorer(alarms = alarms, log = { logs += it })

    /**
     * TIMEOUT 用**绝对时刻** `triggerTime + FOLLOWUP_TIMEOUT_MS`（不是 `now + 30min`）：
     * 重复补钟幂等、不会被频繁冷启动一直推后。
     */
    @Test
    fun waiting_followup_gets_timeout_alarm_at_absolute_deadline() {
        val (scheduling, alarms, logs) = fixture()
        val deadline = T0 - TEN_MIN + THIRTY_MIN // 该追问「本该超时」的那一刻
        val rows = listOf(
            row(id = 42L, triggerTime = T0 - TEN_MIN, type = ReminderType.FOLLOWUP),  // 等回复
            row(id = 43L, triggerTime = T0 + 60_000, type = ReminderType.FOLLOWUP),   // 未来的首钟
            row(id = 44L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION)      // 过期指令提醒
        )

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        // 1) 等回复的行不进计划（否则会被当「关机期间错过」重放一条重复追问）
        assertEquals(listOf(43L, 44L), result.pending.map { it.id })
        assertEquals(1, result.followupTimeouts)

        // 2) TIMEOUT 钟必须存在：code / kind / 绝对时刻 / reminderId / taskDesc 全对上
        val timeout = scheduling.schedulesOf(AlarmCodes.timeout(42L)).single()
        assertEquals(AlarmKind.TIMEOUT, timeout.kind)
        assertEquals(deadline, timeout.triggerAtMillis)
        assertEquals(42L, timeout.reminderId)
        assertEquals(FollowupAlarms.TASK_TIMEOUT, timeout.taskDesc)

        // 3) 不许顺手给别的行补钟 / 排 FOLLOWUP
        assertTrue(scheduling.schedulesOf(AlarmCodes.timeout(43L)).isEmpty())
        assertTrue(scheduling.schedulesOf(AlarmCodes.timeout(44L)).isEmpty())
        assertTrue(scheduling.schedulesOf(AlarmCodes.followup(42L)).isEmpty())
        assertEquals(1, scheduling.schedules.size)
        assertTrue(logs.any { it.contains("补 TIMEOUT 钟 1 条") })
    }

    /** 没有等回复的过期行 → 一条钟都不排，列表原样返回（不误伤正常恢复）。 */
    @Test
    fun no_overdue_waiting_followup_schedules_nothing() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(
            row(id = 42L, triggerTime = T0 + 1, type = ReminderType.FOLLOWUP),
            row(id = 44L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION)
        )

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        assertEquals(listOf(42L, 44L), result.pending.map { it.id })
        assertEquals(0, result.followupTimeouts)
        assertTrue(scheduling.schedules.isEmpty())
        assertTrue(logs.isEmpty())
    }

    /** 边界：`triggerTime == at` 算过期（与 DAO 的 `triggerTime <= now` 一致）。 */
    @Test
    fun trigger_time_equal_to_at_counts_as_overdue() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(row(id = 7L, triggerTime = T0, type = ReminderType.FOLLOWUP))

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        assertEquals(1, result.followupTimeouts)
        assertTrue(result.pending.isEmpty())
        assertEquals(
            T0 + THIRTY_MIN,
            scheduling.schedulesOf(AlarmCodes.timeout(7L)).single().triggerAtMillis
        )
    }

    /**
     * 审查 S1：`attemptCount == 0` 且过期 = **首钟就错过**，一条追问都没发过。
     *
     * 这种行不能一刀切补 TIMEOUT（那要白等 30min + interval 才收到第 1 条），必须留在
     * pending 里，由 planner 按 `kind=FOLLOWUP` 重放成 `now+5s` 的**第 1 条追问**。
     */
    @Test
    fun first_alarm_missed_row_stays_pending_for_planner_replay() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(
            row(id = 51L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 0)
        )

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        assertEquals("首钟错过的行必须留给 planner 重放", listOf(51L), result.pending.map { it.id })
        assertEquals(0, result.followupTimeouts)
        assertTrue("不许给它补 TIMEOUT 钟", scheduling.schedules.isEmpty())
        assertTrue(logs.isEmpty())
    }

    /** 保序：剔除一条后，其余行（含首钟错过的那条）顺序不变，plan 的下标才对得上。 */
    @Test
    fun order_is_preserved_after_removing_waiting_rows() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(
            row(id = 1L, triggerTime = T0 - TEN_MIN, type = ReminderType.FOLLOWUP, attemptCount = 2),
            row(id = 2L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION),
            row(id = 3L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 0),
            row(id = 4L, triggerTime = T0 + 10_000, type = ReminderType.FOLLOWUP, attemptCount = 0)
        )

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        assertEquals(listOf(2L, 3L, 4L), result.pending.map { it.id })
        assertEquals(1, result.followupTimeouts)
        assertEquals(1, scheduling.schedules.size)
    }

    /**
     * 窗口早已过完（`triggerTime + 30min` 在过去）→ 排的仍是那个**过去**的绝对时刻，
     * `AlarmManager` 对过去时刻立即投递 ≈ 立刻推进链，而不是再等 30 分钟。
     */
    @Test
    fun already_expired_deadline_is_scheduled_in_the_past_on_purpose() {
        val (scheduling, alarms, logs) = fixture()
        val twoHoursAgo = T0 - 2 * 60 * 60_000L
        val rows = listOf(row(id = 61L, triggerTime = twoHoursAgo, type = ReminderType.FOLLOWUP))

        val result = restorer(alarms, logs).splitAndRestoreTimeouts(rows, at = T0)

        assertEquals(1, result.followupTimeouts)
        val at = scheduling.schedulesOf(AlarmCodes.timeout(61L)).single().triggerAtMillis
        assertEquals(twoHoursAgo + THIRTY_MIN, at)
        assertTrue("时刻必须在过去（立即触发），而不是 now+30min", at < T0)
    }

    /**
     * 审查 S2：补钟失败时该行必须**退回 pending**（可被 planner 重放），不能既没钟又不在列表里
     * —— 那种状态下链永久死掉，而且没人看得出来。
     */
    @Test
    fun timeout_schedule_failure_puts_row_back_in_pending() {
        val (scheduling, alarms, logs) = fixture()
        scheduling.failNextSchedule = SecurityException("alarm permission denied")
        val rows = listOf(
            row(id = 42L, triggerTime = T0 - TEN_MIN, type = ReminderType.FOLLOWUP),
            row(id = 44L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION)
        )

        val r = restorer(alarms, logs)
        val result = r.splitAndRestoreTimeouts(rows, at = T0)

        assertEquals(0, result.followupTimeouts)
        assertEquals("失败的行必须回到 pending", listOf(42L, 44L), result.pending.map { it.id })
        assertTrue(scheduling.schedules.isEmpty())
        assertTrue(
            "失败必须留痕并说明会退回重排：$logs",
            logs.any { it.contains("补排追问 TIMEOUT 失败 id=42") && it.contains("退回重排") }
        )

        // 验收核心：退回的行在 light 路径的下一跳必须变成 FOLLOWUP 钟（否则被 `triggerTime > now` 滤掉 = 链死）
        val followups = r.scheduleOverdueFollowups(result.pending, at = T0, replayDelayMs = 5_000L)
        assertEquals(1, followups)
        assertEquals(
            T0 + 5_000L,
            scheduling.schedulesOf(AlarmCodes.followup(42L)).single().triggerAtMillis
        )
    }

    /**
     * 审查 P1（第三轮）：判据放宽到「仍在 pending 里的过期 followup」——
     * `attemptCount == 0`（首钟错过）与 `attemptCount > 0`（补 TIMEOUT 失败退回）都要补 FOLLOWUP 钟，
     * 因为调用方下一步的 `triggerTime > now` 过滤会把这两类**全部**滤掉。
     */
    @Test
    fun overdue_followups_include_nonzero_attempt_rows() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(
            row(id = 7L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 0),
            row(id = 8L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 1),
            row(id = 9L, triggerTime = T0 + 60_000, type = ReminderType.FOLLOWUP, attemptCount = 0),
            row(id = 10L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION)
        )

        val scheduled = restorer(alarms, logs)
            .scheduleOverdueFollowups(rows, at = T0, replayDelayMs = 5_000L)

        assertEquals("过期 followup 两条都要补：attempt=0 与 attempt=1", 2, scheduled)
        listOf(7L, 8L).forEach { id ->
            val followup = scheduling.schedulesOf(AlarmCodes.followup(id)).single()
            assertEquals(AlarmKind.FOLLOWUP, followup.kind)
            assertEquals(T0 + 5_000L, followup.triggerAtMillis)
            assertEquals(FollowupAlarms.TASK_FOLLOWUP, followup.taskDesc)
        }
        assertTrue("未来的追问不许动", scheduling.schedulesOf(AlarmCodes.followup(9L)).isEmpty())
        assertTrue("指令提醒不该被这条方法碰", scheduling.schedulesOf(AlarmCodes.followup(10L)).isEmpty())
        assertTrue(scheduling.schedulesOf(AlarmCodes.timeout(7L)).isEmpty())
        assertEquals(2, scheduling.schedules.size)
    }

    /**
     * 验收核心（第三轮）：补 TIMEOUT **失败**退回的行，在 light 路径上必须拿到 FOLLOWUP 钟。
     * 只断言「回到 pending」等于没验到 light 路径的兜底 —— 下一步 `triggerTime > now` 会把它滤掉。
     */
    @Test
    fun failed_timeout_row_still_gets_followup_alarm_in_light_path() {
        val (scheduling, alarms, logs) = fixture()
        scheduling.failNextSchedule = SecurityException("alarm permission denied")
        val rows = listOf(
            row(id = 42L, triggerTime = T0 - 3_600_000L, type = ReminderType.FOLLOWUP, attemptCount = 3)
        )
        val r = restorer(alarms, logs)

        // 第一次补 TIMEOUT 失败 → 行退回 pending，且没有 timeout 钟
        val split = r.splitAndRestoreTimeouts(rows, at = T0)
        assertEquals(listOf(42L), split.pending.map { it.id })
        assertTrue(scheduling.schedulesOf(AlarmCodes.timeout(42L)).isEmpty())

        // light 路径的第二跳：补过期追问（等价于 CompanionApp 里紧接着的 scheduleOverdueFollowups）
        val followups = r.scheduleOverdueFollowups(split.pending, at = T0, replayDelayMs = 5_000L)

        assertEquals(1, followups)
        val alarm = scheduling.schedulesOf(AlarmCodes.followup(42L)).single()
        assertEquals(AlarmKind.FOLLOWUP, alarm.kind)
        assertEquals(T0 + 5_000L, alarm.triggerAtMillis)
        assertEquals(42L, alarm.reminderId)
    }

    /**
     * 判据放宽的直接证据：pending 里的三类行各按语义分流 —— 两条过期追问各补一个钟，
     * 过期 INSTRUCTION 一条都不动。
     */
    @Test
    fun overdue_first_followups_schedule_covers_failed_timeout_rows() {
        val (scheduling, alarms, logs) = fixture()
        val rows = listOf(
            row(id = 71L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 0),
            row(id = 72L, triggerTime = T0 - 1, type = ReminderType.FOLLOWUP, attemptCount = 2),
            row(id = 73L, triggerTime = T0 - 1, type = ReminderType.INSTRUCTION)
        )

        val scheduled = restorer(alarms, logs).scheduleOverdueFollowups(rows, at = T0)

        assertEquals(2, scheduled)
        assertEquals(2, scheduling.schedules.size)
        assertEquals(T0 + BootFollowupRestorer.DEFAULT_REPLAY_DELAY_MS, scheduling.schedulesOf(AlarmCodes.followup(71L)).single().triggerAtMillis)
        assertEquals(T0 + BootFollowupRestorer.DEFAULT_REPLAY_DELAY_MS, scheduling.schedulesOf(AlarmCodes.followup(72L)).single().triggerAtMillis)
        assertTrue(scheduling.schedulesOf(AlarmCodes.followup(73L)).isEmpty())
        assertTrue(scheduling.schedulesOf(AlarmCodes.timeout(71L)).isEmpty())
    }
}