package com.companion.chat.data.proactive

import com.companion.chat.data.calendar.FestivalProvider
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.AllowAllGate
import com.companion.chat.domain.proactive.FestivalDedupe
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * V2.1.7 节日钩子接入验收（8 条中的 1–6）。
 *
 * 用真 [PendingMessageQueue] + 内存假 DAO，保证 `hasPendingWithContent` 走的是
 * `countPendingByContent` 那条真实 SQL 语义，而不是假实现恒返回 0。
 */
class ProactiveCheckRunnerFestivalTest {

    /** 2026-02-17 本地零点；测试里所有 dedupe 键都钉在这一天。 */
    private val dayMillis =
        LocalDate.of(2026, 2, 17).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class FakeDao : PendingMessageDao {
        val items = ArrayList<PendingMessageEntity>()
        private var seq = 0L

        override suspend fun insert(message: PendingMessageEntity): Long {
            val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
            items.add(withId)
            return withId.id
        }

        override suspend fun nextDue(now: Long): PendingMessageEntity? = null
        override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByState(state: String): List<PendingMessageEntity> =
            items.filter { it.state == state }

        override fun observeAll(): Flow<List<PendingMessageEntity>> = emptyFlow()

        override suspend fun transitionState(
            id: Long, newState: String, validFrom: List<String>
        ): Int {
            val i = items.indexOfFirst { it.id == id && it.state in validFrom }
            if (i < 0) return 0
            items[i] = items[i].copy(state = newState)
            return 1
        }

        override suspend fun updateState(id: Long, state: String): Int {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0) return 0
            items[i] = items[i].copy(state = state)
            return 1
        }

        override suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int {
            val i = items.indexOfFirst { it.id == id && it.state == PendingMessageState.PENDING }
            if (i < 0) return 0
            items[i] = items[i].copy(triggerTime = triggerTime)
            return 1
        }

        override suspend fun countPending(): Int =
            items.count { it.state == PendingMessageState.PENDING }

        /** 与真实 DAO 一致：精确 content 匹配 + 只看 PENDING。 */
        override suspend fun countPendingByContent(content: String): Int =
            items.count { it.state == PendingMessageState.PENDING && it.content == content }

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

        override suspend fun deleteAll() {
            items.clear()
        }
    }

    private class RecordingGate(
        private val allowSpecial: Boolean = true,
        private val allowSpontaneous: Boolean = true,
    ) : ProactiveGate {
        val asked = mutableListOf<ProactiveMessageCategory>()
        override suspend fun allow(category: ProactiveMessageCategory): Boolean {
            asked += category
            return when (category) {
                ProactiveMessageCategory.SPECIAL_DATE -> allowSpecial
                else -> allowSpontaneous
            }
        }
    }

    private fun fakeMemoryDao(vararg contents: String): MemoryLongDao = object : MemoryLongDao {
        private val rows = contents.mapIndexed { i, c ->
            MemoryLongEntity(id = i + 1L, content = c, category = "special_date", createTime = 0L)
        }

        override suspend fun getByCategory(category: String): List<MemoryLongEntity> =
            if (category == "special_date") rows else emptyList()

        override suspend fun insert(memory: MemoryLongEntity): Long = 1L
        override suspend fun update(memory: MemoryLongEntity) {}
        override suspend fun delete(memory: MemoryLongEntity) {}
        override fun observeAll(): Flow<List<MemoryLongEntity>> = emptyFlow()
        override suspend fun getAll(): List<MemoryLongEntity> = rows
        override suspend fun getById(id: Long): MemoryLongEntity? = rows.firstOrNull { it.id == id }
        override suspend fun deleteAll() {}
    }

    private fun runner(
        dao: FakeDao,
        gate: ProactiveGate = AllowAllGate(),
        festival: String? = "春节",
        memoryCalls: () -> Unit = {},
        checkSent: suspend (String) -> Boolean = { false },
    ): ProactiveCheckRunner {
        val q = PendingMessageQueue(dao, now = { dayMillis })
        return ProactiveCheckRunner(
            gate = gate,
            recallFestival = { festival },
            recallHook = { memoryCalls(); 42L },
            queue = q,
            checkSent = checkSent,
            now = { dayMillis },
            log = {},
        )
    }

    /** 验收 1：有节日时不召回记忆钩子（且闸门只看 SPECIAL_DATE）。 */
    @Test
    fun festival_does_not_recall_memory_hook() = runBlocking {
        val dao = FakeDao()
        val gate = RecordingGate()
        var calls = 0
        val r = runner(dao, gate = gate, memoryCalls = { calls++ }).runOnce()

        assertEquals(ProactiveCheckOutcome.ENQUEUED, r.outcome)
        assertEquals(0, calls)
        assertEquals(listOf(ProactiveMessageCategory.SPECIAL_DATE), gate.asked)
        assertEquals(dao.items.single().id, r.pendingId)
    }

    /**
     * 验收 2：SpecialDateHit.User（用户生日）也走节日分支。
     * 这里走真实的 SpecialDateHookService → Matcher → firstOrNull()?.name 链路，
     * 覆盖「只取 is Festival 会让生日永远发不出去」那个坑。
     */
    @Test
    fun user_birthday_hit_goes_through_festival_branch() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { dayMillis })
        val service = SpecialDateHookService(
            memoryDao = fakeMemoryDao("生日 2026-02-17"),
            festivals = FestivalProvider(loader = { """{"lunar":[],"solar":[]}""" }),
        )
        var memoryCalls = 0
        val r = ProactiveCheckRunner(
            gate = AllowAllGate(),
            // 与 CompanionApp 的接线一模一样：两个子类都从基类取 name
            recallFestival = { service.specialDatesOn(LocalDate.of(2026, 2, 17)).firstOrNull()?.name },
            recallHook = { memoryCalls++; 42L },
            queue = q,
            now = { dayMillis },
            log = {},
        ).runOnce()

        assertEquals(ProactiveCheckOutcome.ENQUEUED, r.outcome)
        assertEquals(0, memoryCalls)
        val msg = dao.items.single()
        assertEquals(PendingMessageType.SPECIAL_DATE.code, msg.type)
        assertEquals(PendingMessageType.SPECIAL_DATE.priority, msg.priority) // 3，枚举自带
        assertEquals(PendingMessageContent.Festival("生日").encode(), msg.content)
    }

    /** 验收 3：同一节日当天第二次 → ALREADY_SENT，不再投队列。 */
    @Test
    fun same_festival_twice_a_day_is_already_sent() = runBlocking {
        val dao = FakeDao()
        var delivered = false // 模拟送达回调已写入 fes_sent 去重键
        val r = runner(dao, checkSent = { delivered })

        assertEquals(ProactiveCheckOutcome.ENQUEUED, r.runOnce().outcome)
        delivered = true
        val second = r.runOnce()

        assertEquals(ProactiveCheckOutcome.ALREADY_SENT, second.outcome)
        assertEquals(null, second.pendingId)
        assertEquals(1, PendingMessageQueue(dao, now = { dayMillis }).pendingCount())
    }

    /** 验收 4：队列已有同 content 的未发节日 → ALREADY_SENT（真 queue + 假 DAO）。 */
    @Test
    fun queued_same_content_is_already_sent() = runBlocking {
        val dao = FakeDao()
        val q = PendingMessageQueue(dao, now = { dayMillis })
        q.enqueue(
            type = PendingMessageType.SPECIAL_DATE,
            content = PendingMessageContent.Festival("春节"),
            triggerTime = dayMillis,
        )

        val r = runner(dao).runOnce()

        assertEquals(ProactiveCheckOutcome.ALREADY_SENT, r.outcome)
        assertEquals(1, q.pendingCount())
    }

    /** 验收 5：gate.allow(SPECIAL_DATE)=false → GATE_BLOCKED，队列为空，且不回退记忆分支。 */
    @Test
    fun gate_blocked_special_date_does_not_enqueue_or_fall_back() = runBlocking {
        val dao = FakeDao()
        val gate = RecordingGate(allowSpecial = false)
        var memoryCalls = 0
        val r = runner(dao, gate = gate, memoryCalls = { memoryCalls++ }).runOnce()

        assertEquals(ProactiveCheckOutcome.GATE_BLOCKED, r.outcome)
        assertEquals(0, PendingMessageQueue(dao, now = { dayMillis }).pendingCount())
        assertEquals(0, memoryCalls)
        assertEquals(listOf(ProactiveMessageCategory.SPECIAL_DATE), gate.asked)
    }

    /** 验收 6：checkSent 收到的是完整键 `fes_sent_春节_2026-02-17`。 */
    @Test
    fun check_sent_receives_full_dedupe_key() = runBlocking {
        val dao = FakeDao()
        val seen = ArrayList<String>()
        runner(dao, checkSent = { seen += it; false }).runOnce()

        assertEquals(listOf("fes_sent_春节_2026-02-17"), seen)
        assertEquals(FestivalDedupe.key("春节", dayMillis), seen.single())
    }
}
