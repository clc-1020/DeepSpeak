package com.companion.chat.data.local.dao

import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FakeReminderDao 行为单测（V2.2.1 验收）。
 * 全部使用 org.junit.Assert.*，禁止裸 assert。
 */
class FakeReminderDaoTest {

    private fun followup(
        id: Long = 0,
        state: String = ReminderState.PENDING,
        attemptCount: Int = 0,
        triggerTime: Long = 10_000L
    ) = ReminderEntity(
        id = id,
        type = ReminderType.FOLLOWUP,
        taskDesc = "吃早餐了吗",
        triggerTime = triggerTime,
        state = state,
        attemptCount = attemptCount
    )

    @Test
    fun incrementAttempt_accumulates() = runBlocking {
        val dao = FakeReminderDao()
        val id = dao.insert(followup(attemptCount = 0))

        dao.incrementAttempt(id)
        assertEquals(1, dao.getById(id)!!.attemptCount)
        dao.incrementAttempt(id)
        dao.incrementAttempt(id)
        assertEquals(3, dao.getById(id)!!.attemptCount)
        assertEquals(5, dao.getById(id)!!.maxAttempts) // 默认值未被改动
    }

    @Test
    fun incrementAttempt_ignores_unknown_id() = runBlocking {
        val dao = FakeReminderDao()
        val id = dao.insert(followup(attemptCount = 2))
        dao.incrementAttempt(999L)
        assertEquals(2, dao.getById(id)!!.attemptCount)
    }

    @Test
    fun activeFollowup_null_when_attemptCount_zero() = runBlocking {
        val dao = FakeReminderDao()
        dao.insert(followup(attemptCount = 0, triggerTime = 1L))
        assertNull("刚建、还没到点、attemptCount=0 不算活跃追问", dao.activeFollowup())
    }

    @Test
    fun activeFollowup_returns_row_when_attemptCount_positive() = runBlocking {
        val dao = FakeReminderDao()
        dao.insert(followup(attemptCount = 0, triggerTime = 1L))
        val id = dao.insert(followup(attemptCount = 1, triggerTime = 2L))

        val active = dao.activeFollowup()
        assertNotNull(active)
        assertEquals(id, active!!.id)
    }

    @Test
    fun activeFollowup_picks_earliest_triggerTime() = runBlocking {
        val dao = FakeReminderDao()
        dao.insert(followup(attemptCount = 2, triggerTime = 30L))
        val early = dao.insert(followup(attemptCount = 1, triggerTime = 10L))
        dao.insert(followup(attemptCount = 3, triggerTime = 20L))

        assertEquals(early, dao.activeFollowup()!!.id)
    }

    @Test
    fun activeFollowup_ignores_non_pending_and_non_followup() = runBlocking {
        val dao = FakeReminderDao()
        dao.insert(followup(attemptCount = 2, state = ReminderState.DONE))
        dao.insert(
            ReminderEntity(
                type = ReminderType.INSTRUCTION,
                taskDesc = "叫起床",
                triggerTime = 1L,
                attemptCount = 2
            )
        )
        assertNull(dao.activeFollowup())
    }

    @Test
    fun anyPendingFollowup_returns_row_when_attemptCount_zero() = runBlocking {
        val dao = FakeReminderDao()
        val id = dao.insert(followup(attemptCount = 0, triggerTime = 999_999L))
        // 关键回归：刚建、未到点、attemptCount=0 也必须被判为「有未结束的追问链」
        val found = dao.anyPendingFollowup()
        assertNotNull(found)
        assertEquals(id, found!!.id)
    }

    @Test
    fun anyPendingFollowup_null_when_no_pending_followup() = runBlocking {
        val dao = FakeReminderDao()
        dao.insert(followup(attemptCount = 3, state = ReminderState.CANCELLED))
        assertNull(dao.anyPendingFollowup())
    }
}
