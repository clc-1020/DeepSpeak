package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.FakeAlarmScheduling
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.FakeMemoryShortDao
import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.dao.FakeReminderDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.PendingMessageContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V2.2.2：FollowupPortImpl 的 DAO/闹钟副作用单测（FakeDao + 录制式 Scheduler）。
 *
 * V2.2.4：录制式 scheduler 从本文件的 private 内嵌类提成共用 [FakeAlarmScheduling]，
 * [FakeAlarmScheduling.events] 的字符串格式与原来完全一致，故下面旧断言不用改。
 *
 * 2026-11-20 收口（V2.2.2 遗留）：`complete()` / `abandon()` 现在还要清掉**已入队**的残留追问，
 * 故 fixture 里补了真 [PendingMessageQueue] + [FakePendingMessageDao]，
 * 并新增「只杀本链」「先落库再清队列」两条守卫。
 *
 * 2026-11-20 晚间：`complete()` / `abandon()` 顺带删掉这条链的短期记忆
 * （用户要求「监督结束就别留在记忆里」），fixture 补了 [FakeMemoryShortDao]。
 */
class FollowupPortImplTest {

    /**
     * `ReminderDao` 的接口委托包装：只在 [updateState] 上插入顺序流水 / 失败注入。
     *
     * 为什么不直接改 [FakeReminderDao]：那个 fake 被 V2.2.4 的
     * `FollowupChainRunnerTest` 与 V2.2.1 的迁移测试共用，往它的 `updateState` 里加流水
     * 会改动那些测试已钉死的 order 序列（例如「排 TIMEOUT → 计数 → 入队」）。
     * 接口委托让这里可以只包一层、零影响其它用例。
     */
    private class HookedReminderDao(
        private val base: FakeReminderDao,
        private val order: MutableList<String>
    ) : ReminderDao by base {
        /** 非 null 时 [updateState] 先抛再执行，用来断言「落库在清队列之前」。 */
        var failUpdateState: Throwable? = null

        override suspend fun updateState(id: Long, state: String) {
            order += "updateState"
            failUpdateState?.let { throw it }
            base.updateState(id, state)
        }
    }

    private class Fixture(
        val reminders: FakeReminderDao,
        val pendingDao: FakePendingMessageDao,
        val scheduler: FakeAlarmScheduling,
        val memories: FakeMemoryShortDao,
        val port: FollowupPortImpl
    )

    private suspend fun fixture(
        interval: Int? = null,
        attemptCount: Int = 1,
        now: Long = 1_000L,
        /** 传 true 时 DAO 包一层 [HookedReminderDao]，并把三方流水塞进同一个 order。 */
        recordOrder: Boolean = false,
        hook: ((HookedReminderDao) -> Unit)? = null
    ): Fixture {
        val base = FakeReminderDao()
        val order = mutableListOf<String>()
        val dao: ReminderDao = if (recordOrder) {
            HookedReminderDao(base, order).also { hook?.invoke(it) }
        } else {
            base
        }
        val scheduler = FakeAlarmScheduling()
        val pendingDao = FakePendingMessageDao()
        if (recordOrder) {
            scheduler.order = order
            pendingDao.order = order
        }
        base.insert(
            ReminderEntity(
                id = 42L,
                type = ReminderType.FOLLOWUP,
                taskDesc = "吃药",
                triggerTime = 0L,
                state = ReminderState.PENDING,
                followupInterval = interval,
                stopCondition = "用户说吃完了",
                attemptCount = attemptCount
            )
        )
        val memories = FakeMemoryShortDao()
        val queue = PendingMessageQueue(pendingDao, now = { now })
        val port = FollowupPortImpl(
            dao,
            FollowupAlarms(scheduler),
            queue,
            memories,
            nowMillis = { now }
        )
        return Fixture(base, pendingDao, scheduler, memories, port)
    }

    /** 往队列里投一条该链的追问（真实编码，保证前缀与生产一致）。 */
    private suspend fun enqueueFollowup(
        pendingDao: FakePendingMessageDao,
        queue: PendingMessageQueue,
        reminderId: Long,
        attempt: Int = 1
    ): Long = queue.enqueue(
        type = PendingMessageType.FOLLOW_UP,
        content = PendingMessageContent.Followup(reminderId, attempt, "吃药"),
        triggerTime = 0L
    )

    /** 该链自然语言形态的短记忆行（真实数据里 memory_short 存的就是自由中文，不是编码）。 */
    private suspend fun seedMemory(memories: FakeMemoryShortDao, content: String): Long =
        memories.insert(MemoryShortEntity(content = content, timestamp = 0L))

    // 11. followupInterval = null → now + 15*60_000
    @Test
    fun advance_defaults_to_15_minutes() = runBlocking {
        val f = fixture(interval = null)
        f.port.advance(42L)
        assertEquals(1_000L + 15 * 60_000L, f.reminders.getById(42L)!!.triggerTime)
    }

    // 12. followupInterval = 30 → now + 30*60_000
    @Test
    fun advance_uses_entity_interval() = runBlocking {
        val f = fixture(interval = 30)
        f.port.advance(42L)
        assertEquals(1_000L + 30 * 60_000L, f.reminders.getById(42L)!!.triggerTime)
    }

    // 13. advance 绝不 incrementAttempt（防「提前触顶」）
    @Test
    fun advance_does_not_increment_attempt() = runBlocking {
        val f = fixture(attemptCount = 2)
        f.port.advance(42L)
        assertEquals(2, f.reminders.getById(42L)!!.attemptCount)
    }

    // 14. 先 cancel(timeout)、再排 followup（顺序断言）
    @Test
    fun advance_cancels_timeout_then_schedules_followup() = runBlocking {
        val f = fixture()
        f.port.advance(42L)
        assertEquals("cancel:${AlarmCodes.timeout(42L)}", f.scheduler.events[0])
        assertTrue(f.scheduler.events[1].startsWith("schedule:${AlarmCodes.followup(42L)}:"))
        assertTrue(f.scheduler.events[1].contains(":FOLLOWUP:"))
        assertEquals(2, f.scheduler.events.size)
    }

    // 15. complete / abandon：状态正确 + cancelAll 三个 code 全取消
    @Test
    fun complete_and_abandon_write_state_and_cancel_all_three_codes() = runBlocking {
        val a = fixture()
        a.port.complete(42L)
        assertEquals(ReminderState.DONE, a.reminders.getById(42L)!!.state)
        assertEquals(AlarmCodes.cancelAll(42L), a.scheduler.events.map { it.removePrefix("cancel:").toInt() })

        val b = fixture()
        b.port.abandon(42L)
        assertEquals(ReminderState.ABANDONED, b.reminders.getById(42L)!!.state)
        assertEquals(AlarmCodes.cancelAll(42L), b.scheduler.events.map { it.removePrefix("cancel:").toInt() })
    }

    // 16. active()：attemptCount=0 → null；attemptCount=1 → 非空且字段正确
    @Test
    fun active_respects_attempt_count() = runBlocking {
        val zero = fixture(attemptCount = 0)
        assertNull(zero.port.active())

        val one = fixture(attemptCount = 1)
        val active = one.port.active()
        assertNotNull(active)
        assertEquals(42L, active!!.reminderId)
        assertEquals("吃药", active.task)
        assertEquals("用户说吃完了", active.stopCondition)
    }

    // 17. 收口新增：complete() 清掉本链已入队的追问，且不误伤别的链（42 不能杀 420 / 7）
    @Test
    fun complete_cancels_only_queued_followups_of_this_chain() = runBlocking {
        val f = fixture()
        val mine = enqueueFollowup(f.pendingDao, PendingMessageQueue(f.pendingDao), 42L, attempt = 2)
        val neighbour = enqueueFollowup(f.pendingDao, PendingMessageQueue(f.pendingDao), 420L)
        val other = enqueueFollowup(f.pendingDao, PendingMessageQueue(f.pendingDao), 7L)

        f.port.complete(42L)

        val state = { id: Long -> f.pendingDao.items.first { it.id == id }.state }
        assertEquals(PendingMessageState.CANCELLED, state(mine))
        // 尾冒号守卫：FU_PREFIX + "42" 会误杀 followup:420:…，这里必须仍 pending
        assertEquals(PendingMessageState.PENDING, state(neighbour))
        assertEquals(PendingMessageState.PENDING, state(other))
    }

    // 18. 收口新增：abandon() 同样清队列（对话里说「算了不做了」也不该再被催）
    @Test
    fun abandon_cancels_queued_followups_of_this_chain() = runBlocking {
        val f = fixture()
        val queue = PendingMessageQueue(f.pendingDao)
        val mine = enqueueFollowup(f.pendingDao, queue, 42L)

        f.port.abandon(42L)

        assertEquals(PendingMessageState.CANCELLED, f.pendingDao.items.first { it.id == mine }.state)
        assertEquals(ReminderState.ABANDONED, f.reminders.getById(42L)!!.state)
    }

    // 19. 收口新增：顺序铁律 —— 落库 → 撤钟 → 清队列（对调会变成「链还 pending 但队列已空」的死链）
    @Test
    fun complete_writes_state_then_cancels_alarms_then_clears_queue() = runBlocking {
        val f = fixture(recordOrder = true)
        val queue = PendingMessageQueue(f.pendingDao)
        enqueueFollowup(f.pendingDao, queue, 42L)

        f.port.complete(42L)

        // 首项 "enqueue" 是上面播种那条追问的入队流水；下面三段才是 complete() 的顺序
        val expected = mutableListOf("enqueue", "updateState")
        AlarmCodes.cancelAll(42L).forEach { expected += "cancel:$it" }
        expected += "cancel" // FakePendingMessageDao.cancelPendingByContentPrefix 的流水
        assertEquals(expected, f.pendingDao.order!!)
    }

    // 20. 收口新增：落库失败时**不得**清队列 —— 否则链仍 pending、闹钟仍在，下次到点又入队
    @Test
    fun complete_does_not_clear_queue_when_state_write_fails() = runBlocking {
        val boom = IllegalStateException("db down")
        val f = fixture(recordOrder = true, hook = { it.failUpdateState = boom })
        val queue = PendingMessageQueue(f.pendingDao)
        val mine = enqueueFollowup(f.pendingDao, queue, 42L)

        try {
            f.port.complete(42L)
            fail("updateState 抛异常时应向上抛（不静默吞）")
        } catch (e: IllegalStateException) {
            assertEquals("db down", e.message)
        }

        assertEquals(PendingMessageState.PENDING, f.pendingDao.items.first { it.id == mine }.state)
        assertTrue(f.scheduler.cancels.isEmpty())
    }

    // 21. 2026-11-20：complete() 删掉这条链的短记忆，不误删别的主题
    @Test
    fun complete_deletes_only_this_chains_memory() = runBlocking {
        val f = fixture() // taskDesc = "吃药"
        // 真机里记忆是自由中文：一条来自指令提醒原文，一条来自记忆提取器
        val mine = seedMemory(f.memories, "吃药，晚上九点一次")
        val extracted = seedMemory(f.memories, "用户要求每天按时吃药，我答应盯着他")
        val unrelated = seedMemory(f.memories, "用户喜欢喝冰美式，下午常去楼下咖啡店")

        f.port.complete(42L)

        assertTrue("提醒原文行应删", f.memories.items.none { it.id == mine })
        assertTrue("提取器写的同主题行也应删", f.memories.items.none { it.id == extracted })
        assertNotNull("无关记忆不许动", f.memories.items.firstOrNull { it.id == unrelated })
    }

    // 22. 2026-11-20：abandon() 同样删记忆
    @Test
    fun abandon_deletes_this_chains_memory() = runBlocking {
        val f = fixture()
        val mine = seedMemory(f.memories, "提醒我吃药")

        f.port.abandon(42L)

        assertTrue(f.memories.items.none { it.id == mine })
    }

    // 23. 2026-11-20：查不到任务（行已删/坏数据）→ 不删、不报错，主流程照旧
    @Test
    fun complete_with_missing_reminder_row_skips_memory_delete() = runBlocking {
        val f = fixture()
        val mine = seedMemory(f.memories, "提醒我吃药")
        f.reminders.deleteAll() // 把 reminders 行删光，模拟查不到 taskDesc

        f.port.complete(42L)

        assertNotNull("查不到 task 就不该动记忆", f.memories.items.firstOrNull { it.id == mine })
    }

    // 24. 2026-11-20：记忆 DAO 没注入（旧装配）→ 不删，不崩
    @Test
    fun complete_without_memory_dao_does_nothing_extra() = runBlocking {
        val base = FakeReminderDao()
        base.insert(
            ReminderEntity(
                id = 42L, type = ReminderType.FOLLOWUP, taskDesc = "吃药", triggerTime = 0L,
                state = ReminderState.PENDING, attemptCount = 1
            )
        )
        val port = FollowupPortImpl(
            base,
            FollowupAlarms(FakeAlarmScheduling()),
            PendingMessageQueue(FakePendingMessageDao()),
            memoryShortDao = null,
            nowMillis = { 1_000L }
        )

        port.complete(42L)

        assertEquals(ReminderState.DONE, base.getById(42L)!!.state)
    }
}