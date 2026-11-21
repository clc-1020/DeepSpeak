package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.FakeAlarmScheduling
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.dao.FakeReminderDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.PendingMessageContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2.2.4：`FollowupChainRunner` 的纯 JVM 单测（14 条，编号对应实现提示词 §5 的 1–14）。
 *
 * 全部用**真实**实现串起来：`FakeReminderDao` + `FakeAlarmScheduling` + `FakePendingMessageDao`
 * + **真** `FollowupPortImpl` + 真 `PendingMessageQueue`。这样「`ABANDONED` 落库 + 三个码成套取消」
 * 「TIMEOUT 用 interval 而不是硬编码」「入队失败仍已计数」都直接断言在真实副作用上，
 * 不需要再造一个假 port（假 port 会把被测语义复制一份，两边一起错就测不出来）。
 *
 * `now` 一律注入固定值 [T0]，否则「TIMEOUT 排在 now+30min」「下一次 FOLLOWUP 排在 now+interval」
 * 会变成与真实时钟赛跑，断言只能写成范围，等于没钉住。
 */
class FollowupChainRunnerTest {

    private companion object {
        const val T0 = 1_000_000L
        const val RID = 42L
        const val TASK = "背单词"
    }

    /** 一次完整装配：四个依赖 + 录制到的日志 + 跨 fake 的调用顺序。 */
    private class Fixture(
        val dao: FakeReminderDao,
        val pendingDao: FakePendingMessageDao,
        val scheduling: FakeAlarmScheduling,
        val queue: PendingMessageQueue,
        val logs: MutableList<String>,
        val order: MutableList<String>,
        val runner: FollowupChainRunner
    ) {
        /** 三个 code 是否**恰好各被取消一次**（成套取消的严格版断言）。 */
        fun assertCancelAllExactly(id: Long) {
            val expected = AlarmCodes.cancelAll(id)
            assertEquals("取消次数应为 3（三个 code 各一次）", 3, scheduling.cancels.size)
            assertEquals(expected.toSet(), scheduling.cancels.toSet())
            expected.forEach { assertEquals("code=$it 应被取消一次", 1, scheduling.cancelCount(it)) }
        }

        /**
         * 断言 [steps] 严格按给定先后出现（每一步都要在流水里）。
         * 没有它，「计数和入队都发生了」在顺序反了时也成立 —— 断言就白写了。
         */
        fun assertOrdered(vararg steps: String) {
            val indices = steps.map { step ->
                val i = order.indexOf(step)
                assertTrue("调用流水里缺少 $step：$order", i >= 0)
                i
            }
            assertEquals("顺序不对：期望 $steps，实际 $order", indices.sorted(), indices)
        }
    }

    private suspend fun fixture(
        attemptCount: Int = 0,
        maxAttempts: Int = 5,
        state: String = ReminderState.PENDING,
        interval: Int? = 15,
        seedRow: Boolean = true,
        nowMillis: Long = T0
    ): Fixture {
        val dao = FakeReminderDao()
        if (seedRow) {
            dao.insert(
                ReminderEntity(
                    id = RID,
                    type = ReminderType.FOLLOWUP,
                    taskDesc = TASK,
                    triggerTime = 0L,
                    state = state,
                    followupInterval = interval,
                    stopCondition = "用户说背完了",
                    attemptCount = attemptCount,
                    maxAttempts = maxAttempts
                )
            )
        }
        val scheduling = FakeAlarmScheduling()
        val pendingDao = FakePendingMessageDao()
        val order = mutableListOf<String>()
        // 三个 fake 共用一个流水 list：只有这样才断言得了「排钟 → 计数 → 入队」的真实先后
        dao.order = order
        pendingDao.order = order
        scheduling.order = order
        val queue = PendingMessageQueue(pendingDao, now = { nowMillis })
        val alarms = FollowupAlarms(scheduling)
        val port = FollowupPortImpl(dao, alarms, queue, memoryShortDao = null, nowMillis = { nowMillis })
        val logs = mutableListOf<String>()
        val runner = FollowupChainRunner(
            dao = dao,
            alarms = alarms,
            queue = queue,
            port = port,
            now = { nowMillis },
            log = { logs += it }
        )
        return Fixture(dao, pendingDao, scheduling, queue, logs, order, runner)
    }

    // 1. 无对应行 → false、三个码各取消一次、队列 0 条
    @Test
    fun missing_row_cancels_all_and_enqueues_nothing() = runBlocking {
        val f = fixture(seedRow = false)
        assertFalse(f.runner.tickFollowup(RID))
        f.assertCancelAllExactly(RID)
        assertEquals(0, f.pendingDao.items.size)
        assertEquals(0, f.pendingDao.insertCalls)
        assertTrue("无行时不该排任何新闹钟", f.scheduling.schedules.isEmpty())
    }

    // 2. state=DONE → 忽略 + 取消，队列 0 条
    @Test
    fun done_state_is_ignored_and_canceled() = runBlocking {
        val f = fixture(state = ReminderState.DONE, attemptCount = 1)
        assertFalse(f.runner.tickFollowup(RID))
        f.assertCancelAllExactly(RID)
        assertEquals(0, f.pendingDao.items.size)
        assertEquals(0, f.dao.incrementAttemptCalls)
        assertEquals(ReminderState.DONE, f.dao.getById(RID)!!.state)
    }

    // 3. state=ABANDONED → 忽略 + 取消
    @Test
    fun abandoned_state_is_ignored_and_canceled() = runBlocking {
        val f = fixture(state = ReminderState.ABANDONED, attemptCount = 5)
        assertFalse(f.runner.tickFollowup(RID))
        f.assertCancelAllExactly(RID)
        assertEquals(0, f.pendingDao.items.size)
        assertEquals(ReminderState.ABANDONED, f.dao.getById(RID)!!.state)
    }

    // 4. attemptCount=0 首次到点 → 投 1 条、attemptCount==1、TIMEOUT 已排
    @Test
    fun first_tick_enqueues_one_and_schedules_timeout() = runBlocking {
        val f = fixture(attemptCount = 0)
        assertTrue(f.runner.tickFollowup(RID))

        assertEquals(1, f.pendingDao.items.size)
        assertEquals(1, f.dao.getById(RID)!!.attemptCount)

        val timeout = f.scheduling.schedulesOf(AlarmCodes.timeout(RID)).single()
        assertEquals(AlarmKind.TIMEOUT, timeout.kind)
        assertEquals(T0 + FollowupAlarms.FOLLOWUP_TIMEOUT_MS, timeout.triggerAtMillis)
        assertEquals(RID, timeout.reminderId)
        // 首次到点不该触发「成套取消」
        assertTrue("不应取消任何闹钟，实际=${f.scheduling.cancels}", f.scheduling.cancels.isEmpty())
        // 顺序铁律：排 TIMEOUT（续命）→ 计数 → 入队
        f.assertOrdered("schedule:TIMEOUT", "incrementAttempt", "enqueue")
    }

    // 5. attemptCount=4（第 5 次）→ 投出第 5 条、attemptCount==5
    @Test
    fun fifth_tick_still_delivers() = runBlocking {
        val f = fixture(attemptCount = 4)
        assertTrue(f.runner.tickFollowup(RID))

        assertEquals(1, f.pendingDao.items.size)
        assertEquals(5, f.dao.getById(RID)!!.attemptCount)
        val decoded = PendingMessageContent.followupOrNull(f.pendingDao.items.single().content)
        assertEquals(5, decoded!!.attempt)
        assertEquals(ReminderState.PENDING, f.dao.getById(RID)!!.state)
    }

    // 6. attemptCount=5（第 6 次到点）→ false、队列 0 条、DB 变 abandoned、三个码全取消
    @Test
    fun sixth_tick_abandons_without_enqueue() = runBlocking {
        val f = fixture(attemptCount = 5)
        assertFalse(f.runner.tickFollowup(RID))

        assertEquals("第 6 次到点不许再投队列", 0, f.pendingDao.items.size)
        assertEquals(0, f.pendingDao.insertCalls)
        assertEquals(ReminderState.ABANDONED, f.dao.getById(RID)!!.state)
        f.assertCancelAllExactly(RID)
        assertEquals("放弃时不该再排 TIMEOUT", 0, f.scheduling.schedules.size)
        assertEquals("放弃时次数不再 +1", 5, f.dao.getById(RID)!!.attemptCount)
        assertEquals(0, f.dao.incrementAttemptCalls)
    }

    // 7. 入队内容可解码：Followup(id, attempt, taskDesc) 三个字段逐一对上
    @Test
    fun enqueued_content_decodes_to_followup_with_all_fields() = runBlocking {
        val f = fixture(attemptCount = 2)
        assertTrue(f.runner.tickFollowup(RID))

        val row = f.pendingDao.items.single()
        val content = PendingMessageContent.decode(row.content)
        assertTrue("应解码为 Followup，实际=$content", content is PendingMessageContent.Followup)
        val followup = content as PendingMessageContent.Followup
        assertEquals(RID, followup.reminderId)
        assertEquals(3, followup.attempt)
        assertEquals(TASK, followup.task)
        // 坑 #8：手拼字符串会把前缀写错，这里直接钉 wire 格式
        assertEquals("followup:$RID:3:$TASK", row.content)
    }

    // 8. 计数先于入队：insert 抛异常 → attemptCount 仍 +1，且 TIMEOUT 已排
    @Test
    fun count_and_timeout_happen_even_if_enqueue_fails() = runBlocking {
        val f = fixture(attemptCount = 0)
        f.pendingDao.failNextInsert = IllegalStateException("db is locked")

        assertFalse("入队失败 → 返回 false", f.runner.tickFollowup(RID))

        assertEquals("计数必须先落（坑 #2）", 1, f.dao.getById(RID)!!.attemptCount)
        assertEquals(1, f.pendingDao.insertCalls)
        assertEquals(0, f.pendingDao.items.size)
        val timeout = f.scheduling.schedulesOf(AlarmCodes.timeout(RID)).single()
        assertEquals("TIMEOUT 必须已排（坑 #1：先续命）", T0 + FollowupAlarms.FOLLOWUP_TIMEOUT_MS, timeout.triggerAtMillis)
        assertTrue(
            "失败必须留在日志里（不许静默吞）：${f.logs}",
            f.logs.any { it.contains("投队列失败") }
        )
        assertEquals("链未结束，行仍是 pending", ReminderState.PENDING, f.dao.getById(RID)!!.state)
        // 坑 #2 的真正判据：计数发生在**入队之前**（入队抛异常也不能颠倒这个先后）
        f.assertOrdered("schedule:TIMEOUT", "incrementAttempt", "enqueue")
    }

    // 9. reminderId = -1L → false，DAO 与队列均 0 次交互
    @Test
    fun non_positive_reminder_id_does_not_touch_dao_or_queue() = runBlocking {
        val f = fixture(attemptCount = 0)
        val itemsBefore = f.dao.items.toList()

        assertFalse(f.runner.tickFollowup(-1L))

        assertEquals("不该 getById(-1) 白查一次", 0, f.dao.getByIdCalls)
        assertEquals(itemsBefore, f.dao.items)
        assertEquals(0, f.pendingDao.insertCalls)
        assertTrue(f.scheduling.events.isEmpty())
        assertEquals(1, f.logs.size)
    }

    // 10. 投递类型是 PendingMessageType.FOLLOW_UP（code="followup", priority=4）
    @Test
    fun enqueued_row_uses_follow_up_type_and_priority() = runBlocking {
        val f = fixture(attemptCount = 0)
        assertTrue(f.runner.tickFollowup(RID))

        val row = f.pendingDao.items.single()
        assertEquals(PendingMessageType.FOLLOW_UP.code, row.type)
        assertEquals(4, row.priority)
        assertEquals(PendingMessageState.PENDING, row.state)
        assertEquals(T0, row.triggerTime)
    }

    // 11. TIMEOUT：attemptCount 不变、队列 0 条、下一次 FOLLOWUP 的 at == now + interval*60_000
    @Test
    fun timeout_advances_next_followup_without_counting() = runBlocking {
        val f = fixture(attemptCount = 1, interval = 20)
        assertTrue(f.runner.tickTimeout(RID))

        assertEquals("TIMEOUT 不许 +1（坑 #4）", 1, f.dao.getById(RID)!!.attemptCount)
        assertEquals(0, f.dao.incrementAttemptCalls)
        assertEquals("TIMEOUT 不许投队列", 0, f.pendingDao.items.size)
        assertEquals(0, f.pendingDao.insertCalls)

        val next = f.scheduling.schedulesOf(AlarmCodes.followup(RID)).single()
        assertEquals(AlarmKind.FOLLOWUP, next.kind)
        assertEquals(T0 + 20 * 60_000L, next.triggerAtMillis)
        assertEquals(T0 + 20 * 60_000L, f.dao.getById(RID)!!.triggerTime)
        // advance 的第一动作是取消旧 TIMEOUT（成套取消的「单码」版）
        assertEquals(1, f.scheduling.cancelCount(AlarmCodes.timeout(RID)))
        // 也钉住先后：先 cancel 旧 TIMEOUT，再排新 FOLLOWUP
        f.assertOrdered("cancel:${AlarmCodes.timeout(RID)}", "schedule:FOLLOWUP")
    }

    // 12. TIMEOUT：followupInterval = null → 用 DEFAULT_INTERVAL_MINUTES（15 分钟，不是 15 秒）
    @Test
    fun timeout_defaults_to_fifteen_minutes_not_fifteen_seconds() = runBlocking {
        val f = fixture(attemptCount = 1, interval = null)
        assertTrue(f.runner.tickTimeout(RID))

        val expected = T0 + FollowupPortImpl.DEFAULT_INTERVAL_MINUTES * 60_000L
        assertEquals(15 * 60_000L, expected - T0)
        assertEquals(expected, f.scheduling.schedulesOf(AlarmCodes.followup(RID)).single().triggerAtMillis)
        assertEquals(expected, f.dao.getById(RID)!!.triggerTime)
        // 反例钉住：写成 *1000L 会得到 15_000
        assertTrue("别把分钟当秒（坑 #5）", f.dao.getById(RID)!!.triggerTime != T0 + 15_000L)
    }

    // 13. TIMEOUT：state=DONE → 忽略 + 取消，不排下一次
    @Test
    fun timeout_on_done_state_cancels_without_scheduling() = runBlocking {
        val f = fixture(state = ReminderState.DONE, attemptCount = 2)
        assertFalse(f.runner.tickTimeout(RID))

        f.assertCancelAllExactly(RID)
        assertTrue("已结束的链不许再排 FOLLOWUP", f.scheduling.schedules.isEmpty())
        assertEquals(0, f.pendingDao.items.size)
        assertEquals(2, f.dao.getById(RID)!!.attemptCount)
    }

    // 14. FOLLOWUP_TIMEOUT_MS == 30 * 60_000L（常量钉住，防有人改成 30 秒）
    @Test
    fun followup_timeout_constant_is_thirty_minutes() {
        assertEquals(1_800_000L, FollowupAlarms.FOLLOWUP_TIMEOUT_MS)
        assertEquals(30 * 60_000L, FollowupAlarms.FOLLOWUP_TIMEOUT_MS)
        // taskDesc 仍是常量（坑 #10：别把任务原文塞进闹钟文案）
        assertEquals("追问", FollowupAlarms.TASK_FOLLOWUP)
        assertEquals("追问超时", FollowupAlarms.TASK_TIMEOUT)
    }

    // 附带：整条链从 0 连续到点 6 次 → 恰好发 5 条，第 6 次放弃（验收 §4 第一句的端到端版）
    @Test
    fun full_chain_delivers_exactly_max_attempts_messages() = runBlocking {
        val f = fixture(attemptCount = 0, maxAttempts = 5)

        repeat(5) { i ->
            assertTrue("第 ${i + 1} 次到点应投出", f.runner.tickFollowup(RID))
        }
        assertEquals(5, f.pendingDao.items.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            f.pendingDao.items.map { PendingMessageContent.followupOrNull(it.content)!!.attempt }
        )
        assertEquals(5, f.dao.getById(RID)!!.attemptCount)

        assertFalse("第 6 次到点应放弃", f.runner.tickFollowup(RID))
        assertEquals("恰好 5 条，不许变成 6 条", 5, f.pendingDao.items.size)
        assertEquals(ReminderState.ABANDONED, f.dao.getById(RID)!!.state)

        val lastThree = f.scheduling.cancels.takeLast(3)
        assertEquals(AlarmCodes.cancelAll(RID).toSet(), lastThree.toSet())
        assertNotNull(f.logs.firstOrNull { it.contains("转 ABANDONED") })
    }

    /**
     * 第 2 次 [getById]（tick 入队后的复查）时把行改成 DONE ——
     * 精确模拟「scheduleTimeout → incrementAttempt → enqueue 这段窗口里，用户点了已完成」。
     */
    private class CompletedOnSecondLookup(
        private val base: FakeReminderDao,
        private val doneState: String
    ) : ReminderDao by base {
        private var lookups = 0

        override suspend fun getById(id: Long): ReminderEntity? {
            lookups++
            if (lookups == 2) base.updateState(id, doneState)
            return base.getById(id)
        }
    }

    // 15. V2.2.5 观察③ 收口：入队瞬间链被点完成 → 刚入队那条必须撤回，不能 15 分钟后照发
    @Test
    fun tick_withdraws_just_enqueued_followup_when_chain_ends_mid_flight() = runBlocking {
        val base = FakeReminderDao()
        base.insert(
            ReminderEntity(
                id = RID, type = ReminderType.FOLLOWUP, taskDesc = TASK, triggerTime = 0L,
                state = ReminderState.PENDING, followupInterval = 15,
                stopCondition = "用户说背完了", attemptCount = 2, maxAttempts = 5
            )
        )
        val dao = CompletedOnSecondLookup(base, ReminderState.DONE)
        val pendingDao = FakePendingMessageDao()
        val scheduling = FakeAlarmScheduling()
        val queue = PendingMessageQueue(pendingDao, now = { T0 })
        val alarms = FollowupAlarms(scheduling)
        val logs = mutableListOf<String>()
        val runner = FollowupChainRunner(
            dao = dao,
            alarms = alarms,
            queue = queue,
            port = FollowupPortImpl(dao, alarms, queue, memoryShortDao = null, nowMillis = { T0 }),
            now = { T0 },
            log = { logs += it }
        )

        assertFalse("链已结束的那一条不该算投出", runner.tickFollowup(RID))

        assertEquals("入队的那条必须被撤回", 1, pendingDao.items.size)
        assertEquals(
            PendingMessageState.CANCELLED,
            pendingDao.items.single().state
        )
        assertNotNull("应留下撤回日志", logs.firstOrNull { it.contains("撤回") })
    }
}