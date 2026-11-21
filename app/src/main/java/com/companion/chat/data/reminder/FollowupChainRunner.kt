package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.reminder.FollowupPort

/**
 * 追问链 tick（V2.2.4）。计划书 §3.6 的执行体。
 *
 * **为什么单独一个类**：`ReminderAlarmReceiver` 没有 Android Context 就没法 JVM 测，而这轮
 * 4 条验收全是「计数 / 排钟 / 入队」的纯决策。抽出来以后 Receiver 只剩「读 extra → 调这里」。
 * 依赖全部可注入、零 Android：
 *  - [dao]     `ReminderDao` 接口（测试用 FakeReminderDao）
 *  - [alarms]  只依赖 `AlarmScheduling`（测试用 FakeAlarmScheduling）
 *  - [queue]   只依赖 `PendingMessageDao`（测试用 FakePendingMessageDao）
 *  - [port]    复用 V2.2.2 的 [FollowupPort]（`advance()` / `abandon()`），不在这里重算间隔
 *
 * 两类 tick 的语义（§3.6 定死，不许自创）：
 *
 * | kind | 行为 |
 * |---|---|
 * | FOLLOWUP | `n = attemptCount + 1`；`n > maxAttempts` → `abandoned` + `cancelAll`，**不投队列**；否则 `incrementAttempt` → 投 `FOLLOW_UP` → 排 `TIMEOUT`（now + 30min） |
 * | TIMEOUT | **不投队列、不发通知、不改 attemptCount**；只重排下一次 FOLLOWUP（now + `followupInterval * 60_000`） |
 *
 * **删除计数语义**：`attemptCount` 只在 FOLLOWUP 投队列时 +1。从 0 起第 1..5 次到点各发 1 条，
 * 第 6 次到点 `n=6 > 5` → 放弃 —— 恰好发出 `maxAttempts`(=5) 条。
 *
 * 顺序铁律（坑表 #1/#2，每条都对应一次真实事故模式）：
 * 1. 判上限（先 return，不投队列）；
 * 2. **先排 TIMEOUT**（续命：后面两步抛异常，链仍能靠 TIMEOUT 继续，不会「断在半路、永久 pending」）；
 * 3. 再计数（§3.6：++ 发生在「即将投队列」之前，入队失败也不会漏计导致多催一轮）；
 * 4. 最后入队（失败只记日志：这条丢一次，但 TIMEOUT 已排好，链不断）。
 *
 * 不包 runCatching 到 [tickTimeout] 外层：异常交给 Receiver 的 `runCatching` 兜（那里必须不抛，
 * 否则记 ANR/崩溃）。本类只把**入队失败**降级成日志，因为那是可预期的一次性丢弃。
 */
class FollowupChainRunner(
    private val dao: ReminderDao,
    private val alarms: FollowupAlarms,
    private val queue: PendingMessageQueue,
    private val port: FollowupPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.d(TAG, it) }
) {

    /** FOLLOWUP 到点。返回是否真的投出了一条追问。 */
    suspend fun tickFollowup(reminderId: Long): Boolean {
        // 坑 #7：旧闹钟（EXTRA_REMINDER_ID 缺失时是 -1L）别去 getById(-1) 白查一次
        if (reminderId <= 0L) {
            log("followup reminderId 非法=$reminderId（旧闹钟/坏数据），忽略")
            return false
        }
        val reminder = dao.getById(reminderId)
        if (reminder == null) {
            log("id=$reminderId 无对应行，取消三个码")
            alarms.cancelAll(reminderId)
            return false
        }
        // 坑 #6：用户已完成 / 已放弃，残留闹钟不能再补一条追问
        if (reminder.state != ReminderState.PENDING) {
            log("id=$reminderId 已是 ${reminder.state}，忽略并取消三个码")
            alarms.cancelAll(reminderId)
            return false
        }

        val attempt = reminder.attemptCount + 1
        if (attempt > reminder.maxAttempts) {
            // 坑 #3：第 6 次到点 —— 放弃，这条**不投队列**（否则会发出 6 条，验收「恰好 5 条」失败）。
            // abandon() 内部 = updateState(ABANDONED) + cancelAll(三个码)。
            port.abandon(reminderId)
            log("id=$reminderId 已达上限 ${reminder.maxAttempts} 次，转 ABANDONED")
            return false
        }

        // ① 先续命：把 TIMEOUT 排好。后面两步万一抛异常，链仍能靠 TIMEOUT 继续，
        //    不会像 V2.2.3 那样「断在半路、永久 pending」。（与 handleEnvCheck「先续命」同一模式）
        alarms.scheduleTimeout(reminderId, now() + FollowupAlarms.FOLLOWUP_TIMEOUT_MS)

        // ② 再计数（§3.6：++ 在「即将投队列」之前）
        dao.incrementAttempt(reminderId)

        // ③ 最后入队。失败只记日志：这条丢一次，但 TIMEOUT 已在 ① 排好，链不会断。
        //    类型必须走 PendingMessageType.FOLLOW_UP（坑 #9：别造新枚举，闸门 ofPendingType 靠 code="followup"），
        //    内容必须走 PendingMessageContent.Followup 重载（坑 #8：手拼字符串前缀写错会被当正文发出去）。
        val enqueuedId = runCatching {
            queue.enqueue(
                type = PendingMessageType.FOLLOW_UP,
                content = PendingMessageContent.Followup(reminderId, attempt, reminder.taskDesc),
                triggerTime = now()
            )
        }.onFailure {
            log("id=$reminderId 投队列失败（已计数，此条丢弃，链继续）: ${it.message}")
        }.getOrNull()

        // ④ 入队后**立刻复查状态**（V2.2.5 观察③ 收口，2026-11-20）：
        //    「用户点已完成」与「闹钟到点」是两个线程，中间夹着 scheduleTimeout + incrementAttempt
        //    两次 IO。若 complete() 恰好在这段窗口里跑完，它的 cancelPendingFollowups 扫的是
        //    「当时还没入队」的队列 → 漏掉刚写进去的这一条 → 表现为「点了完成还收到一条追问」。
        //
        //    两头都关才不漏：complete() 先清、我后写 → 这里复查发现链已结束，撤回本条；
        //    我先写、complete() 后清 → 它自己扫得到。代价是每 tick 多一次 getById（单行主键查询）。
        val stillPending = enqueuedId != null && dao.getById(reminderId)?.state == ReminderState.PENDING
        if (enqueuedId != null && !stillPending) {
            queue.cancelPendingFollowups(reminderId)
            log("id=$reminderId 入队瞬间链已结束，撤回本条追问")
        }

        log("id=$reminderId 第 $attempt/${reminder.maxAttempts} 次追问：队列 ok=${enqueuedId != null}，timeout 已排")
        return enqueuedId != null && stillPending
    }

    /** TIMEOUT 到点。返回是否推进了下一次 FOLLOWUP。 */
    suspend fun tickTimeout(reminderId: Long): Boolean {
        if (reminderId <= 0L) {
            log("timeout reminderId 非法=$reminderId，忽略")
            return false
        }
        val reminder = dao.getById(reminderId)
        if (reminder == null || reminder.state != ReminderState.PENDING) {
            log("id=$reminderId 无行或已结束，取消三个码")
            alarms.cancelAll(reminderId)
            return false
        }
        // V2.2.2 的 advance 已经做了：cancel 旧 TIMEOUT → updateTriggerTime → scheduleFollowup。
        // **不 +1、不投队列、不发通知**（§3.6 定死；坑 #4/#5：别在这里 increment，也别自己抄
        // `interval * 60_000` 公式 —— interval 存的是分钟，抄错成 *1000L 会把 15 分钟变 15 秒）。
        //
        // 已知非原子点（审查 S4，记录不修）：advance 的「cancel 旧 TIMEOUT → 排新 FOLLOWUP」
        // 不是原子操作；若最后一步 scheduleFollowup 抛（AlarmScheduler 已 catch SecurityException
        // 并回落，实际极难触发），旧 TIMEOUT 已取消、新 FOLLOWUP 没排 → 链断。属 V2.2.2 既有行为，
        // 本轮没有加重；真要治需要在 advance 内部把「排钟失败」回滚成「排 TIMEOUT」。
        port.advance(reminderId)
        log("id=$reminderId 超时未回应，推进下一次 FOLLOWUP（不计数）")
        return true
    }

    private companion object {
        const val TAG = "FollowupChain"
    }
}

