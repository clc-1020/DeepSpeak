package com.companion.chat.data.tts

import com.companion.chat.data.power.WakeLockHandle
import com.companion.chat.data.power.WakeLockPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReadAloudController 朗读+WakeLock 单测（假引擎/假锁）。任务 4.2 */
class ReadAloudControllerTest {

    private class FakeWakeLock : WakeLockHandle {
        val events = mutableListOf<String>()
        var lastTimeoutMs: Long = -1
        private var held = false
        override fun acquire(timeoutMs: Long) {
            lastTimeoutMs = timeoutMs
            held = true
            events.add("acquire")
        }
        override fun release() { if (held) { held = false; events.add("release") } }
        override fun isHeld(): Boolean = held
    }

    private class FakeEngine(private val result: Result<Unit>) : TtsEngine {
        var calls = 0
        override suspend fun speak(text: String): Result<Unit> { calls++; return result }
        override fun stop() {}
        override fun isAvailable() = true
    }

    private fun controller(engine: FakeEngine, lock: FakeWakeLock): ReadAloudController {
        val manager = TtsManager(
            settingsProvider = { TtsSettings(enabled = true, deepSeekEnabled = false) },
            systemEngine = engine,
            deepSeekEngine = FakeEngine(Result.success(Unit))
        )
        return ReadAloudController(manager, lock)
    }

    @Test
    fun speak_acquires_with_tts_timeout_constant() = runBlocking {
        // 任务 6.1 审查项：TTS 的持锁时长必须是 10 分钟常量，而不是随手写的字面量
        val lock = FakeWakeLock()
        controller(FakeEngine(Result.success(Unit)), lock).speak("长段落")
        assertEquals(10 * 60_000L, lock.lastTimeoutMs)
        assertEquals(WakeLockPolicy.TTS_TIMEOUT_MS, lock.lastTimeoutMs)
        assertEquals(ReadAloudController.MAX_WAKE_MS, lock.lastTimeoutMs)
    }

    @Test
    fun tts_timeout_is_documented_exception_to_five_minute_policy() {
        assertEquals(WakeLockPolicy.TTS_TIMEOUT_MS, ReadAloudController.MAX_WAKE_MS)
        assertTrue(ReadAloudController.MAX_WAKE_MS > WakeLockPolicy.TIMEOUT_MS)
    }

    @Test
    fun speak_acquires_and_releases_wakelock() = runBlocking {
        val lock = FakeWakeLock()
        val engine = FakeEngine(Result.success(Unit))
        val r = controller(engine, lock).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, engine.calls)
        assertEquals(listOf("acquire", "release"), lock.events)
        assertFalse(lock.isHeld())
    }

    @Test
    fun releases_even_on_playback_failure() = runBlocking {
        val lock = FakeWakeLock()
        val engine = FakeEngine(Result.failure(RuntimeException("播放失败")))
        val r = controller(engine, lock).speak("你好")
        assertTrue(r.isFailure)
        assertEquals(listOf("acquire", "release"), lock.events)
        assertFalse(lock.isHeld())
    }

    @Test
    fun blank_text_does_not_touch_wakelock() = runBlocking {
        val lock = FakeWakeLock()
        val engine = FakeEngine(Result.success(Unit))
        val r = controller(engine, lock).speak("   ")
        assertTrue(r.isSuccess)
        assertTrue(lock.events.isEmpty())
        assertEquals(0, engine.calls)
    }

    @Test
    fun stop_releases_wakelock() {
        val lock = FakeWakeLock()
        controller(FakeEngine(Result.success(Unit)), lock).stop()
        assertFalse(lock.isHeld())
    }
}