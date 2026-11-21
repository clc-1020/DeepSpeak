package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderType

/**
 * 恢复路径（重启 / 升级 / force-stop / ROM 清后台）上对「追问行」的处置（V2.2.4 审查 R1）。
 *
 * **背景（为什么必须有这一步）**：这三种场景都会把 `AlarmManager` 里的闹钟清掉，而
 * `reminders` 表里的行仍然是 `pending`。一条 `type=followup` 的行只有两种状态：
 *
 * | 状态 | 判据 | 恢复后应该怎么走 |
 * |---|---|---|
 * | 还没发过任何追问（首钟就错过） | `attemptCount == 0` | 补一次 **FOLLOWUP** 钟 → 立即发第 1 条 |
 * | 已发过追问、正在等回应 | `attemptCount > 0` 且 `triggerTime` 是过去 | 补一个 **TIMEOUT** 钟 → 链靠它继续 |
 *
 * 两种分支若混成一条，代价分别是：
 *  - 一律补 TIMEOUT：首钟错过的用户要白等 30min（TIMEOUT）+ interval 才收到第 1 条追问；
 *  - 一律不补（第一版写法）：该行永久 `pending`、没有任何闹钟指向它 →
 *    `anyPendingFollowup()` 恒真 → V2.2.3 的 `extractAndSchedule` 从此静默拒绝所有新建监督任务
 *    （比重复追问更糟，也是 V2.2.4 存在的理由）。
 *
 * **TIMEOUT 的时刻用绝对时间 `triggerTime + FOLLOWUP_TIMEOUT_MS`，不用 `now + 30min`**：
 *  - 绝对时刻 = 这条追问「本该超时」的那一刻，重复补是**幂等**的（重启 + 每次冷启动各补一次也不会漂）；
 *  - `now + 30min` 则每次冷启动都把追问再推后 30 分钟 —— 用户频繁开 App 会把追问无限期延后，
 *    而 `anyPendingFollowup()` 一直为真、他同时又建不了新的监督任务；
 *  - 若该窗口早已过完，`AlarmManager` 对过去的 `triggerAtMillis` **立即投递** → TIMEOUT 立刻触发、
 *    链立刻往前走一步（`advance` → 下一条按 interval 发），这正是「已经超时了就该动」的语义。
 *
 * 纯 JVM：只依赖 [FollowupAlarms]（→ `AlarmScheduling` 接口），不需要 Context —— 抽出来是为了让
 * 「恢复后 `timeout(id)` 闹钟必须存在」这条副作用能直测（原来埋在 `CompanionApp` 里，JVM 测不到）。
 *
 * **本类没有内部时钟**：`at` 一律由调用方显式传入（**无默认值**）。两处调用点都用同一个 `now`，
 * 避免同一进程两次取时让边界行「一边判过期、一边判未来」；去掉默认值 = 将来新增调用点忘传会
 * 直接编译失败，而不是悄悄再取一次时间。
 */
class BootFollowupRestorer(
    private val alarms: FollowupAlarms,
    private val log: (String) -> Unit = { android.util.Log.w("BootRestore", it) }
) {

    /**
     * @param pending 交给 [BootRestorePlanner][com.companion.chat.data.alarm.BootRestorePlanner]
     *   的行：**保持原顺序**；「已发过追问、在等回应」的过期行已剔除（它们的钟已改补 TIMEOUT）；
     *   `attemptCount == 0` 的过期行**留在里面**，由 planner 按 `kind=FOLLOWUP` 重放出第 1 条追问。
     * @param followupTimeouts 本次真正补上的 TIMEOUT 钟条数（用于日志/报告核对）。
     */
    data class Result(
        val pending: List<ReminderEntity>,
        val followupTimeouts: Int
    )

    /**
     * 「已发过追问、正在等回应」的过期行 → 补 TIMEOUT 钟；其余行原样返回（保序）。
     *
     * 补钟失败的处置：**把该行放回 [Result.pending]**，让上层按「过期行」重放一次。
     * 理由：失败后既不重放也没有钟 = 链永久死掉（`anyPendingFollowup()` 恒真、新链被挡）；
     * 而重放的代价只是**可能多一条重复追问**。两害相权，宁可重复一次。实际极难触发
     * （`AlarmScheduler` 自己 catch `SecurityException` 并回落）。
     *
     * 全程 runCatching：单条失败只影响那一条，绝不抛（BootReceiver 里抛会记 ANR/崩溃）。
     *
     * @param at 判定基准（调用方传入，**不要**在类里再取一次时间：上层也用同一个 `now` 做
     *   过期/未来判定，两次取时会让边界行一边判「过期」一边判「未来」）
     */
    fun splitAndRestoreTimeouts(rows: List<ReminderEntity>, at: Long): Result {
        val pending = ArrayList<ReminderEntity>(rows.size)
        var scheduled = 0
        var failed = 0
        rows.forEach { reminder ->
            val waitingForReply = reminder.type == ReminderType.FOLLOWUP &&
                reminder.triggerTime <= at &&
                reminder.attemptCount > 0
            if (!waitingForReply) {
                pending += reminder
                return@forEach
            }
            val ok = runCatching {
                alarms.scheduleTimeout(reminder.id, reminder.triggerTime + FollowupAlarms.FOLLOWUP_TIMEOUT_MS)
                true
            }.getOrElse {
                log("补排追问 TIMEOUT 失败 id=${reminder.id}，退回重排（可能重复一条追问）: ${it.message}")
                false
            }
            if (ok) scheduled++ else {
                failed++
                pending += reminder
            }
        }
        if (scheduled > 0 || failed > 0) {
            log("过期追问：不重放追问，补 TIMEOUT 钟 $scheduled 条后继续链（补钟失败 $failed 条已退回重排）")
        }
        return Result(pending, scheduled)
    }

    /**
     * 仍在 `pending` 里的**过期 followup** → 补一次 FOLLOWUP 钟，`at + replayDelayMs` 到点。
     *
     * **为什么判据不能只认 `attemptCount == 0`**：调用方（light restore）下一步是
     * `filter { triggerTime > now }`，会把所有过期行滤掉。而这里出现过期 followup 只有两种：
     *  1. 首钟就错过（`attemptCount == 0`）→ 补发第 1 条追问；
     *  2. 上面 [splitAndRestoreTimeouts] 补 TIMEOUT **失败**退回的（`attemptCount > 0`）
     *     → 宁可重复一条追问，也不让链死（`anyPendingFollowup()` 恒真会挡住所有新建监督任务）。
     * 两种都不许被静默丢掉。
     *
     * **只给 light restore 用**：boot 路径由 [BootRestorePlanner] 统一重放，重复调虽然也只是
     * 同 code 替换、不会叠钟，但没必要（多一次 AlarmManager 调用）。
     *
     * @return 真正补上的 FOLLOWUP 钟条数
     */
    fun scheduleOverdueFollowups(
        rows: List<ReminderEntity>,
        at: Long,
        replayDelayMs: Long = DEFAULT_REPLAY_DELAY_MS
    ): Int {
        var scheduled = 0
        rows.forEach { reminder ->
            if (reminder.type != ReminderType.FOLLOWUP || reminder.triggerTime > at) return@forEach
            runCatching {
                alarms.scheduleFollowup(reminder.id, at + replayDelayMs)
                scheduled++
            }.onFailure {
                log("补排过期追问失败 id=${reminder.id}（该链会停住）: ${it.message}")
            }
        }
        if (scheduled > 0) log("过期追问 $scheduled 条：补 FOLLOWUP 钟 @+${replayDelayMs}ms")
        return scheduled
    }

    companion object {
        /** 与 `CompanionApp.OVERDUE_REPLAY_DELAY_MS` 同义：够快，又避开开机/冷启瞬间的资源抢占。 */
        const val DEFAULT_REPLAY_DELAY_MS = 5_000L
    }
}