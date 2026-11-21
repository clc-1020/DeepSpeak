package com.companion.chat.data.tts

import android.media.MediaPlayer
import com.companion.chat.data.remote.ChatApiClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek 官方 TTS 引擎（联网、按字符计费）。任务 4.1
 *
 * 流程：文本按计费字符分片（≤9000/片）→ 逐片 POST /v1/tools/tts 拿 wav 字节
 * → 写临时文件 → MediaPlayer 顺序播放并挂起到播放完成。任一步失败返回 Result.failure，
 * 由 TtsManager 决定降级到系统 TTS。
 */
class DeepSeekTtsEngine(
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String = { "https://api.deepseek.com" },
    private val voiceProvider: () -> String = { "stella" },
    private val cacheDirProvider: () -> File,
    private val client: OkHttpClient = ChatApiClient.defaultClient()
) : TtsEngine {

    private val gson = Gson()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    /** 正在等待播放结束的协程；stop() 时主动 cancel，避免停止后 speak 永久挂起（协程泄漏）。 */
    @Volatile
    private var pendingPlay: CancellableContinuation<Unit>? = null

    @Volatile
    private var stopped = false

    override fun isAvailable(): Boolean = apiKeyProvider().isNotBlank()

    override suspend fun speak(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        val key = apiKeyProvider()
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("未填写 API Key"))
        try {
            stopped = false
            for (chunk in TtsTextChunker.split(text)) {
                if (stopped) break
                val file = requestWav(chunk, key)
                try {
                    playBlocking(file)
                } finally {
                    file.delete()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            // 用户 stop() 主动取消：向上传播，不回退系统 TTS（否则停不下来）
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requestWav(text: String, apiKey: String): File {
        val payload = TtsRequest(text = text, voiceId = voiceProvider())
        val request = Request.Builder()
            .url(buildTtsEndpoint(baseUrlProvider()))
            .header("Authorization", "Bearer $apiKey")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body ?: throw IOException("TTS 空响应")
            if (!resp.isSuccessful) {
                throw IOException("TTS 请求失败：HTTP ${resp.code} ${resp.message}")
            }
            val file = File.createTempFile("tts_", ".wav", cacheDirProvider())
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            return file
        }
    }

    private suspend fun playBlocking(file: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            mediaPlayer = mp
            pendingPlay = cont
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    pendingPlay = null
                    if (cont.isActive) cont.resume(Unit)
                    releasePlayer(mp)
                }
                mp.setOnErrorListener { _, what, extra ->
                    pendingPlay = null
                    if (cont.isActive) {
                        cont.resumeWithException(IOException("MediaPlayer 播放错误 $what/$extra"))
                    }
                    releasePlayer(mp)
                    true
                }
                mp.setOnPreparedListener { it.start() }
                // 异步准备，避免在主线程同步 prepare() 卡 UI（大文件/慢盘）
                mp.prepareAsync()
            } catch (e: Exception) {
                pendingPlay = null
                releasePlayer(mp)
                if (cont.isActive) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation {
                pendingPlay = null
                releasePlayer(mp)
            }
        }
    }

    private fun releasePlayer(mp: MediaPlayer) {
        try {
            mp.release()
        } catch (_: Exception) {
        }
        if (mediaPlayer === mp) mediaPlayer = null
    }

    override fun stop() {
        stopped = true
        mediaPlayer?.let { releasePlayer(it) }
        // 若正挂起在 MediaPlayer 等待播放结束，主动 cancel 该协程，避免 stop 后 speak 永不返回
        pendingPlay?.let { if (it.isActive) it.cancel() }
        pendingPlay = null
    }

    private fun buildTtsEndpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return when {
            b.endsWith("/tools/tts") -> b
            b.endsWith("/v1") -> "$b/tools/tts"
            else -> "$b/v1/tools/tts"
        }
    }

    private data class TtsRequest(
        val text: String,
        @SerializedName("voice_id") val voiceId: String,
        val format: String = "wav",
        @SerializedName("filter_markdown") val filterMarkdown: Boolean = true
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
