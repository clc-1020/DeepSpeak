package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryFeedback 打分三态纯逻辑单测。任务 2.8 */
class MemoryFeedbackTest {

    private val day = 86_400_000L

    // ---------- 回复 ----------

    @Test
    fun reply_adds_point_two() {
        assertEquals(1.2, MemoryFeedback.afterReply(1.0), 1e-9)
        assertEquals(0.5, MemoryFeedback.afterReply(0.3), 1e-9)
    }

    @Test
    fun reply_capped_at_weight_max() {
        // 已到上限再回复不超上限（防单条记忆无限膨胀霸榜）
        assertEquals(MemoryFeedback.WEIGHT_MAX, MemoryFeedback.afterReply(MemoryFeedback.WEIGHT_MAX), 1e-9)
        assertEquals(MemoryFeedback.WEIGHT_MAX, MemoryFeedback.afterReply(MemoryFeedback.WEIGHT_MAX - 0.1), 1e-9)
    }

    // ---------- 别提这个：第 1 次 → 冷却 30 天 ----------

    @Test
    fun first_dismiss_subtracts_half_and_sets_cooling() {
        val now = 1000L * day
        val r = MemoryFeedback.afterDismiss(
            currentWeight = 1.0, currentDismissCount = 0, currentCoolingUntil = null, nowMillis = now
        )
        assertEquals(0.5, r.weight, 1e-9)
        assertEquals(1, r.dismissCount)
        assertFalse(r.blacklisted)
        assertEquals(now + 30 * day, r.coolingUntil)
        // 此刻应处于冷却中
        assertTrue(MemoryFeedback.isSuppressed(r.blacklisted, r.coolingUntil, now))
    }

    @Test
    fun first_dismiss_weight_not_below_zero() {
        val r = MemoryFeedback.afterDismiss(
            currentWeight = 0.3, currentDismissCount = 0, currentCoolingUntil = null, nowMillis = 5000L
        )
        assertEquals(0.0, r.weight, 1e-9)
    }

    @Test
    fun cooling_expires_after_30_days() {
        val now = 1000L * day
        val until = now + 30 * day
        // 到期前一天仍抑制
        assertTrue(MemoryFeedback.isSuppressed(false, until, now + 29 * day))
        // 到期当天/之后恢复
        assertFalse(MemoryFeedback.isSuppressed(false, until, now + 30 * day + 1))
    }

    // ---------- 别提这个：第 2 次 → 永久拉黑 ----------

    @Test
    fun second_dismiss_blacklists_permanently() {
        val now = 2000L * day
        val r = MemoryFeedback.afterDismiss(
            currentWeight = 0.5, currentDismissCount = 1,
            currentCoolingUntil = now - 5 * day, nowMillis = now
        )
        assertEquals(2, r.dismissCount)
        assertTrue(r.blacklisted)
        assertEquals(0.0, r.weight, 1e-9) // 0.5 - 0.5
    }

    @Test
    fun blacklisted_stays_suppressed_forever() {
        val now = 9999L * day
        assertTrue(MemoryFeedback.isSuppressed(blacklisted = true, coolingUntil = null, nowMillis = now))
        assertTrue(MemoryFeedback.isSuppressed(blacklisted = true, coolingUntil = now - day, nowMillis = now))
    }

    @Test
    fun dismiss_after_blacklist_keeps_count_at_two() {
        // 已拉黑后再点（误操作/测试）：dismissCount 不再从 2 累加到 3、4…
        val r = MemoryFeedback.afterDismiss(
            currentWeight = 0.0, currentDismissCount = 2, currentCoolingUntil = null, nowMillis = 5000L * day
        )
        assertEquals(2, r.dismissCount)
        assertTrue(r.blacklisted)
    }

    @Test
    fun not_suppressed_when_no_flags() {
        assertFalse(MemoryFeedback.isSuppressed(false, null, 12345L))
        // 冷却已过的 null/旧时间也不算抑制
        assertFalse(MemoryFeedback.isSuppressed(false, 100L, 200L))
    }

    // ---------- 冷却态查询 ----------

    @Test
    fun is_cooling_active_helper() {
        assertTrue(MemoryFeedback.isCoolingActive(200L, 100L))
        assertFalse(MemoryFeedback.isCoolingActive(100L, 200L))
        assertFalse(MemoryFeedback.isCoolingActive(null, 100L))
    }
}
