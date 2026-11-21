package com.companion.chat.ui.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.companion.chat.data.speech.AsrResult
import com.companion.chat.data.speech.AudioRecorder
import com.companion.chat.data.speech.SttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** 语音输入状态机（V2.3.4）。三态互斥，`ChatScreen` 据此渲染波形/转圈。 */
sealed class VoiceState {

    /** 空闲：麦克风可长按。 */
    object Idle : VoiceState()

    /** 录音中。[volume] 是线性 RMS 0..100 原始值（波形另用拉伸后的 [VoiceInputController.waveform]）。 */
    data class Recording(val volume: Int, val cancelZone: Boolean) : VoiceState()

    /** 识别中：禁止再次长按（两个 AudioRecord 抢麦会双双初始化失败）。 */
    object Transcribing : VoiceState()
}

/**
 * 语音输入控制器（V2.3.4 语音输入 UI）。
 *
 * ### 为什么长这样：四条真实踩坑来的硬约束
 *
 * 1. **禁止在 `onAutoStop` 里直接 `stop()`**。该回调跑在 `AudioRecorder` 的录制线程上，
 *    而 `stop()` 内部是 `worker.join(5000)`——对自身 join 会卡满 5 秒再返回。所以回调里只
 *    `mainHandler.post { finishInternal(auto = true) }`，真正的 `stop()` 在 IO 线程执行。
 * 2. **`stop()` 非幂等**。第一次取走并 `reset()` 了 pcm，第二次返回 `ByteArray(0)`。
 *    120 秒自动停 + 用户松手两条路径都会触发收尾，所以每个 session 配一个
 *    [AtomicBoolean] 一次性标记，`finish`/`cancel` 谁先到谁执行，后到的直接 return。
 *    否则第二条路径拿到空数组 → 误报「没录到声音」。
 * 3. **波形必须拉伸**。`volumeLevel()` 是线性 RMS 映射，正常说话只有 3–9%，
 *    直接画贴底不动；[voiceBarLevel] 做开方拉伸（3% → 0.24，9% → 0.36）。
 * 4. **`stop()` 必须在 IO 线程**（阻塞 5 秒上限 + 末尾一次阻塞读），主线程会 ANR。
 *
 * ### 线程模型
 * - 状态与波形都用 Compose 快照状态，**只在主线程写**；
 * - 收尾协程跑在自持的 `Dispatchers.Main.immediate`（不依赖 `rememberCoroutineScope`，
 *   界面不可见时 Handler 派发照常，不会因为 Choreographer 停帧而卡住收尾）；
 * - 丢弃路径（取消录音 / 页面销毁）用独立 Thread 调 `stop()`，保证 `release()` 之后
 *   麦克风一定被释放——协程作用域那时已被 cancel，不能用它。
 *
 * @param cancelThresholdPx 上划取消阈值（像素）。Compose 侧用 `80.dp.toPx()` 传进来。
 * @param onResult 识别成功的文本（已 trim，非空）。调用方负责填输入框，**不自动发送**。
 * @param onError 面向用户的失败文案，**直接来自 [AsrResult.Failure.reason]**（调用方只 Toast）。
 * @param onNotice 非错误的提示（如「录音已满 2 分钟」），与 onError 分开，避免被当成失败处理。
 */
class VoiceInputController(
    private val sttManager: SttManager,
    private val cancelThresholdPx: Float,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onNotice: (String) -> Unit = {},
    private val recorderFactory: () -> AudioRecorder = { AudioRecorder() },
) {

    companion object {
        /** 波形柱数量（UI 侧按这个数量取最近 N 个采样）。 */
        const val WAVEFORM_BARS = 18

        /** 音量轮询间隔；16k/2048 帧约 128 ms 一帧，60 ms 已足够跟手且不费电。 */
        private const val POLL_INTERVAL_MS = 60L

        const val EMPTY_RECORDING_REASON = "没录到声音，请重试"
        const val RECORD_FAILED_REASON = "无法打开麦克风，请检查录音权限或稍后重试"
        const val LIMIT_REACHED_NOTICE = "录音已满 2 分钟"
    }

    /** 当前状态（Compose 可观察）。 */
    var state: VoiceState by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    /** 最近 [WAVEFORM_BARS] 个拉伸后的波形高度（0f..1f），最新的在末尾。 */
    val waveform = mutableStateListOf<Float>()

    /** 收尾协程的作用域：自持，`release()` 时 cancel。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** session 一次性收尾标记（契约 2）。每个新 session 换一个新实例。 */
    private var claim: AtomicBoolean? = null

    private var recorder: AudioRecorder? = null
    private var pollJob: Job? = null

    /** 上一次 `stop()` 还没退出（丢弃路径）。此时不许开新 session，否则两个 AudioRecord 抢麦。 */
    @Volatile
    private var closing = false

    /** 页面是否已销毁；销毁后一切回调都不再改状态。 */
    @Volatile
    private var released = false

    // ---------------------------------------------------------------- 对外动作

    /**
     * 长按按下：开麦。
     *
     * 返回 false 表示**没有进入录音**（正在识别 / 上一次还没退干净 / 麦克风初始化失败）；
     * 初始化失败时会通过 [onError] 提示，其余情况调用方无需额外提示。
     */
    fun start(): Boolean {
        if (released) return false
        if (state !is VoiceState.Idle) return false // 录音中或识别中：拒绝并发
        if (closing) return false

        val rec = recorderFactory()
        val sessionClaim = AtomicBoolean(false)
        claim = sessionClaim
        recorder = rec
        waveform.clear()

        // 契约 1：回调在录制线程，只切线程，绝不在回调里 stop()
        rec.onAutoStop = {
            scope.launch { finishInternal(auto = true) }
        }
        rec.start()

        if (!rec.isRecording) {
            // 初始化失败（无权限 / 被占用 / AudioRecord 打不开）：不进 Recording 态
            recorder = null
            claim = null
            onError(RECORD_FAILED_REASON)
            return false
        }

        state = VoiceState.Recording(volume = 0, cancelZone = false)
        startPolling()
        return true
    }

    /**
     * 手指移动：[offsetY] 是**相对按下点的累计竖直位移**（负数=上滑）。
     * 越过 `-cancelThresholdPx` 进入取消态，回到阈值内自动退出。
     */
    fun updateDrag(offsetY: Float) {
        val cur = state as? VoiceState.Recording ?: return
        val cancelZone = offsetY <= -cancelThresholdPx
        if (cur.cancelZone != cancelZone) state = cur.copy(cancelZone = cancelZone)
    }

    /** 上划取消：丢掉音频，**不 transcribe、不上屏、不提示成功**。 */
    fun cancel() {
        if (!claimSession()) return
        stopPolling()
        val rec = recorder
        recorder = null
        state = VoiceState.Idle
        if (rec != null) discard(rec)
        claim = null
    }

    /** 松手：切 IO 取音频 → 识别 → [onResult] / [onError]。 */
    fun finish() {
        finishInternal(auto = false)
    }

    /** 页面销毁 / 控件卸载：停轮询、丢掉录音、释放麦克风。可重复调用。 */
    fun release() {
        if (released) return
        released = true
        stopPolling()
        claim?.compareAndSet(false, true)
        val rec = recorder
        recorder = null
        state = VoiceState.Idle
        scope.cancel()
        if (rec != null) discard(rec)
    }

    // ---------------------------------------------------------------- 内部

    private fun claimSession(): Boolean = claim?.compareAndSet(false, true) ?: false

    private fun finishInternal(auto: Boolean) {
        if (!claimSession()) return // 另一条路径已经收尾（契约 2）

        stopPolling()
        val rec = recorder ?: run {
            claim = null
            state = VoiceState.Idle
            return
        }
        recorder = null
        state = VoiceState.Transcribing

        scope.launch {
            // 契约 4：stop() 阻塞（join ≤5s + 末尾阻塞读），必须 IO 线程
            val wav = withContext(Dispatchers.IO) {
                runCatching { rec.stop() }.getOrDefault(ByteArray(0))
            }
            // 页面已销毁（release() 取消了本作用域）：不 Toast、不改状态。
            // 注意 DeepSeekAsrClient.transcribe 里的 runCatching 会吞掉 CancellationException
            // 并返回 Failure，若不在这里拦一道，离开页面后会弹一条假的「识别服务异常」。
            if (released) return@launch

            // 先取 isAutoStopped 再看是否为空：到点自动停时它一定是 true
            val reachedLimit = auto || rec.isAutoStopped

            if (wav.isEmpty()) {
                // 契约 5：空数组只 Toast，绝不 transcribe、绝不发消息
                finishSession(VoiceState.Idle)
                onError(EMPTY_RECORDING_REASON)
                return@launch
            }
            if (reachedLimit) onNotice(LIMIT_REACHED_NOTICE)

            val result = sttManager.transcribe(wav)
            // 识别期间页面被销毁同样不能再回调（同上的 CancellationException 兜底）
            if (released) return@launch

            when (result) {
                is AsrResult.Success -> {
                    val text = result.text.trim()
                    finishSession(VoiceState.Idle)
                    // 后端约定空白即「没听清」，这里再兜一层，避免把空串塞进输入框
                    if (text.isEmpty()) onError("没听清，请再说一次") else onResult(text)
                }
                // 契约 6：文案原样透传，由调用方 Toast，禁止改写成「识别失败，请重试」
                is AsrResult.Failure -> {
                    finishSession(VoiceState.Idle)
                    onError(result.reason)
                }
            }
        }
    }

    private fun finishSession(next: VoiceState) {
        claim = null
        if (!released) state = next
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (true) {
                val rec = recorder ?: break
                if (state !is VoiceState.Recording) break
                val raw = rec.volumeLevel()
                waveform.add(voiceBarLevel(raw))
                while (waveform.size > WAVEFORM_BARS) waveform.removeAt(0)
                state = VoiceState.Recording(
                    volume = raw,
                    cancelZone = (state as? VoiceState.Recording)?.cancelZone ?: false,
                )
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 丢弃音频并释放麦克风（取消 / 页面销毁）。
     *
     * 用独立线程而不是协程：`release()` 里 `scope` 已被 cancel，协程版可能在 `join` 中途被取消，
     * 麦克风就被上一个 session 永久占住，下一次长按必然初始化失败。
     */
    private fun discard(rec: AudioRecorder) {
        closing = true
        Thread({
            try {
                rec.stop()
            } catch (_: Throwable) {
                // 丢弃路径不看结果
            } finally {
                closing = false
            }
        }, "companion-voice-discard").apply { isDaemon = true }.start()
    }
}

/**
 * 线性 RMS（0..100）→ 波形高度（0f..1f），开方拉伸（契约 3）。
 *
 * 正常说话 RMS 约 1000–3000，[com.companion.chat.data.speech.AudioRecorder.volumeLevel]
 * 映射后只有 3–9%；线性画几乎不动。开方后 3% → 0.24、9% → 0.36、50% → 0.73，
 * 安静和说话在视觉上能区分开。纯函数，可 JVM 单测。
 */
internal fun voiceBarLevel(rawVolume: Int): Float {
    val v = rawVolume.coerceIn(0, 100) / 100f
    return (BAR_FLOOR_CONST + (1f - BAR_FLOOR_CONST) * sqrt(v)).coerceIn(0f, 1f)
}

/** 与 [VoiceInputController] 的 `BAR_FLOOR` 对齐（独立常量：顶层函数不能引用 private companion）。 */
private const val BAR_FLOOR_CONST = 0.08f