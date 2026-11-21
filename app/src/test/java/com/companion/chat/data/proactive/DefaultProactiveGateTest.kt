package com.companion.chat.data.proactive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** DefaultProactiveGate 接线单测（provider→规则映射）。任务 3.4 */
class DefaultProactiveGateTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 20)
    private fun at(hour: Int, minute: Int): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun gate(
        nowMillis: Long,
        last: Long? = null,
        sentToday: Int = 0,
        reply: Double? = null,
        config: ProactiveGateConfig = ProactiveGateConfig()
    ) = DefaultProactiveGate(
        now = { nowMillis },
        configProvider = { config },
        lastInteractionProvider = { last },
        spontaneousSentTodayProvider = { sentToday },
        replyRateProvider = { reply },
        zone = zone,
        log = {} // 单测不接 android.util.Log
    )

    @Test
    fun allows_when_clear() = runBlocking {
        assertTrue(gate(at(12, 0)).allow())
    }

    @Test
    fun blocks_on_cooldown_from_provider() = runBlocking {
        val now = at(12, 0)
        assertFalse(gate(now, last = now - 1_800_000L).allow())
    }

    @Test
    fun blocks_on_daily_limit_from_provider() = runBlocking {
        // 默认上限已改为 8，这里显式给 3 才能测「打满即拦」
        val cfg = ProactiveGateConfig(dailyLimit = 3)
        assertFalse(gate(at(12, 0), sentToday = 3, config = cfg).allow())
    }

    @Test
    fun blocks_on_low_reply_when_already_sent() = runBlocking {
        assertFalse(gate(at(12, 0), sentToday = 1, reply = 0.05).allow())
    }

    @Test
    fun blocks_in_quiet_hours_when_enabled() = runBlocking {
        val cfg = ProactiveGateConfig(dndEnabled = true, dndStartMinute = 23 * 60, dndEndMinute = 7 * 60)
        assertFalse(gate(at(2, 0), config = cfg).allow())
    }
}
