package com.companion.chat.data.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** STT 门面单测：开关是 lambda，必须每轮现读，且关闭时不构造/不调用请求。任务 V2.3.3 */
class SttManagerTest {

    private class FakeAsr : AsrPort {
        var calls = 0
        var lastWav: ByteArray? = null
        var result: AsrResult = AsrResult.Success("识别成功", 1234)

        override suspend fun transcribe(wav: ByteArray): AsrResult {
            calls++
            lastWav = wav
            return result
        }
    }

    @Test
    fun disabled_returns_failure_and_never_calls_asr() = runBlocking {
        val asr = FakeAsr()
        val manager = SttManager(asr, { false })
        assertEquals(AsrResult.Failure("语音已关闭"), manager.transcribe(ByteArray(1)))
        assertEquals(0, asr.calls)
    }

    @Test
    fun enabled_lambda_flips_at_runtime_on_same_instance() = runBlocking {
        val asr = FakeAsr()
        var enabled = false
        val manager = SttManager(asr, { enabled })
        val wav = ByteArray(8) { it.toByte() }

        assertEquals(AsrResult.Failure("语音已关闭"), manager.transcribe(wav))
        assertEquals(0, asr.calls)

        enabled = true
        assertEquals(AsrResult.Success("识别成功", 1234), manager.transcribe(wav))
        assertEquals(1, asr.calls)
        assertSame(wav, asr.lastWav)
    }

    @Test
    fun enabled_passes_through_asr_result_unchanged() = runBlocking {
        val asr = FakeAsr().apply { result = AsrResult.Failure("网络异常，请检查连接") }
        val manager = SttManager(asr, { true })
        assertEquals(AsrResult.Failure("网络异常，请检查连接"), manager.transcribe(ByteArray(2)))
        assertEquals(1, asr.calls)
    }

    @Test
    fun empty_wav_is_rejected_without_calling_asr() = runBlocking {
        val asr = FakeAsr()
        val manager = SttManager(asr, { true })
        assertEquals(AsrResult.Failure("没录到声音，请重试"), manager.transcribe(ByteArray(0)))
        assertEquals(0, asr.calls)
    }

    @Test
    fun isEnabled_reflects_lambda_each_call() = runBlocking {
        var on = false
        val manager = SttManager(FakeAsr(), { on })
        assertEquals(false, manager.isEnabled())
        on = true
        assertEquals(true, manager.isEnabled())   // 同一实例，现查
    }
}
