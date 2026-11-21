package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.ConversationWriter
import com.companion.chat.domain.proactive.FestivalDedupe
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import com.companion.chat.domain.proactive.ProactiveMessageGenerator
import com.companion.chat.domain.proactive.ProactiveNotification
import com.companion.chat.domain.proactive.ProactiveNotifier
import com.companion.chat.domain.proactive.ProactiveSpeaker
import com.companion.chat.domain.proactive.ProactiveVoicePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** ProactiveDeliveryRunner 生成与送达编排单测（全假端口）。任务 3.5 */
class ProactiveDeliveryRunnerTest {

    private class FakeDao : PendingMessageDao {
        val items = ArrayList<PendingMessageEntity>()
        private var seq = 0L
        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
            items.add(withId); return withId.id
        }
        override suspend fun nextDue(now: Long): PendingMessageEntity? =
            items.filter { it.state == PendingMessageState.PENDING && it.triggerTime <= now }
                .sortedWith(compareByDescending<PendingMessageEntity> { it.priority }.thenBy { it.triggerTime }.thenBy { it.id })
                .firstOrNull()
        override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByState(state: String): List<PendingMessageEntity> = items.filter { it.state == state }
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
        override suspend fun generate(pending: PendingMessageEntity): String? = text
    }
    private class FakeWriter(private val convId: Long?) : ConversationWriter {
        var lastText: String? = null
        override suspend fun appendAssistant(text: String, atMillis: Long): Long? {
            lastText = text; return convId
        }
    }
    private class FakeNotifier : ProactiveNotifier {
        val shown = mutableListOf<ProactiveNotification>()
        override fun show(notification: ProactiveNotification) { shown.add(notification) }
    }
    private class FakeSpeaker : ProactiveSpeaker {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) { spoken.add(text) }
    }

    private val t0 = 1000L

    private fun queue(dao: FakeDao) = PendingMessageQueue(dao, now = { t0 })

    @Test
    fun empty_queue_returns_EMPTY() = runBlocking {
        val dao = FakeDao()
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = queue(dao), gate = FakeGate(true), generator = FakeGen("x"),
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(), now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.EMPTY, runner.deliverNext())
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun spontaneous_blocked_by_gate_discards() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        val gate = FakeGate(false)
        val gen = FakeGen("不该被调用")
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = gate, generator = gen, writer = FakeWriter(1L),
            notifier = FakeNotifier(), speaker = FakeSpeaker(), now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.BLOCKED, runner.deliverNext())
        assertEquals(PendingMessageState.DISCARDED, dao.items.single().state)
        assertEquals(1, gate.called)
    }

    @Test
    fun generation_failure_keeps_pending() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen(null), writer = FakeWriter(1L),
            notifier = FakeNotifier(), speaker = FakeSpeaker(), now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.GENERATION_FAILED, runner.deliverNext())
        assertEquals(PendingMessageState.PENDING, dao.items.single().state)
    }

    @Test
    fun success_delivers_persists_notifies_and_counts() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        val writer = FakeWriter(7L)
        val notifier = FakeNotifier()
        var delivered = 0
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("你好呀"),
            writer = writer, notifier = notifier, speaker = FakeSpeaker(),
            voicePolicy = ProactiveVoicePolicy { false },
            onDelivered = { delivered++ }, now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(PendingMessageState.SENT, dao.items.single().state)
        assertEquals("你好呀", writer.lastText)
        assertEquals(1, notifier.shown.size)
        assertEquals("你好呀", notifier.shown[0].body)
        assertEquals(7L, notifier.shown[0].conversationId)
        assertEquals(1, delivered)
    }

    /**
     * 审查 R1（V2.2.4）：追问/指令是用户自己建的，**不许**消耗「今日自发」额度。
     *
     * 一条追问链跑满 `maxAttempts=5` 就吃掉 5/8 的额度，成功/失败两种路径都会让用户
     * 当天的自发消息被 `DAILY_LIMIT` 拦死（与 `force_delivery_does_not_count_toward_daily_limit`
     * 记录的手动测试事故同构）。契约见 `ProactiveGateContext.spontaneousSentToday`
     * 「指令/追问/手动测试不计」。
     */
    @Test
    fun follow_up_delivery_does_not_count_toward_daily_limit() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(42L, 1, "背单词"),
            t0
        )
        var delivered = 0
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(false), generator = FakeGen("背单词背得怎么样啦"),
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(),
            onDelivered = { delivered++ }, now = { t0 }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals("追问不该计入自发额度", 0, delivered)
        // 反证：消息确实送达了（不是因为被拦掉才没计数）
        assertEquals(1, notifier.shown.size)
        assertEquals("背单词背得怎么样啦", notifier.shown[0].body)
    }

    /** 对照组：纯自发（CHITCHAT）送达必须计数，否则「每日上限」彻底失效。 */
    @Test
    fun spontaneous_delivery_still_counts() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        var delivered = 0
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("在忙吗"),
            writer = FakeWriter(1L), notifier = FakeNotifier(), speaker = FakeSpeaker(),
            onDelivered = { delivered++ }, now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(1, delivered)
    }

    /**
     * 审查 S1（V2.2.4）：`KNOWN_TAGS` 必须含 `followup:`。
     *
     * 合法的追问能 decode 成 `Followup`（不会进这条判据）；但**非法**追问
     * （`followup:0:1:x`、`followup:7`）会回落成 `PlainText(raw)`，若 `followup:` 不在名单里
     * 就判不出坏数据 → 送达层把 `followup:0:1:x` 原样当正文推给用户。
     */
    @Test
    fun unknown_tag_detection_covers_followup_tag() {
        // 非法追问 = 坏数据 → true（应丢弃）
        assertTrue(ProactiveDeliveryRunner.isUnknownTagContent("followup:0:1:x"))
        assertTrue(ProactiveDeliveryRunner.isUnknownTagContent("followup:7"))
        assertTrue(ProactiveDeliveryRunner.isUnknownTagContent("followup:abc:1:x"))
        // 合法追问 / 普通文本 / 其他已知 tag → false（不许误丢）
        assertFalse(
            ProactiveDeliveryRunner.isUnknownTagContent(
                PendingMessageContent.Followup(7L, 3, "背单词").encode()
            )
        )
        assertFalse(ProactiveDeliveryRunner.isUnknownTagContent("text:hi"))
        assertFalse(ProactiveDeliveryRunner.isUnknownTagContent("memory:1"))
        assertFalse(ProactiveDeliveryRunner.isUnknownTagContent("今天天气不错"))
    }

    /** 坏追问行必须被送达层丢弃，不能把内部格式当正文发出去（S1 的端到端版）。 */
    @Test
    fun malformed_followup_row_is_discarded_without_notifying() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        val id = q.enqueue(PendingMessageType.FOLLOW_UP, "followup:0:1:x", t0)
        val gen = FakeGen("不该生成")
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = gen,
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(),
            now = { t0 }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.BLOCKED, runner.deliverNext())
        assertEquals(PendingMessageState.DISCARDED, dao.items.first { it.id == id }.state)
        assertTrue(notifier.shown.isEmpty())
    }

    /**
     * 回归：测试模式（force=true）**不得**计入「今日已发自发消息」。
     *
     * 实测事故：在设置页连着点几次「立即触发一次」，每次 force 送达都 +1，
     * 当日计数顶到 11 而每日上限只有 3，之后每 2 小时的定时检查全部被
     * DAILY_LIMIT 拦死 —— 用户侧看到的就是「2 小时一次的主动消息完全不触发」。
     */
    @Test
    fun force_delivery_does_not_count_toward_daily_limit() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        val id = q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        var delivered = 0
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("测试消息"),
            writer = FakeWriter(1L), notifier = FakeNotifier(), speaker = FakeSpeaker(),
            onDelivered = { delivered++ }, now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverById(id, force = true))
        assertEquals("测试模式不应计入每日上限", 0, delivered)
    }

    @Test
    fun voice_policy_true_triggers_speak() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.CHITCHAT, "memory:1", t0)
        val speaker = FakeSpeaker()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("说一句"),
            writer = FakeWriter(1L), notifier = FakeNotifier(), speaker = speaker,
            voicePolicy = ProactiveVoicePolicy { true }, now = { t0 }, log = {}
        )
        runner.deliverNext()
        assertEquals(listOf("说一句"), speaker.spoken)
    }

    @Test
    fun instruction_bypasses_gate() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(PendingMessageType.INSTRUCTION, "叫我起床", t0)
        val gate = FakeGate(false) // 若被调用会拦截
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = gate, generator = FakeGen("该起床啦"),
            writer = FakeWriter(2L), notifier = notifier, speaker = FakeSpeaker(), now = { t0 }, log = {}
        )
        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(0, gate.called) // 指令跳过闸门
        assertEquals("该起床啦", notifier.shown[0].body)
    }

    /** V2.1.7 验收 8：记账键按 triggerTime（入队那天）派生，跨午夜送达也不错位。 */
    @Test
    fun festival_bookkeeping_uses_trigger_time_not_delivery_time() = runBlocking {
        val zone = ZoneId.systemDefault()
        val trigger = LocalDate.of(2026, 11, 20).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val deliveredAt = LocalDate.of(2026, 11, 21).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()

        val dao = FakeDao()
        // 队列的时钟必须跟着「送达时刻」走，否则 triggerTime 还没到，peekNext 直接返回 EMPTY
        val q = PendingMessageQueue(dao, now = { deliveredAt })
        q.enqueue(
            PendingMessageType.SPECIAL_DATE,
            PendingMessageContent.Festival("春节"),
            trigger,
        )
        val keys = ArrayList<String>()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("新年快乐"),
            writer = FakeWriter(1L), notifier = FakeNotifier(), speaker = FakeSpeaker(),
            // 与 CompanionApp.onDeliveredMessage 的节日分支同一段逻辑
            onDeliveredMessage = { msg ->
                val fest = PendingMessageContent.decode(msg.content) as? PendingMessageContent.Festival
                if (msg.type == PendingMessageType.SPECIAL_DATE.code && fest != null) {
                    keys += FestivalDedupe.key(fest.name, msg.triggerTime, zone)
                }
            },
            now = { deliveredAt }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(listOf("fes_sent_春节_2026-11-20"), keys)
    }

    /** V2.1.7 防御：decode 兜底成 PlainText 却以已知 tag 开头 → discard，不当正文发出去。 */
    @Test
    fun unknown_tag_content_is_discarded_without_generating() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        val id = q.enqueue(PendingMessageType.SPECIAL_DATE, "text:fest:春节", t0)
        val gen = FakeGen("不该生成")
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = gen,
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(),
            now = { t0 }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.BLOCKED, runner.deliverNext())
        assertEquals(PendingMessageState.DISCARDED, dao.items.first { it.id == id }.state)
        assertTrue(notifier.shown.isEmpty())
    }

    /**
     * V2.2.5：追问内容必须把 `reminderId` 带到通知上 —— 两个按钮的 PendingIntent extra 全靠它，
     * 漏传的表现是「按钮点下去什么都不发生」。
     */
    @Test
    fun followup_content_carries_reminder_id_to_notification() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(
            PendingMessageType.FOLLOW_UP,
            PendingMessageContent.Followup(9L, 2, "背单词"),
            t0
        )
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("背单词背得怎么样啦"),
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(),
            now = { t0 }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(9L, notifier.shown.single().followupReminderId)
        assertNull("追问通知不带记忆删除语义", notifier.shown.single().completeMemoryId)
    }

    /** 对照组：指令提醒仍只带记忆 id，`followupReminderId` 必须是 null（不许挂两个追问按钮）。 */
    @Test
    fun instruction_content_carries_memory_id_and_no_followup_id() = runBlocking {
        val dao = FakeDao(); val q = queue(dao)
        q.enqueue(
            PendingMessageType.INSTRUCTION,
            PendingMessageContent.Instruction("喝水", 77L),
            t0
        )
        val notifier = FakeNotifier()
        val runner = ProactiveDeliveryRunner(
            queue = q, gate = FakeGate(true), generator = FakeGen("该喝水啦"),
            writer = FakeWriter(1L), notifier = notifier, speaker = FakeSpeaker(),
            now = { t0 }, log = {}
        )

        assertEquals(ProactiveDeliveryOutcome.DELIVERED, runner.deliverNext())
        assertEquals(77L, notifier.shown.single().completeMemoryId)
        assertNull(notifier.shown.single().followupReminderId)
    }
}

