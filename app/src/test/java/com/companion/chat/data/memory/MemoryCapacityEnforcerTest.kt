package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.FakeMemoryShortDao
import com.companion.chat.data.local.dao.FakeReminderDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `MemoryCapacityEnforcer` 单测（2026-11-20）。
 *
 * 策略本身在 `ShortMemoryCapacityTest` 里测；这里只测**接线**：
 * 会不会真的删、会不会误伤活跃任务、没到阈值时是否零动作、失败是否静默。
 */
class MemoryCapacityEnforcerTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun mem(id: Long, content: String = "记忆$id", fadeScore: Double = id.toDouble(), ageDays: Long = 30) =
        MemoryShortEntity(id = id, content = content, timestamp = now - ageDays * day, fadeScore = fadeScore)

    private fun enforcer(
        short: MemoryShortDao,
        reminder: FakeReminderDao,
        logs: MutableList<String>? = null
    ) = MemoryCapacityEnforcer(
        shortDao = short,
        reminderDao = reminder,
        now = { now },
        log = { logs?.add(it) }
    )

    @Test
    fun evicts_twenty_when_at_capacity() = runBlocking {
        val short = FakeMemoryShortDao()
        (1L..50L).forEach { short.insert(mem(it)) }

        val removed = enforcer(short, FakeReminderDao()).enforceOnce()

        assertEquals(20, removed)
        assertEquals("剩 30 条", 30, short.items.size)
        // fadeScore 最高的（id 31..50）被删
        assertNull(short.items.firstOrNull { it.id == 50L })
        assertNotNull(short.items.firstOrNull { it.id == 1L })
    }

    @Test
    fun below_capacity_does_nothing() = runBlocking {
        val short = FakeMemoryShortDao()
        (1L..49L).forEach { short.insert(mem(it)) }

        assertEquals(0, enforcer(short, FakeReminderDao()).enforceOnce())
        assertEquals(49, short.items.size)
    }

    @Test
    fun active_followup_task_is_protected() = runBlocking {
        val short = FakeMemoryShortDao()
        short.insert(mem(99, content = "喝水，每隔1分钟提醒我一次", fadeScore = 9999.0))
        (1L..49L).forEach { short.insert(mem(it)) }

        val reminder = FakeReminderDao()
        reminder.insert(
            ReminderEntity(
                id = 7L, type = ReminderType.FOLLOWUP, taskDesc = "喝水",
                triggerTime = now, state = ReminderState.PENDING
            )
        )

        val removed = enforcer(short, reminder).enforceOnce()

        assertEquals(20, removed)
        assertNotNull("正在监督的任务记忆必须留着", short.items.firstOrNull { it.id == 99L })
    }

    @Test
    fun finished_reminder_does_not_protect_its_memory() = runBlocking {
        val short = FakeMemoryShortDao()
        short.insert(mem(99, content = "喝水，每隔1分钟提醒我一次", fadeScore = 9999.0))
        (1L..49L).forEach { short.insert(mem(it)) }

        val reminder = FakeReminderDao()
        reminder.insert(
            ReminderEntity(
                id = 7L, type = ReminderType.FOLLOWUP, taskDesc = "喝水",
                triggerTime = now, state = ReminderState.DONE // 已结束 → 不再保护
            )
        )

        enforcer(short, reminder).enforceOnce()

        assertNull("已结束的提醒不再享受保护", short.items.firstOrNull { it.id == 99L })
    }

    @Test
    fun failure_is_swallowed() = runBlocking {
        // 记忆表读失败 → 返回 0，不抛（否则会连累主对话/提醒送达）
        val broken = object : MemoryShortDao by FakeMemoryShortDao() {
            override suspend fun getAll(): List<MemoryShortEntity> = error("db down")
        }
        val logs = mutableListOf<String>()

        assertEquals(0, enforcer(broken, FakeReminderDao(), logs).enforceOnce())
        assertNotNull("失败要留日志", logs.firstOrNull { it.contains("淘汰失败") })
    }
}