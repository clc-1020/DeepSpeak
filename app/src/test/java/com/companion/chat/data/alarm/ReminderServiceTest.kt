package com.companion.chat.data.alarm

import com.companion.chat.data.local.entity.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ReminderService 编排单测（假 AlarmScheduling + 假落库）。任务 3.3 / B9
 *
 * B9 起必须覆盖「落库 → requestCode 由行 id 派生」，因为恢复路径只拿得到行 id：
 * 两边公式不一致就会双响、且将来 `cancelAll` 取消不掉。
 */
class ReminderServiceTest {

    private class FakeScheduler : AlarmScheduling {
        data class Call(
            val requestCode: Int,
            val at: Long,
            val task: String,
            val reminderId: Long? = null,
            val kind: AlarmKind = AlarmKind.INSTRUCTION,
        )
        val calls = mutableListOf<Call>()
        override fun schedule(
            requestCode: Int,
            triggerAtMillis: Long,
            taskDesc: String,
            reminderId: Long?,
            kind: AlarmKind,
        ) {
            calls.add(Call(requestCode, triggerAtMillis, taskDesc, reminderId, kind))
        }
        override fun cancel(requestCode: Int) { /* no-op */ }
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val ref = LocalDate.of(2026, 11, 20)
    private fun at(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 11, 21).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
            .toEpochMilli()

    private fun svc(
        sched: FakeScheduler,
        save: suspend (String, String, Long) -> Long = { _, _, _ -> 0L }
    ) = ReminderService(
        scheduler = sched,
        saveReminder = save,
        now = { ref.atTime(6, 0).atZone(zone).toInstant().toEpochMilli() },
        referenceDate = { ref },
        zone = zone
    )

    @Test
    fun instruction_with_time_schedules_alarm() = runBlocking {
        val sched = FakeScheduler()
        val r = svc(sched, save = { _, _, _ -> 42L }).scheduleFromText("明天7点叫我起床")
        assertNotNull(r)
        assertEquals(1, sched.calls.size)
        val call = sched.calls[0]
        assertEquals(at(7, 0), call.at)
        assertEquals("叫我起床", call.task)
        assertEquals(AlarmKind.INSTRUCTION, call.kind)
        assertEquals(AlarmCodes.first(42L), call.requestCode)
        assertEquals(42L, call.reminderId)
        assertEquals(42L, r!!.reminderId)
        assertEquals(AlarmCodes.first(42L), r.requestCode)
    }

    @Test
    fun casual_chat_does_not_schedule() = runBlocking {
        val sched = FakeScheduler()
        assertNull(svc(sched).scheduleFromText("今天天气不错"))
        assertTrue(sched.calls.isEmpty())
    }

    @Test
    fun reminder_word_but_no_time_does_not_schedule() = runBlocking {
        // 有提醒意图（叫我）但无时间点 → 不排，也不落库
        val sched = FakeScheduler()
        var saveCalls = 0
        assertNull(svc(sched, save = { _, _, _ -> saveCalls++; 1L }).scheduleFromText("明天叫我起床"))
        assertTrue(sched.calls.isEmpty())
        assertEquals(0, saveCalls)
    }

    @Test
    fun persists_before_scheduling_with_expected_fields() = runBlocking {
        val sched = FakeScheduler()
        val saved = mutableListOf<Triple<String, String, Long>>()
        svc(sched, save = { type, desc, at ->
            saved.add(Triple(type, desc, at))
            7L
        }).scheduleFromText("明天7点叫我起床")

        assertEquals(1, saved.size)
        assertEquals(ReminderType.INSTRUCTION, saved[0].first)
        assertEquals("叫我起床", saved[0].second)
        assertEquals(at(7, 0), saved[0].third)
        // 落库先于设钟（否则 requestCode 无从由 id 派生）
        assertEquals(1, sched.calls.size)
    }

    @Test
    fun save_failure_still_schedules_with_derived_request_code() = runBlocking {
        val sched = FakeScheduler()
        val r = svc(sched, save = { _, _, _ -> throw IllegalStateException("db down") })
            .scheduleFromText("明天7点叫我起床")

        // 落库失败不能连提醒本身一起丢：仍要响这一次
        assertNotNull(r)
        assertEquals(1, sched.calls.size)
        assertEquals((at(7, 0) % Int.MAX_VALUE).toInt(), sched.calls[0].requestCode)
        // 没有行 → 不传 reminderId（这条重启后不可恢复，是刻意的降级）
        assertNull(sched.calls[0].reminderId)
        assertEquals(0L, r!!.reminderId)
    }

    @Test
    fun same_save_result_overwrites_same_alarm() = runBlocking {
        // 同一条提醒重复设：同 id → 同 requestCode → 覆盖，不堆叠
        val sched = FakeScheduler()
        val s = svc(sched, save = { _, _, _ -> 5L })
        val r1 = s.scheduleFromText("明天7点叫我起床")
        val r2 = s.scheduleFromText("明天7点叫我起床")
        assertEquals(r1!!.requestCode, r2!!.requestCode)
        assertEquals(2, sched.calls.size)
        assertEquals(sched.calls[0].requestCode, sched.calls[1].requestCode)
        assertEquals(AlarmCodes.first(5L), sched.calls[0].requestCode)
    }

    @Test
    fun different_rows_get_different_request_codes() = runBlocking {
        // 语义变更（B9）：两条独立提醒 = 两个闹钟。
        // 旧实现用 triggerTime 派生，同刻同任务会互相覆盖 → 第二条被静默吞掉。
        val sched = FakeScheduler()
        var seq = 0L
        val s = svc(sched, save = { _, _, _ -> ++seq })
        s.scheduleFromText("明天7点叫我起床")
        s.scheduleFromText("明天7点叫我起床")
        assertEquals(2, sched.calls.size)
        assertNotEquals(sched.calls[0].requestCode, sched.calls[1].requestCode)
        assertEquals(AlarmCodes.first(1L), sched.calls[0].requestCode)
        assertEquals(AlarmCodes.first(2L), sched.calls[1].requestCode)
    }
}