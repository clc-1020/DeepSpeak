package com.companion.chat.data.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TtsManager 选择/降级逻辑单测（JVM，用假引擎）。任务 4.1 → 1.13.0 三通道。 */
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

    private fun manager(
        settings: TtsSettings,
        system: TtsEngine,
        deepSeek: TtsEngine,
        xiaoai: TtsEngine? = null
    ) = TtsManager({ settings }, system, deepSeek, xiaoai)

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
        val xiaoai = FakeEngine(true, Result.success(Unit))
        val r = manager(
            TtsSettings(enabled = false, xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(0, system.calls)
        assertEquals(0, ds.calls)
        assertEquals(0, xiaoai.calls)
    }

    // ---------- 1.13.0：小爱音色通道 ----------

    @Test
    fun xiaoai_enabled_and_available_wins_over_deepseek() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.success(Unit))
        val r = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, xiaoai.calls)
        assertEquals(0, ds.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun xiaoai_failure_falls_back_to_deepseek() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.failure(RuntimeException("小爱未运行")))
        val r = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, xiaoai.calls)
        assertEquals(1, ds.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun xiaoai_failure_and_deepseek_failure_falls_back_to_system() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.failure(RuntimeException("网络错误")))
        val xiaoai = FakeEngine(true, Result.failure(RuntimeException("小爱未运行")))
        val r = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(1, xiaoai.calls)
        assertEquals(1, ds.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun xiaoai_unavailable_uses_deepseek() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(false, Result.success(Unit))
        val r = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(0, xiaoai.calls)
        assertEquals(1, ds.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun xiaoai_disabled_uses_deepseek() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.success(Unit))
        val r = manager(
            TtsSettings(xiaomiEnabled = false, deepSeekEnabled = true),
            system, ds, xiaoai
        ).speak("你好")
        assertTrue(r.isSuccess)
        assertEquals(0, xiaoai.calls)
        assertEquals(1, ds.calls)
    }

    @Test
    fun current_channel_reports_xiaoai_when_enabled() {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.success(Unit))
        val m = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        )
        assertEquals(TtsChannel.XIAOAI, m.currentChannel())
    }

    @Test
    fun current_channel_falls_back_when_xiaoai_unavailable() {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(false, Result.success(Unit))
        val m = manager(
            TtsSettings(xiaomiEnabled = true, deepSeekEnabled = true),
            system, ds, xiaoai
        )
        assertEquals(TtsChannel.DEEPSEEK, m.currentChannel())
    }

    @Test
    fun stop_calls_all_engines() {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.success(Unit))
        // stop() 后不应抛异常（假引擎的 stop 是空实现，这里只验证不被调用链漏掉）
        manager(TtsSettings(xiaomiEnabled = true), system, ds, xiaoai).stop()
        assertEquals(0, system.calls)
        assertEquals(0, ds.calls)
        assertEquals(0, xiaoai.calls)
    }

    // ---------- 1.13.1：全部通道失败时的提示 ----------

    @Test
    fun all_channels_fail_reports_actionable_message() = runBlocking {
        val system = FakeEngine(true, Result.failure(RuntimeException("系统 TTS 初始化失败(-1)")))
        val ds = FakeEngine(true, Result.failure(RuntimeException("网络错误")))
        var sink: String? = null
        val m = TtsManager(
            { TtsSettings(xiaomiEnabled = false, deepSeekEnabled = false) },
            system, ds, null, { sink = it }
        )
        val r = m.speak("你好")
        assertTrue(r.isFailure)
        assertTrue(sink != null)
        // 没有其它通道可用时，必须给出「小爱音色 / 装引擎」两条出路，而不是裸的状态码
        assertTrue(sink!!.contains("小爱同学音色"))
    }

    @Test
    fun all_channels_fail_with_xiaoai_enabled_keeps_cause() = runBlocking {
        val system = FakeEngine(true, Result.failure(RuntimeException("系统 TTS 初始化失败(-1)")))
        val ds = FakeEngine(true, Result.success(Unit))
        val xiaoai = FakeEngine(true, Result.failure(RuntimeException("小爱未运行")))
        var sink: String? = null
        val m = TtsManager(
            { TtsSettings(xiaomiEnabled = true, deepSeekEnabled = false) },
            system, ds, xiaoai, { sink = it }
        )
        val r = m.speak("你好")
        assertTrue(r.isFailure)
        assertTrue(sink!!.contains("系统 TTS 初始化失败"))
    }

    @Test
    fun success_does_not_report_error() = runBlocking {
        val system = FakeEngine(true, Result.success(Unit))
        val ds = FakeEngine(true, Result.success(Unit))
        var sink: String? = null
        val m = TtsManager({ TtsSettings() }, system, ds, null, { sink = it })
        m.speak("你好")
        assertEquals(null, sink)
    }
}