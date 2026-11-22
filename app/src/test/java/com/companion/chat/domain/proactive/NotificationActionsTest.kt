package com.companion.chat.domain.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知按钮计划的纯函数单测（V2.2.5）。
 *
 * 为什么测这一层：计划书要求「followupReminderId == null → 不加追问按钮」，而
 * `AndroidProactiveNotifier` 需要 Context + NotificationCompat.Builder，JVM 单测里
 * 直接 `RuntimeException: Stub!`（本项目无 Robolectric）。抽成纯整数运算后才能真断言。
 */
class NotificationActionsTest {

    @Test
    fun no_ids_produces_no_action() {
        assertTrue(NotificationActions.planFor(1, null, null).isEmpty())
    }

    @Test
    fun memory_id_produces_single_done() {
        val plan = NotificationActions.planFor(1, 77L, null)
        assertEquals(1, plan.size)
        assertEquals(NotificationActionKind.DONE, plan[0].kind)
    }

    @Test
    fun followup_id_produces_done_and_postpone() {
        val plan = NotificationActions.planFor(1, null, 9L)
        assertEquals(
            listOf(NotificationActionKind.DONE, NotificationActionKind.POSTPONE),
            plan.map { it.kind }
        )
    }

    /**
     * 两个 id 都非空时按追问处理。
     *
     * 更强的断言是「与只有追问时完全一致」：如果实现背地里多挂了一个「只删记忆」的按钮，
     * 这里会立刻不等（数量/顺序/requestCode 任一不同都不等）。
     */
    @Test
    fun both_ids_prefer_followup() {
        val both = NotificationActions.planFor(1, 77L, 9L)
        val followupOnly = NotificationActions.planFor(1, null, 9L)
        assertEquals(2, both.size)
        assertEquals(followupOnly, both)
    }

    /**
     * `PendingIntent.getBroadcast` 的复用判据是 `(requestCode, filterEquals, flags)`，
     * 而 `filterEquals` **不看 extras** —— 同一条通知内两个按钮 requestCode 相同会被覆写，
     * 点「已完成」实际触发 `postpone`。这条是 8.1 的守卫。
     */
    @Test
    fun request_codes_are_unique_within_one_notification() {
        val plan = NotificationActions.planFor(1, null, 9L)
        val codes = plan.map { it.requestCode }
        assertEquals("同一通知内 requestCode 不得重复", codes.size, codes.toSet().size)
    }

    /**
     * notificationId 来自 `msg.id % Int.MAX_VALUE`，可以非常接近上界。
     * `code()` 用 Long 相加再截断，两个 offset 的差值必须仍然保留。
     */
    @Test
    fun request_codes_stay_distinct_for_large_ids() {
        val plan = NotificationActions.planFor(Int.MAX_VALUE - 1, null, 9L)
        assertEquals(2, plan.size)
        assertNotEquals(plan[0].requestCode, plan[1].requestCode)
    }

    /** 1.9.4.2：长期记忆兜底 → 只有「别提这个」一个按钮（DISMISS），不带完成。 */
    @Test
    fun dismiss_long_id_produces_single_dismiss() {
        val plan = NotificationActions.planFor(
            notificationId = 1,
            completeMemoryId = null,
            followupReminderId = null,
            dismissLongMemoryId = 77L
        )
        assertEquals(1, plan.size)
        assertEquals(NotificationActionKind.DISMISS, plan[0].kind)
    }
}