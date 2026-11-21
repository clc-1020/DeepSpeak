package com.companion.chat.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 系统 TTS 引擎（离线兜底）。任务 4.1
 *
 * 关键：TextToSpeech 的 onInit 是**异步**回调。冷启动首帧引擎还没就绪就调用 speak 会直接
 * 返回 ERROR。这里用 [initDeferred] 挂起等待 onInit（带超时），就绪后才真正 speak；
 * 失败再返回 Result.failure。系统是最后兜底，必须稳。
 *
 * 支持：语速（[speechRateProvider]）、指定系统引擎（[engineNameProvider]，null=默认）——
 * 引擎名变化时自动重建（重新等待 onInit）。
 */
class SystemTtsEngine(
    context: Context,
    private val speechRateProvider: () -> Float = { 1.0f },
    private val engineNameProvider: () -> String? = { null }
) : TtsEngine {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    /** onInit 回调完成前一直挂起；完成值为 TextToSpeech.SUCCESS / ERROR。 */
    private var initDeferred = CompletableDeferred<Int>()

    @Volatile
    private var initStarted = false

    /** 已完成初始化的引擎名（用于检测设置变更后重建）。 */
    private var initializedEngine: String? = null

    @Volatile
    private var pending: CancellableContinuation<Result<Unit>>? = null

    @Synchronized
    private fun ensureInit() {
        val wanted = engineNameProvider()
        if (initStarted && wanted == initializedEngine) return
        // 首次初始化或引擎变更 → 重建
        if (initStarted && wanted != initializedEngine) {
            try { tts?.shutdown() } catch (_: Exception) { }
        }
        initStarted = true
        initializedEngine = wanted
        initDeferred = CompletableDeferred()
        try {
            tts = if (wanted.isNullOrBlank()) {
                TextToSpeech(appContext) { status -> initDeferred.complete(status) }
            } else {
                TextToSpeech(appContext, { status -> initDeferred.complete(status) }, wanted)
            }
        } catch (_: Exception) {
            if (!initDeferred.isCompleted) initDeferred.complete(TextToSpeech.ERROR)
        }
    }

    /** 系统 TTS 恒视为"可尝试"（真正是否就绪由 speak 内部等待 onInit 决定）。 */
    override fun isAvailable(): Boolean = true

    override suspend fun speak(text: String): Result<Unit> {
        ensureInit()
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { initDeferred.await() }
            ?: return Result.failure(IllegalStateException("系统 TTS 初始化超时"))
        if (status != TextToSpeech.SUCCESS) {
            return Result.failure(IllegalStateException("系统 TTS 初始化失败($status)"))
        }
        val engine = tts ?: return Result.failure(IllegalStateException("系统 TTS 未就绪"))
        return suspendCancellableCoroutine { cont ->
            pending = cont
            val utterId = UUID.randomUUID().toString()
            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) = finish(Result.success(Unit))

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) =
                    finish(Result.failure(IllegalStateException("系统 TTS 播放失败")))

                override fun onError(id: String?, errorCode: Int) =
                    finish(Result.failure(IllegalStateException("系统 TTS 播放失败($errorCode)")))

                private fun finish(r: Result<Unit>) {
                    val p = pending ?: return
                    pending = null
                    if (p.isActive) p.resume(r)
                }
            }
            engine.setOnUtteranceProgressListener(listener)
            try {
                engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            } catch (_: Exception) {
                // 语言设置失败不阻断，用系统默认
            }
            // 应用语速（0.5~2.0；越界夹紧，避免引擎异常）
            try {
                engine.setSpeechRate(speechRateProvider().coerceIn(0.5f, 2.0f))
            } catch (_: Exception) {
                // 语速设置失败不阻断，用默认
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterId)
            if (result != TextToSpeech.SUCCESS) {
                pending = null
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException("系统 TTS 启动失败")))
            }
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        val p = pending ?: return
        pending = null
        // 被停止 ≠ 播放成功：以 failure 结束，避免上层误判为完整播完
        if (p.isActive) p.resume(Result.failure(IllegalStateException("系统 TTS 已停止")))
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5000L
    }
}