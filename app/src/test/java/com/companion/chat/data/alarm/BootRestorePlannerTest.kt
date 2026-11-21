package com.companion.chat.data.alarm

import com.companion.chat.data.local.entity.ReminderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重启恢复计划器单测（B9，纯逻辑）。
 *
 * 这段逻辑原本焊在 `CompanionApp.restoreAlarmsAfterBoot()` 里（需要 Android Context，跑不了
 * JVM 测试），导致「`triggerTime > now` 按原时间重排」这条分支只能靠真机凑数据。
 * 抽成纯函数后两条分支都在这里被钉住。
 *
 * V2.2.3：入参从 Pair 升级为 Triple(id, triggerTime, type)，新增按 type 分配 code/kind 的两条。
 */
class BootRestorePlannerTest {

    /** 固定基准时刻，不依赖时区/当前时间。 */
    private val now = 1_795_000_000_000L

    private fun row(id: Long, at: Long, type: String = ReminderType.INSTRUCTION) =
        Triple(id, at, type)

    @Test
    fun future_trigger_keeps_original_time() {
        val at = now + 30 * 60_000L
        val plan = BootRestorePlanner.plan(listOf(row(7L, at)), now = now)
        assertEquals(1, plan.size)
        assertEquals(at, plan[0].atMillis)
        assertFalse(plan[0].overdue)
    }

    @Test
    fun trigger_equal_to_now_is_overdue() {
        val plan = BootRestorePlanner.plan(listOf(row(7L, now)), now = now, delayMs = 5_000L)
        assertEquals(now + 5_000L, plan[0].atMillis)
        assertTrue(plan[0].overdue)
    }

    @Test
    fun past_trigger_goes_to_now_plus_delay() {
        val plan = BootRestorePlanner.plan(
            listOf(row(7L, now - 3_600_000L)),
            now = now,
            delayMs = 5_000L
        )
        assertEquals(now + 5_000L, plan[0].atMillis)
        assertTrue(plan[0].overdue)
    }

    @Test
    fun multiple_overdue_are_staggered_by_one_second() {
        val plan = BootRestorePlanner.plan(
            pending = listOf(row(1L, now - 1_000L), row(2L, now - 2_000L), row(3L, now - 3_000L)),
            now = now,
            delayMs = 5_000L,
            staggerMs = 1_000L
        )
        assertEquals(3, plan.size)
        assertEquals(now + 5_000L, plan[0].atMillis)
        assertEquals(now + 6_000L, plan[1].atMillis)
        assertEquals(now + 7_000L, plan[2].atMillis)
        assertTrue(plan.all { it.overdue })
    }

    @Test
    fun future_rows_do_not_consume_stagger_slots() {
        // 混合场景：未来行插在中间，不能占用 overdue 的错开槽
        val far = now + 600_000L
        val plan = BootRestorePlanner.plan(
            pending = listOf(row(1L, now - 1_000L), row(2L, far), row(3L, now - 2_000L)),
            now = now,
            delayMs = 5_000L,
            staggerMs = 1_000L
        )
        assertEquals(now + 5_000L, plan[0].atMillis)
        assertEquals(far, plan[1].atMillis)
        assertEquals(now + 6_000L, plan[2].atMillis)
        assertFalse(plan[1].overdue)
    }

    @Test
    fun empty_pending_gives_empty_plan() {
        assertTrue(BootRestorePlanner.plan(emptyList(), now = now).isEmpty())
    }

    @Test
    fun request_code_uses_first_id() {
        val plan = BootRestorePlanner.plan(listOf(row(42L, now - 1)), now = now)
        assertEquals(AlarmCodes.first(42L), plan[0].requestCode)
    }

    @Test
    fun order_is_preserved_for_index_alignment() {
        // 调用方用 plan[i] 与 pending[i] 一一对应，顺序必须稳定
        val plan = BootRestorePlanner.plan(listOf(row(9L, now - 1), row(3L, now - 2)), now = now)
        assertEquals(AlarmCodes.first(9L), plan[0].requestCode)
        assertEquals(AlarmCodes.first(3L), plan[1].requestCode)
    }

    // ---- V2.2.3 新增（坑 B：followup 行恢复不能被当成首钟指令闹钟） ----

    @Test
    fun followup_type_uses_followup_code_and_kind() {
        val plan = BootRestorePlanner.plan(
            listOf(row(42L, now + 60_000L, ReminderType.FOLLOWUP)),
            now = now
        )
        assertEquals(AlarmCodes.followup(42L), plan[0].requestCode)
        assertEquals(AlarmKind.FOLLOWUP, plan[0].kind)
    }

    @Test
    fun instruction_type_keeps_first_code_and_instruction_kind() {
        val plan = BootRestorePlanner.plan(
            listOf(row(43L, now + 60_000L, ReminderType.INSTRUCTION)),
            now = now
        )
        assertEquals(AlarmCodes.first(43L), plan[0].requestCode)
        assertEquals(AlarmKind.INSTRUCTION, plan[0].kind)
    }
}
