package com.companion.chat.data.speech

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * DeepSeek 云端 ASR 客户端。任务 V2.3.3「DeepSeek ASR 实现（上传 + 解析）」
 *
 * 协议（计划书 §V2.3.3，`v3.4` 云端方案）：
 * - `POST {baseUrl}/v1/tools/asr`
 * - 头 `Authorization: Bearer <apiKey>`，key 每次调用现取（设置页改 key 立即生效，复用聊天 key）
 * - 体 multipart/form-data，文件字段名固定 `file`、文件名固定 `speech.wav`、MIME `audio/wav`
 * - 响应取 `text` / `duration_ms`，`text` 空白按「没听清」处理
 *
 * 所有失败（前置拦截、HTTP 错误、连接失败、超时、JSON 解析异常、WAV 解析异常）一律转
 * [AsrResult.Failure] 且文案可指导用户；任何异常都不向调用方抛出（[transcribe] 外层 runCatching 兜底）。
 *
 * 输入只接受 RIFF/WAVE 线性 PCM16：[AsrPort] 注释提到的 Ogg Opus 不在本实现路径内（V2.3.2
 * 录音链路只产出 WAV），传入 Ogg 会按「录音格式有问题，请重试」返回，不会静默误解析。
 *
 * [client] 由容器注入**专用短超时**实例（connect 10s / write 30s / read 60s / call 90s），
 * 不复用聊天的 120s 流式 client。
 */
class DeepSeekAsrClient(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * 生产应传 `{ configRepository.load().baseUrl }`（2026-11-20 补）。
     *
     * 原来只吃构造里那个默认常量：用户在设置页把 Base URL 改成别的端点后，
     * **聊天走新端点、语音仍打 deepseek 官方**。表现就是「聊天正常、语音报网络异常」，
     * 而且因为下面 catch 里不记异常，日志里一条线索都没有。默认 null = 用 [baseUrl]（测试与旧调用零改动）。
     */
    private val baseUrlProvider: () -> String? = { null },
) : AsrPort {

    /** fmt 块里需要的四个字段。 */
    private data class Fmt(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Long,
        val bitsPerSample: Int,
    )

    /**
     * WAV 解析结果。
     *
     * [dataSize] 是 data chunk **声明**的长度（用于 120s 拦截），[dataStart] 是 data 载荷的实际文件偏移，
     * [available] 是文件里真正可用的字节数（用于抽左声道，防止声明值超出文件导致越界）。
     * 不硬编码 44 字节：手机录音常在 fmt 与 data 之间插 LIST / fact 块。
     */
    private data class WavInfo(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataSize: Long,
        val dataStart: Int,
        val available: Int,
    )

    override suspend fun transcribe(wav: ByteArray): AsrResult = runCatching {
        transcribeInternal(wav)
    }.getOrDefault(AsrResult.Failure(BAD_RESPONSE_REASON))

    private suspend fun transcribeInternal(wav: ByteArray): AsrResult {
        // key 最前置：没配置时连 WAV 都不用看，直接提示用户去设置页填写，
        // 否则「无 key + 超长录音」会先报「录音太长」，用户按提示分段说完还是不能用。
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isEmpty()) return AsrResult.Failure("未配置 API Key，请先在设置里填写")

        // ---- 上传前本地拦截：省钱省时间，命中即返回，requestCount 必须为 0 ----
        if (wav.isEmpty()) return AsrResult.Failure("没录到声音，请重试")
        if (wav.size > MAX_BYTES) return AsrResult.Failure(TOO_LONG_REASON)

        val info = parseWavInfo(wav) ?: return AsrResult.Failure(BAD_FORMAT_REASON)

        /*
         * 时长用 data chunk 声明的 dataSize，而非实到字节数：
         * 超长样本可以在极小的字节数组里「声明」超长时长，若按实到字节算就会绕过 120s 上限。
         * 反过来，单声道路径仍原样上传真实字节，不让声明值影响实际载荷（不做截断/补齐）。
         */
        val bytesPerFrame = info.channels.toLong() * (info.bitsPerSample / 8).toLong()
        val durationMs = info.dataSize * 1000L / (info.sampleRate.toLong() * bytesPerFrame)
        if (durationMs > MAX_DURATION_MS) return AsrResult.Failure(TOO_LONG_REASON)

        // 8/24 位会被 s16leToFloat 误读；上游契约要求 PCM16，直接前置拦掉。
        if (info.bitsPerSample != 16) return AsrResult.Failure(BAD_FORMAT_REASON)

        // 双声道只取左声道：抽样本重组单声道 + 重建头，绝不把双声道原始字节直传。
        val uploadBytes = if (info.channels == 2) {
            deinterleaveLeft(wav, info) ?: return AsrResult.Failure(BAD_FORMAT_REASON)
        } else {
            wav
        }

        return withContext(Dispatchers.IO) { uploadAndParse(uploadBytes, apiKey) }
    }

    /** 真正发请求 + 解析响应；网络/超时/JSON 异常全部在内部转成 Failure。 */
    private suspend fun uploadAndParse(wav: ByteArray, apiKey: String): AsrResult {
        val url = resolveAsrUrl()
        val filePart = MultipartBody.Part.createFormData(
            "file",
            "speech.wav",
            wav.toRequestBody("audio/wav".toMediaType()),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(filePart)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 失败必须留痕：以前这里静默转文案，真机上只看到一句「网络异常」，
                    // 分不清是断网、TLS、401 还是路径写错（2026-11-20 事故）。
                    val detail = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    android.util.Log.w(
                        TAG,
                        "ASR HTTP ${response.code} url=$url bytes=${wav.size} body=${detail.take(300)}"
                    )
                    return@use httpFailure(response.code)
                }
                val raw = response.body?.string().orEmpty()
                val root = JsonParser.parseString(raw)
                if (!root.isJsonObject) {
                    android.util.Log.w(TAG, "ASR 响应不是 JSON 对象 url=$url body=${raw.take(300)}")
                    return@use AsrResult.Failure(BAD_RESPONSE_REASON)
                }
                val obj = root.asJsonObject
                val text = obj.get("text")
                    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.asString
                    .orEmpty()
                val durationMs = obj.get("duration_ms")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt
                    ?: 0
                android.util.Log.d(
                    TAG, "ASR ok url=$url bytes=${wav.size} duration=${durationMs}ms textLen=${text.length}"
                )
                if (text.isBlank()) {
                    AsrResult.Failure("没听清，请再说一次")
                } else {
                    AsrResult.Success(text, durationMs)
                }
            }
        } catch (e: SocketTimeoutException) {
            // 连不上 / 读超时（IOException 子类，先于 IOException 捕获）
            android.util.Log.w(TAG, "ASR 超时 url=$url bytes=${wav.size}", e)
            AsrResult.Failure(NETWORK_REASON)
        } catch (e: IOException) {
            // 连接失败 / 无响应 / 断网 / TLS 握手失败（证书过期等）都会落到这里，
            // 所以异常原文必须打出来，否则「网络异常」四个字无法区分病因。
            android.util.Log.w(
                TAG,
                "ASR IO 失败 url=$url bytes=${wav.size}: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            AsrResult.Failure(NETWORK_REASON)
        } catch (e: Exception) {
            // JsonParseException / IllegalStateException / JSONException 等解析或运行期异常兜底
            android.util.Log.w(TAG, "ASR 响应处理失败 url=$url", e)
            AsrResult.Failure(BAD_RESPONSE_REASON)
        }
    }

    /**
     * ASR 端点：跟随设置页的 Base URL（见 [baseUrlProvider] 注释）。
     *
     * 容错两点：末尾 `/` 去掉；用户把 Base URL 填成 `…/v1`（聊天常用写法）时去掉这个后缀，
     * 否则拼出来是 `/v1/v1/tools/asr` → 404 → 用户只会看到「识别服务异常」。
     */
    private fun resolveAsrUrl(): String {
        val raw = baseUrlProvider().orEmpty().trim().ifBlank { baseUrl }
        return raw.trimEnd('/').removeSuffix("/v1").trimEnd('/') + ASR_PATH
    }

    /** HTTP 非 2xx → 可指导用户的中文文案。 */
    private fun httpFailure(code: Int): AsrResult.Failure = AsrResult.Failure(
        when (code) {
            400 -> "录音格式有问题，请重试"
            401, 403 -> "API Key 无效，请在设置里检查"
            408 -> "上传超时，请检查网络后重试"
            413 -> TOO_LONG_REASON
            429 -> "说话太频繁，歇一下再试"
            503, 504 -> "服务正忙，稍后再试"
            in 500..599 -> "服务正忙，稍后再试"
            else -> BAD_RESPONSE_REASON
        }
    )

    /**
     * 按 RIFF chunk 链表扫描定位 fmt / data 块，**不硬编码 44 字节**。
     *
     * 与同包 [PcmUtils.stripWavHeader] 采用同一套 chunk 遍历规则（奇数长度补 1 字节 pad）；
     * 因 V2.3.1 契约禁止改动 PcmUtils，这里内聚实现一份，仅多读 fmt 里的三个字段。
     * 只校验解析所需字段；不校验 dataSize 与实到字节一致（见 [transcribeInternal] 注释）。
     * 非法 / 非 PCM / 非标准采样率返回 null，由调用方转「录音格式有问题」。
     */
    private fun parseWavInfo(wav: ByteArray): WavInfo? {
        if (wav.size < RIFF_HEADER_SIZE) return null
        if (ascii(wav, 0) != "RIFF" || ascii(wav, 8) != "WAVE") return null

        var pos = RIFF_HEADER_SIZE.toLong()
        var fmt: Fmt? = null
        var dataStart = -1
        var dataSize = -1L
        while (pos + CHUNK_HEADER_SIZE <= wav.size.toLong()) {
            val p = pos.toInt()
            val id = ascii(wav, p)
            val size = u32(wav, p + 4)
            val bodyStart = pos + CHUNK_HEADER_SIZE
            when (id) {
                "fmt " -> {
                    if (size < FMT_BODY_SIZE || bodyStart + FMT_BODY_SIZE > wav.size.toLong()) return null
                    val b = bodyStart.toInt()
                    fmt = Fmt(
                        audioFormat = u16(wav, b),
                        channels = u16(wav, b + 2),
                        sampleRate = u32(wav, b + 4),
                        bitsPerSample = u16(wav, b + 14),
                    )
                }
                "data" -> {
                    dataStart = bodyStart.toInt()
                    dataSize = size
                }
            }
            val next = bodyStart + size + (size and 1L) // 奇数长度补 1 字节对齐
            if (next <= pos) return null
            if (id == "data") break // data 是载荷终点，后面是否有尾块与解析无关
            pos = next
        }

        val f = fmt ?: return null
        if (dataStart < 0 || dataSize < 0) return null
        if (f.audioFormat != 1) return null // 只收线性 PCM，压缩格式会被 s16leToFloat 误读
        if (f.channels !in 1..2) return null
        // 必须拒绝 > Int.MAX_VALUE：否则 deinterleave 里 toInt() 变负数，WavHeader.require 抛 IAE。
        if (f.sampleRate <= 0 || f.sampleRate > Int.MAX_VALUE.toLong()) return null
        if (f.bitsPerSample <= 0 || f.bitsPerSample % 8 != 0) return null

        val available = minOf(dataSize, (wav.size - dataStart).toLong()).toInt()
        return WavInfo(f.channels, f.sampleRate.toInt(), f.bitsPerSample, dataSize, dataStart, available)
    }

    /**
     * 双声道 PCM16 → 左声道单声道 WAV。
     * 每帧 4 字节：L(2) + R(2)，取前 2 字节；起点用 [WavInfo.dataStart]（不是固定 44），
     * 抽帧长度用 [WavInfo.available]（不是声明值，防越界）。
     * 重组的头由 [WavHeader.buildWavHeader] 重建，channels=1、byteRate/blockAlign/dataSize 随之重算。
     * 无法整齐抽帧时返回 null。
     */
    private fun deinterleaveLeft(wav: ByteArray, info: WavInfo): ByteArray? {
        val available = info.available
        if (available <= 0 || available % BYTES_PER_STEREO_FRAME != 0) return null
        val frames = available / BYTES_PER_STEREO_FRAME
        val monoPcm = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val src = info.dataStart + i * BYTES_PER_STEREO_FRAME
            monoPcm[i * 2] = wav[src]
            monoPcm[i * 2 + 1] = wav[src + 1]
        }
        return WavHeader.buildWavHeader(
            pcmLength = monoPcm.size,
            sampleRate = info.sampleRate,
            channels = 1,
            bitsPerSample = 16,
        ) + monoPcm
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val TAG = "DeepSeekAsr"
        private const val ASR_PATH = "/v1/tools/asr"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val RIFF_HEADER_SIZE = 12
        private const val CHUNK_HEADER_SIZE = 8
        private const val FMT_BODY_SIZE = 16
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_DURATION_MS = 120_000

        const val TOO_LONG_REASON = "录音太长（超过 2 分钟），请分段说"
        const val BAD_FORMAT_REASON = "录音格式有问题，请重试"
        private const val BAD_RESPONSE_REASON = "识别服务异常，请稍后重试"
        private const val NETWORK_REASON = "网络异常，请检查连接"
    }
}
