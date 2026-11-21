package com.companion.chat.data.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TtsManager 选择/降级逻辑单测（JVM，用假引擎）。任务 4.1 */
class TtsManagerTest {

    private class FakeEngine(
        private val available: Boolean,
        private val result: Result<Unit>
    ) : TtsEngine {
        var calls = 0
        override suspend fun speak(text: String): Result<Unit> {
            calls++
            return result
        }

        override fun stop() {}
        override fun isAvailable() = available
    }

    private fun manager(settings: TtsSettings, system: TtsEngine, deepSeek: TtsEngine) =
        TtsManager({ settings }, system, deepSeek)

    @Test
    fun deepseek_disabled_uses_system() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val r = manager(TtsSettings(deepSeekEnabled = false), system, ds).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, system.calls)
        assertEquals(0, ds.calls)
    }

    @Test
    fun deepseek_enabled_success_uses_deepseek() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val r = manager(TtsSettings(deepSeekEnabled = true), system, ds).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, ds.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun deepseek_failure_falls_back_to_system() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.failure(RuntimeException("网络错误")))
        val r = manager(TtsSettings(deepSeekEnabled = true), system, ds).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, ds.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun deepseek_unavailable_uses_system() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(false, Result.success(Unit))
        val r = manager(TtsSettings(deepSeekEnabled = true), system, ds).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(0, ds.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun blank_text_no_call() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val r = manager(TtsSettings(deepSeekEnabled = true), system, ds).speak("   ")
        assertTrue(r.isSuccess)
        assertEquals(0, system.calls)
        assertEquals(0, ds.calls)
    }

    @Test
    fun master_switch_off_skips_all_engines() = runBlocking {
        // 任务 4.1 语音总开关：关闭时任何通道都不调用
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val r = manager(TtsSettings(enabled = false, deepSeekEnabled = true), system, ds).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(0, system.calls)
        assertEquals(0, ds.calls)
    }
}
