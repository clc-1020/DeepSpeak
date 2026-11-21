package com.companion.chat.data.power

import com.companion.chat.domain.settings.DndSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WakeLockPolicy 常量 + 持锁判据单测。任务 6.1 */
class WakeLockPolicyTest {

    // ---------- 超时策略 ----------

    @Test
    fun default_timeout_is_five_minutes() {
        assertEquals(5 * 60_000L, WakeLockPolicy.TIMEOUT_MS)
    }

    @Test
    fun receiver_timeout_is_much_shorter_than_worker_timeout() {
        assertEquals(10_000L, WakeLockPolicy.RECEIVER_TIMEOUT_MS)
        assertTrue(WakeLockPolicy.RECEIVER_TIMEOUT_MS < WakeLockPolicy.TIMEOUT_MS)
    }

    @Test
    fun tts_timeout_is_the_documented_exception() {
        assertEquals(10 * 60_000L, WakeLockPolicy.TTS_TIMEOUT_MS)
        assertTrue(WakeLockPolicy.TTS_TIMEOUT_MS > WakeLockPolicy.TIMEOUT_MS)
    }

    @Test
    fun tags_are_unique_and_prefixed() {
        assertEquals(WakeLockPolicy.ALL_TAGS.size, WakeLockPolicy.ALL_TAGS.toSet().size)
        assertTrue(WakeLockPolicy.ALL_TAGS.all { it.startsWith("companion:") })
    }

    @Test
    fun tags_cover_every_holder() {
        assertTrue(WakeLockPolicy.TAG_CHECK in WakeLockPolicy.ALL_TAGS)
        assertTrue(WakeLockPolicy.TAG_DELIVERY in WakeLockPolicy.ALL_TAGS)
        assertTrue(WakeLockPolicy.TAG_REMINDER in WakeLockPolicy.ALL_TAGS)
        assertTrue(WakeLockPolicy.TAG_TTS in WakeLockPolicy.ALL_TAGS)
        // B9：开机恢复也是持锁方，漏进 ALL_TAGS 就查不到它的残留
        assertTrue(WakeLockPolicy.TAG_BOOT in WakeLockPolicy.ALL_TAGS)
    }

    // ---------- 持锁判据（纯逻辑） ----------

    private val quiet = DndSettings(enabled = true, suppressNotification = true)  // 不允许陪聊出声

    @Test
    fun screen_on_releases_lock() {
        val d = WakeLockPolicyEvaluator.evaluate(screenOn = true, incomingCall = false, systemDndActive = false)
        assertFalse(d.shouldKeep)
        assertEquals(PolicyState.SCREEN_ON, d.state)
    }

    @Test
    fun incoming_call_releases_lock() {
        val d = WakeLockPolicyEvaluator.evaluate(screenOn = false, incomingCall = true, systemDndActive = false)
        assertFalse(d.shouldKeep)
        assertEquals(PolicyState.INCOMING_CALL, d.state)
    }

    @Test
    fun system_dnd_with_chat_blocked_releases_lock() {
        val d = WakeLockPolicyEvaluator.evaluate(
            screenOn = false, incomingCall = false, systemDndActive = true, dndSettings = quiet
        )
        assertFalse(d.shouldKeep)
        assertEquals(PolicyState.DND_BLOCK, d.state)
    }

    @Test
    fun system_dnd_but_chat_allowed_keeps_lock() {
        val allowed = DndSettings(enabled = true, suppressNotification = false)
        assertTrue(allowed.allowChatInDnd)
        val d = WakeLockPolicyEvaluator.evaluate(
            screenOn = false, incomingCall = false, systemDndActive = true, dndSettings = allowed
        )
        assertTrue(d.shouldKeep)
        assertEquals(PolicyState.KEEP_WAKELOCK, d.state)
    }

    @Test
    fun screen_off_no_call_no_dnd_keeps_lock() {
        val d = WakeLockPolicyEvaluator.evaluate(screenOn = false, incomingCall = false, systemDndActive = false)
        assertTrue(d.shouldKeep)
        assertEquals(PolicyState.KEEP_WAKELOCK, d.state)
    }

    @Test
    fun screen_on_wins_over_everything() {
        val d = WakeLockPolicyEvaluator.evaluate(
            screenOn = true, incomingCall = true, systemDndActive = true, dndSettings = quiet
        )
        assertEquals(PolicyState.SCREEN_ON, d.state)
    }

    @Test
    fun call_wins_over_dnd() {
        val d = WakeLockPolicyEvaluator.evaluate(
            screenOn = false, incomingCall = true, systemDndActive = true, dndSettings = quiet
        )
        assertEquals(PolicyState.INCOMING_CALL, d.state)
    }

    @Test
    fun allow_chat_in_dnd_is_derived_from_suppress_switch() {
        assertFalse(DndSettings(enabled = true, suppressNotification = true).allowChatInDnd)
        assertTrue(DndSettings(enabled = true, suppressNotification = false).allowChatInDnd)
        // 默认「区间内彻底安静」（suppress=true）→ 不允许陪聊出声
        assertFalse(DndSettings.DISABLED.allowChatInDnd)
    }

    @Test
    fun evaluator_logs_reason() {
        val logs = mutableListOf<String>()
        WakeLockPolicyEvaluator.evaluate(
            screenOn = true, incomingCall = false, systemDndActive = false, log = { logs.add(it) }
        )
        assertEquals(1, logs.size)
        assertTrue(logs.first().contains("释放"))
    }

    @Test
    fun diagnostic_snapshot_shape() {
        val snap = DiagnosticSnapshot(
            rom = com.companion.chat.domain.power.RomFamily.MIUI,
            screenOn = false,
            incomingCall = false,
            systemDndActive = false,
            allowChatInDnd = true,
            ignoringBatteryOptimizations = true,
            shouldKeepWakeLock = true,
            policyState = PolicyState.KEEP_WAKELOCK
        )
        assertTrue(snap.shouldKeepWakeLock)
        assertEquals(PolicyState.KEEP_WAKELOCK, snap.policyState)
        assertTrue(snap.ignoringBatteryOptimizations)
    }
}