package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.ConversationWriter
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import com.companion.chat.domain.proactive.ProactiveMessageGenerator
import com.companion.chat.domain.proactive.ProactiveNotification
import com.companion.chat.domain.proactive.ProactiveNotifier
import com.companion.chat.domain.proactive.ProactiveSpeaker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 送达层勿扰顺延单测。任务 7.2 */
class ProactiveDeliveryDndTest {

    private class FakeDao : PendingMessageDao {
        val items = ArrayList<PendingMessageEntity>()
        private var seq = 0L

        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
            items.add(withId); return withId.id
        }

        override suspend fun nextDue(now: Long): PendingMessageEntity? =
            items.filter { it.state == PendingMessageState.PENDING && it.triggerTime <= now }
                .sortedWith(
                    compareByDescending<PendingMessageEntity> { it.priority }
                        .thenBy { it.triggerTime }
                        .thenBy { it.id }
                )
                .firstOrNull()

        override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }

        override suspend fun getByState(state: String): List<PendingMessageEntity> =
            items.filter { it.state == state }

        override fun observeAll(): Flow<List<PendingMessageEntity>> = emptyFlow()

        override suspend fun transitionState(id: Long, newState: String, validFrom: List<String>): Int {
            val i = items.indexOfFirst { it.id == id && it.state in validFrom }
            if (i < 0) return 0
            items[i] = items[i].copy(state = newState); return 1
        }

        override suspend fun updateState(id: Long, state: String): Int {
            val i = items.indexOfFirst { it.id == id }; if (i < 0) return 0
            items[i] = items[i].copy(state = state); return 1
        }

        override suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int {
            val i = items.indexOfFirst { it.id == id && it.state == PendingMessageState.PENDING }
            if (i < 0) return 0
            items[i] = items[i].copy(triggerTime = triggerTime); return 1
        }

        override suspend fun countPending(): Int = items.count { it.state == PendingMessageState.PENDING }
        override suspend fun countPendingByContent(content: String): Int = 0

        override suspend fun countAvailable(now: Long): Int =
            items.count { it.state == PendingMessageState.PENDING && it.triggerTime <= now }

        // V2.2.5：新增 DAO 方法，本测试只为满足接口
        override suspend fun cancelPendingByContentPrefix(prefix: String, newState: String): Int {
            var n = 0
            for (i in items.indices) {
                val row = items[i]
                if (row.state == PendingMessageState.PENDING && row.content.startsWith(prefix)) {
                    items[i] = row.copy(state = newState); n++
                }
            }
            return n
        }

        override suspend fun deleteAll() { items.clear() }
    }

    private class FakeGate(private val allow: Boolean) : ProactiveGate {
        var called = 0
        override suspend fun allow(category: ProactiveMessageCategory): Boolean { called++; return allow }
    }

    private class FakeGen(private val text: String?) : ProactiveMessageGenerator {
        var calls = 0
        override suspend fun generate(pending: PendingMessageEntity): String? {
            calls++; return text
        }
    }

    private class FakeWriter : ConversationWriter {
        override suspend fun appendAssistant(text: String, atMillis: Long): Long? = 1L
    }

    private class FakeNotifier : ProactiveNotifier {
        val shown = mutableListOf<ProactiveNotification>()
        override fun show(notification: ProactiveNotification) { shown.add(notification) }
    }

    private class FakeSpeaker : ProactiveSpeaker {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) { spoken.add(text) }
    }

    private val now = 1_000_000L
    private val dndEnd = now + 8 * 3_600_000L // 8 小时后结束

    private fun build(
        dao: FakeDao,
        gen: FakeGen,
        notifier: FakeNotifier,
        speaker: FakeSpeaker,
        gate: FakeGate = FakeGate(true),
        dndBlocks: () -> Boolean,
        dndBlocksInstruction: () -> Boolean = { false },
        endMillis: () -> Long? = { dndEnd },
        scheduled: MutableList<Long> = mutableListOf(),
        clock: () -> Long = { now }
    ) = ProactiveDeliveryRunner(
        queue = PendingMessageQueue(dao, clock),
        gate = gate,
        generator = gen,
        writer = FakeWriter(),
        notifier = notifier,
        speaker = speaker,
        now = clock,
        log = {},
        dndBlocksDelivery = dndBlocks,
        dndBlocksInstruction = dndBlocksInstruction,
        dndEndMillisProvider = endMillis,
        scheduleDeferredDelivery = { delay -> scheduled.add(delay) }
    )

    @Test
    fun dnd_defers_spontaneous_message_instead_of_dropping() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("hi"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        val id = PendingMessageQueue(dao) { now }.enqueue(
            PendingMessageType.CHITCHAT, "text:随便聊", now
        )
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { true }, scheduled = scheduled)
            .deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DND_DEFERRED, outcome)
        assertEquals(0, gen.calls)              // 没生成
        assertTrue(notifier.shown.isEmpty())    // 没弹通知
        assertEquals(listOf(dndEnd - now), scheduled) // 安排了区间结束再送
        val row = dao.items.first { it.id == id }
        assertEquals(PendingMessageState.PENDING, row.state)   // 没丢，还在队列里
        assertEquals(dndEnd, row.triggerTime)                  // 顺延到区间结束
    }

    /**
     * 回归（BUG-1）：勿扰默认**不压**用户自己设的指令提醒。
     *
     * AI 自发消息在区间内被顺延（[dnd_defers_spontaneous_message_instead_of_dropping]），
     * 但用户设的「6:30 叫我吃早餐」必须准时送达——闹钟不准时＝核心功能失效。
     */
    @Test
    fun dnd_does_not_defer_user_instruction_by_default() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("该起床了"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        queue.enqueue(PendingMessageType.INSTRUCTION, "instr:0:起床", now)
        // dndBlocks=true（AI 消息确实被压），但 dndBlocksInstruction 默认 false
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { true }).deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, outcome)
        assertEquals(1, gen.calls)
        assertEquals(1, notifier.shown.size)
    }

    /** 用户明确打开「压掉我的提醒」后，指令提醒才跟着顺延。 */
    @Test
    fun dnd_defers_instruction_only_when_user_opts_in() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("该起床了"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        val id = queue.enqueue(PendingMessageType.INSTRUCTION, "instr:0:起床", now)
        val outcome = build(
            dao, gen, notifier, FakeSpeaker(),
            dndBlocks = { true },
            dndBlocksInstruction = { true }
        ).deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DND_DEFERRED, outcome)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(dndEnd, dao.items.first { it.id == id }.triggerTime)
    }

    @Test
    fun dnd_defers_follow_up_too() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("做完了吗"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        val id = queue.enqueue(PendingMessageType.FOLLOW_UP, "text:作业写完了吗", now)
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { true }).deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DND_DEFERRED, outcome)
        assertEquals(dndEnd, dao.items.first { it.id == id }.triggerTime)
    }

    @Test
    fun without_dnd_block_message_delivers_normally() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("晚安"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        queue.enqueue(PendingMessageType.CHITCHAT, "text:晚安", now)
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { false }).deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, outcome)
        assertEquals(1, gen.calls)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun dnd_without_end_time_delivers_normally() = runBlocking {
        // end 为 null 表示「不在区间内/算不出来」→ 不能无限期卡住消息，照常送达
        val dao = FakeDao(); val gen = FakeGen("早"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        queue.enqueue(PendingMessageType.CHITCHAT, "text:早上好", now)
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { true }, endMillis = { null })
            .deliverNext()
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, outcome)
        assertEquals(1, notifier.shown.size)
    }

    @Test
    fun force_bypasses_dnd_deferral_for_test_mode() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("测试"); val notifier = FakeNotifier()
        val queue = PendingMessageQueue(dao) { now }
        val id = queue.enqueue(PendingMessageType.CHITCHAT, "text:x", now)
        val scheduled = mutableListOf<Long>()
        val outcome = build(dao, gen, notifier, FakeSpeaker(), dndBlocks = { true }, scheduled = scheduled)
            .deliverById(id, force = true)
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, outcome)
        assertTrue(scheduled.isEmpty())
        assertEquals(1, notifier.shown.size)
        assertNull(dao.items.firstOrNull { it.id == id && it.state == PendingMessageState.PENDING })
    }

    // ---- 复审补充：自发消息在勿扰期间是「丢弃」而非「顺延」 ----

    @Test
    fun gate_blocked_spontaneous_is_discarded_not_deferred() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("x"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        val queue = PendingMessageQueue(dao) { now }
        val id = queue.enqueue(PendingMessageType.CHITCHAT, "text:hi", now)

        val outcome = build(
            dao, gen, notifier, FakeSpeaker(),
            gate = FakeGate(false),   // 闸门拦截（勿扰时自发消息走这里）
            dndBlocks = { true },
            scheduled = scheduled
        ).deliverNext()

        assertEquals(ProactiveDeliveryOutcome.BLOCKED, outcome)
        assertEquals(0, gen.calls)              // 没生成
        assertTrue(notifier.shown.isEmpty())    // 没通知
        assertTrue(scheduled.isEmpty())         // 也没安排顺延
        assertEquals(PendingMessageState.DISCARDED, dao.items.first { it.id == id }.state)
    }

    // ---- 复审补充：顺延后「到时」真的会再送一次 ----

    @Test
    fun deferred_message_is_delivered_once_dnd_ends() = runBlocking {
        val dao = FakeDao(); val gen = FakeGen("早上好"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        var clock = now
        var inDnd = true

        val queue = PendingMessageQueue(dao) { clock }
        val id = queue.enqueue(PendingMessageType.INSTRUCTION, "instr:0:起床", clock)

        val runner = build(
            dao, gen, notifier, FakeSpeaker(),
            dndBlocks = { inDnd },
            // 这里测的是「顺延后到点会再送一次」的机制本身，所以模拟用户已打开「压掉我的提醒」；
            // 默认不压的行为由 dnd_does_not_defer_user_instruction_by_default 覆盖。
            dndBlocksInstruction = { inDnd },
            endMillis = { if (inDnd) dndEnd else null },
            scheduled = scheduled,
            clock = { clock }
        )

        // 第一次：勿扰中 → 顺延
        assertEquals(ProactiveDeliveryOutcome.DND_DEFERRED, runner.deliverNext())
        assertEquals(dndEnd, dao.items.first { it.id == id }.triggerTime)
        assertEquals(listOf(dndEnd - now), scheduled)

        // 时间跳到勿扰结束（延迟任务到点）
        clock = dndEnd
        inDnd = false

        // 第二次：同一条消息已在到点窗口 → 真送达
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(1, notifier.shown.size)
        assertEquals(PendingMessageState.SENT, dao.items.first { it.id == id }.state)
    }

    @Test
    fun dnd_end_in_the_past_is_not_scheduled() = runBlocking {
        // end <= now 说明算不出未来结束时刻（异常/边界）→ 不能把消息卡死，照常送
        val dao = FakeDao(); val gen = FakeGen("hi"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        val queue = PendingMessageQueue(dao) { now }
        queue.enqueue(PendingMessageType.CHITCHAT, "text:x", now)

        val outcome = build(
            dao, gen, notifier, FakeSpeaker(),
            dndBlocks = { true },
            endMillis = { now - 1_000L },
            scheduled = scheduled
        ).deliverNext()

        assertEquals(ProactiveDeliveryOutcome.DELIVERED, outcome)
        assertTrue(scheduled.isEmpty())
    }

    // ---- V2.1.7 验收 7：节日勿扰顺延的当天时效 ----

    /** 勿扰结束落到次日 → 节日已过期：discard，且绝不安排延迟送达。 */
    @Test
    fun festival_dnd_crossing_midnight_is_expired_and_discarded() = runBlocking {
        val zone = ZoneId.systemDefault()
        val night = LocalDate.of(2026, 2, 17).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val nextMorning = LocalDate.of(2026, 2, 18).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

        val dao = FakeDao(); val gen = FakeGen("春节快乐"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        val q = PendingMessageQueue(dao) { night }
        val id = q.enqueue(PendingMessageType.SPECIAL_DATE, "fest:春节", night)

        val outcome = build(
            dao, gen, notifier, FakeSpeaker(),
            dndBlocks = { true },
            endMillis = { nextMorning },
            scheduled = scheduled,
            clock = { night },
        ).deliverNext()

        assertEquals(ProactiveDeliveryOutcome.FESTIVAL_EXPIRED, outcome)
        assertEquals(PendingMessageState.DISCARDED, dao.items.first { it.id == id }.state)
        assertTrue(scheduled.isEmpty())
        assertEquals(0, gen.calls)
        assertTrue(notifier.shown.isEmpty())
    }

    /** 勿扰结束仍在当天 → 维持原来的 reschedule + DND_DEFERRED（不受节日时效影响）。 */
    @Test
    fun festival_dnd_same_day_is_rescheduled() = runBlocking {
        val zone = ZoneId.systemDefault()
        val morning = LocalDate.of(2026, 2, 17).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val evening = LocalDate.of(2026, 2, 17).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()

        val dao = FakeDao(); val gen = FakeGen("春节快乐"); val notifier = FakeNotifier()
        val scheduled = mutableListOf<Long>()
        val q = PendingMessageQueue(dao) { morning }
        val id = q.enqueue(PendingMessageType.SPECIAL_DATE, "fest:春节", morning)

        val outcome = build(
            dao, gen, notifier, FakeSpeaker(),
            dndBlocks = { true },
            endMillis = { evening },
            scheduled = scheduled,
            clock = { morning },
        ).deliverNext()

        assertEquals(ProactiveDeliveryOutcome.DND_DEFERRED, outcome)
        assertEquals(PendingMessageState.PENDING, dao.items.first { it.id == id }.state)
        assertEquals(evening, dao.items.first { it.id == id }.triggerTime)
        assertEquals(listOf(evening - morning), scheduled)
    }
}


