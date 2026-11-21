package com.companion.chat.data.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * requestCode 分配表单测（任务 3.5）。
 *
 * 公式是**持久化契约**：已排的 PendingIntent 按 requestCode 匹配，改公式会让旧闹钟取消不掉。
 */
class AlarmCodesTest {

    @Test
    fun first_uses_reminder_id_directly() {
        assertEquals(42, AlarmCodes.first(42L))
        assertEquals(0, AlarmCodes.first(0L))          // id=0 是真 id，不是哨兵
        assertEquals(-1, AlarmCodes.first(-1L))        // 负数理论上不该有，但公式不崩
    }

    @Test
    fun followup_shifts_by_million() {
        assertEquals(1_000_042, AlarmCodes.followup(42L))
    }

    @Test
    fun timeout_shifts_by_two_million() {
        assertEquals(2_000_042, AlarmCodes.timeout(42L))
    }

    @Test
    fun cancel_all_returns_all_three_codes() {
        assertEquals(
            listOf(42, 1_000_042, 2_000_042),
            AlarmCodes.cancelAll(42L),
        )
    }

    /** 环境检查用固定 code，不占 reminderId 空间。 */
    @Test
    fun env_check_is_fixed() {
        assertEquals(999_999, AlarmCodes.ENV_CHECK)
    }

    /** 三个 code 互不重叠（同一 reminderId 下不会撞车）。 */
    @Test
    fun codes_do_not_collide() {
        val id = 42L
        assertEquals(3, setOf(AlarmCodes.first(id), AlarmCodes.followup(id), AlarmCodes.timeout(id)).size)
    }
}