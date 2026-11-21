package com.companion.chat.data.speech

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * DeepSeek ASR 上传 + 解析 JVM 单测。任务 V2.3.3
 *
 * 走真实 OkHttp + MockWebServer：URL / 头 / multipart 体按线上协议断言；
 * 本地拦截类用例断言 `server.requestCount == 0`（省钱省时间，不发请求）。
 */
class DeepSeekAsrClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private var apiKey: String? = "sk-test"
    private lateinit var asr: DeepSeekAsrClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        asr = newAsr(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newAsr(
        baseUrl: String,
        client: OkHttpClient = http,
        keyProvider: () -> String? = { apiKey },
    ): DeepSeekAsrClient = DeepSeekAsrClient(client, keyProvider, baseUrl.trimEnd('/'))

    /** 标准 44 字节头 + 指定 PCM；[declaredDataSize] 可故意与实到字节不符以测时长拦截。 */
    private fun wav(
        channels: Int = 1,
        sampleRate: Int = 16000,
        bits: Int = 16,
        dataBytes: ByteArray = ByteArray(3200),
        declaredDataSize: Int = dataBytes.size,
    ): ByteArray = WavHeader.buildWavHeader(declaredDataSize, sampleRate, channels, bits) + dataBytes

    private fun enqueueOk(text: String, durationMs: Int = 3200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"request_id":"req-1","text":"$text","duration_ms":$durationMs}""")
        )
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeAscii(out: ByteArray, offset: Int, s: String) {
        for (i in s.indices) out[offset + i] = s[i].code.toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) out[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }

    /**
     * 带 LIST 块的 WAV：LIST size=5 + 1 字节 pad，使 data 载荷起点变成 46 而非 44。
     * 老代码按固定偏移 40 读 dataSize 会读到 dataHeader 中间（审查探针复现点）。
     */
    private fun wavWithListChunk(dataSize: Int, dataBytes: ByteArray): ByteArray {
        val fmt = ByteArray(24)
        writeAscii(fmt, 0, "fmt ")
        writeU32(fmt, 4, 16L)
        writeU16(fmt, 8, 1)      // audioFormat = PCM
        writeU16(fmt, 10, 1)     // channels = 1
        writeU32(fmt, 12, 16000L)
        writeU32(fmt, 16, 32000L)
        writeU16(fmt, 20, 2)
        writeU16(fmt, 22, 16)

        val list = ByteArray(14) // id(4) + size(4) + 5 字节内容 + 1 字节 pad
        writeAscii(list, 0, "LIST")
        writeU32(list, 4, 5L)
        writeAscii(list, 8, "INFO!")
        list[13] = 0

        val dh = ByteArray(8)
        writeAscii(dh, 0, "data")
        writeU32(dh, 4, dataSize.toLong())

        val body = fmt + list + dh + dataBytes
        val out = ByteArray(12 + body.size)
        writeAscii(out, 0, "RIFF")
        writeU32(out, 4, (4 + body.size).toLong())
        writeAscii(out, 8, "WAVE")
        body.copyInto(out, 12)
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /** 从 multipart 原始字节里定位上传的 WAV（"RIFF" 不会出现在 boundary 里）。 */
    private fun uploadedWav(body: ByteArray): ByteArray {
        val marker = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        for (i in 0..body.size - 4) {
            if (body[i] == marker[0] && body[i + 1] == marker[1] &&
                body[i + 2] == marker[2] && body[i + 3] == marker[3]
            ) {
                return body.copyOfRange(i, body.size)
            }
        }
        throw AssertionError("multipart 体里没有 WAV")
    }

    // ---------- 正常路径 ----------

    @Test
    fun success_parses_text_and_duration() = runBlocking {
        enqueueOk("你好呀", 3200)
        val result = asr.transcribe(wav())
        assertTrue(result is AsrResult.Success)
        val ok = result as AsrResult.Success
        assertEquals("你好呀", ok.text)
        assertEquals(3200, ok.durationMs)
    }

    @Test
    fun empty_text_returns_retry_hint() = runBlocking {
        enqueueOk("")
        assertEquals(AsrResult.Failure("没听清，请再说一次"), asr.transcribe(wav()))
    }

    @Test
    fun blank_text_returns_retry_hint() = runBlocking {
        enqueueOk("   ")
        assertEquals(AsrResult.Failure("没听清，请再说一次"), asr.transcribe(wav()))
    }

    @Test
    fun malformed_json_does_not_throw() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        val result = asr.transcribe(wav())
        assertTrue(result is AsrResult.Failure)
    }

    // ---------- HTTP 错误映射（六条） ----------

    private fun assertHttpReason(code: Int, reason: String) = runBlocking {
        // OkHttp 按 RFC 会对 408 自动重试一次，MockWebServer 要备两份响应才能观察到最终映射。
        repeat(if (code == 408) 2 else 1) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
        }
        assertEquals(AsrResult.Failure(reason), asr.transcribe(wav()))
    }

    @Test fun http_400_maps_to_format_hint() = assertHttpReason(400, "录音格式有问题，请重试")
    @Test fun http_401_maps_to_api_key_hint() = assertHttpReason(401, "API Key 无效，请在设置里检查")
    @Test fun http_403_maps_to_api_key_hint() = assertHttpReason(403, "API Key 无效，请在设置里检查")
    @Test fun http_408_maps_to_timeout_hint() = assertHttpReason(408, "上传超时，请检查网络后重试")
    @Test fun http_413_maps_to_too_long_hint() =
        assertHttpReason(413, "录音太长（超过 2 分钟），请分段说")
    @Test fun http_429_maps_to_rate_limit_hint() = assertHttpReason(429, "说话太频繁，歇一下再试")
    @Test fun http_503_maps_to_busy_hint() = assertHttpReason(503, "服务正忙，稍后再试")
    @Test fun http_504_maps_to_busy_hint() = assertHttpReason(504, "服务正忙，稍后再试")

    // ---------- 连接失败 ----------

    @Test
    fun connection_failure_returns_failure_without_throw() = runBlocking {
        val dead = MockWebServer().also { it.start() }
        val deadUrl = dead.url("/").toString()
        dead.shutdown()
        val shortClient = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()
        val result = newAsr(deadUrl, client = shortClient).transcribe(wav())
        assertEquals(AsrResult.Failure("网络异常，请检查连接"), result)
    }

    // ---------- 协议：URL / 头 / 体 ----------

    @Test
    fun request_url_ends_with_v1_tools_asr() = runBlocking {
        enqueueOk("ok")
        asr.transcribe(wav())
        assertEquals("/v1/tools/asr", server.takeRequest().path)
    }

    @Test
    fun request_carries_bearer_authorization() = runBlocking {
        enqueueOk("ok")
        asr.transcribe(wav())
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun request_body_is_multipart_with_file_part_speech_wav() = runBlocking {
        enqueueOk("ok")
        asr.transcribe(wav())
        val request = server.takeRequest()
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"speech.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
    }

    // ---------- 本地前置拦截（requestCount 必须为 0） ----------

    @Test
    fun empty_wav_blocked_locally() = runBlocking {
        assertEquals(AsrResult.Failure("没录到声音，请重试"), asr.transcribe(ByteArray(0)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oversized_wav_blocked_locally() = runBlocking {
        val huge = ByteArray(8 * 1024 * 1024 + 1)
        assertEquals(
            AsrResult.Failure("录音太长（超过 2 分钟），请分段说"),
            asr.transcribe(huge),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun duration_over_120s_blocked_locally() = runBlocking {
        // 16k / 单声道 / 16bit = 32000 B/s；121 秒声明 3,872,000 字节，实到数据只有 32 字节。
        val declared = 16_000 * 1 * 2 * 121
        val fakeLong = wav(dataBytes = ByteArray(32), declaredDataSize = declared)
        assertEquals(
            AsrResult.Failure("录音太长（超过 2 分钟），请分段说"),
            asr.transcribe(fakeLong),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun non_16bit_blocked_locally() = runBlocking {
        assertEquals(
            AsrResult.Failure("录音格式有问题，请重试"),
            asr.transcribe(wav(bits = 8)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun truncated_header_blocked_locally() = runBlocking {
        assertEquals(
            AsrResult.Failure("录音格式有问题，请重试"),
            asr.transcribe(ByteArray(20)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missing_api_key_blocked_locally() = runBlocking {
        apiKey = null
        assertEquals(
            AsrResult.Failure("未配置 API Key，请先在设置里填写"),
            asr.transcribe(wav()),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missing_api_key_is_reported_before_wav_checks() = runBlocking {
        // 改进项：没配 key 时先提示去设置页，不必先解析 WAV / 报「录音太长」。
        apiKey = null
        assertEquals(
            AsrResult.Failure("未配置 API Key，请先在设置里填写"),
            asr.transcribe(ByteArray(8 * 1024 * 1024 + 1)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sample_rate_beyond_int_range_is_rejected_without_throw() = runBlocking {
        // 审查复现点：0xFFFFFFFF 作为 u32 能过 Long 校验，toInt() 变 -1 会让 WavHeader.require 抛 IAE。
        val broken = wav(channels = 2, dataBytes = ByteArray(16)).copyOf()
        writeU32(broken, 24, 0xFFFF_FFFFL)
        assertEquals(
            AsrResult.Failure("录音格式有问题，请重试"),
            asr.transcribe(broken),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun list_chunk_does_not_corrupt_duration_parsing() = runBlocking {
        // 审查复现点：data 载荷起点是 46，固定偏移 40 会读到 dataHeader 中间。
        enqueueOk("带 LIST")
        val result = asr.transcribe(wavWithListChunk(3200, ByteArray(3200)))
        assertTrue(result is AsrResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun list_chunk_with_overlong_declared_data_is_still_blocked() = runBlocking {
        val declared = 16_000 * 1 * 2 * 121 // 121 秒
        assertEquals(
            AsrResult.Failure("录音太长（超过 2 分钟），请分段说"),
            asr.transcribe(wavWithListChunk(declared, ByteArray(32))),
        )
        assertEquals(0, server.requestCount)
    }

    // ---------- 双声道 → 左声道 ----------

    @Test
    fun stereo_upload_is_deinterleaved_to_left_mono() = runBlocking {
        val pcm = ByteArray(4 * 4) // 4 帧：L(1000+i), R(9000+i)
        for (i in 0 until 4) {
            writeU16(pcm, i * 4, 1000 + i)
            writeU16(pcm, i * 4 + 2, 9000 + i)
        }
        enqueueOk("立体声")
        val result = asr.transcribe(wav(channels = 2, dataBytes = pcm))
        assertTrue(result is AsrResult.Success)

        val uploaded = uploadedWav(server.takeRequest().body.readByteArray())
        assertEquals(1, u16(uploaded, 22))           // channels 改为 1
        assertEquals(32000L, u32(uploaded, 28))      // byteRate = 16000*1*16/8
        assertEquals(2, u16(uploaded, 32))           // blockAlign = 1*16/8
        assertEquals(16, u16(uploaded, 34))
        assertEquals((pcm.size / 2).toLong(), u32(uploaded, 40)) // dataSize 重算
        for (i in 0 until 4) {
            assertEquals(1000 + i, u16(uploaded, 44 + i * 2)) // 只保留左声道
        }
    }

    @Test
    fun stereo_with_unpaired_frames_is_rejected_locally() = runBlocking {
        // 6 字节 = 1.5 帧，无法整帧抽左声道 → 立即 Failure，不直传双声道原始字节。
        val broken = wav(channels = 2, dataBytes = ByteArray(6))
        assertEquals(
            AsrResult.Failure("录音格式有问题，请重试"),
            asr.transcribe(broken),
        )
        assertEquals(0, server.requestCount)
    }

    // ---- 2026-11-20：Base URL 必须跟随设置页（真机「聊天正常、语音报网络异常」后补）----

    @Test
    fun base_url_provider_overrides_default_and_strips_trailing_v1() = runBlocking {
        enqueueOk("你好")
        val viaConfig = DeepSeekAsrClient(
            http,
            { apiKey },
            baseUrl = "https://never-used.example.com",
            // 用户把 Base URL 填成聊天惯用的 `…/v1` 时，不能拼成 `/v1/v1/tools/asr`
            baseUrlProvider = { server.url("/v1").toString() },
        )

        assertTrue(viaConfig.transcribe(wav()) is AsrResult.Success)
        assertEquals("/v1/tools/asr", server.takeRequest().path)
    }

    @Test
    fun base_url_provider_trailing_slash_is_normalized() = runBlocking {
        enqueueOk("你好")
        val viaConfig = DeepSeekAsrClient(
            http,
            { apiKey },
            baseUrl = "https://never-used.example.com",
            baseUrlProvider = { server.url("/").toString() + "/" },
        )

        assertTrue(viaConfig.transcribe(wav()) is AsrResult.Success)
        assertEquals("/v1/tools/asr", server.takeRequest().path)
    }

    @Test
    fun blank_base_url_provider_falls_back_to_constructor_default() = runBlocking {
        enqueueOk("你好")
        val viaConfig = DeepSeekAsrClient(
            http,
            { apiKey },
            baseUrl = server.url("/").toString().trimEnd('/'),
            baseUrlProvider = { "   " },
        )

        assertTrue(viaConfig.transcribe(wav()) is AsrResult.Success)
        assertEquals("/v1/tools/asr", server.takeRequest().path)
    }
}
