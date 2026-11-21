package com.companion.chat.data.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * RMS 音量换算为 0–100 的纯函数。任务 V2.3.2
 *
 * 从一段 16 位小端单声道 PCM 计算 RMS，按满幅 32768 线性映射到 0–100 并四舍五入
 * （满幅 32767 → 100，半幅 16384 → 50）。无 Android 依赖，可单独做 JVM 单测；
 * AudioRecorder 录制循环直接调用它驱动 UI 波形。
 *
 * 注意：这是**线性刻度**。正常说话 RMS 约 1000–3000，映射后只有 3–9%，UI 画波形
 * 必须自行做 sqrt/log 拉伸（契约见计划书 §V2.3.4），否则波形会贴底几乎不动。
 */
internal fun rmsToVolumeLevel(pcm: ByteArray, offset: Int, length: Int): Int {
    var sumSq = 0.0
    var count = 0
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
        val raw = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        val sample = raw.toShort().toInt() // 截断成有符号 16 位
        sumSq += sample.toDouble() * sample
        count++
        i += 2
    }
    if (count == 0) return 0
    val rms = sqrt(sumSq / count)
    return (rms / 32768.0 * 100.0).roundToInt().coerceIn(0, 100)
}

/**
 * 把 PCM 截断到不超过 [maxDurationMs] 对应的字节数（16 位单声道，2 字节/样本）。
 *
 * 背景：`readLoop` 判到 120s 后 break，退出前还会再读一批残余（16k 下 2048 帧 = 128 ms），
 * 于是实际录到 120.192s / 3,846,144 字节。若不截断，DeepSeek ASR 侧按
 * `dataSize * 1000 / (sampleRate * 1 * 16/8)` 算出 120,192 ms，被 120,000 ms 本地闸门拦掉，
 * 用户按住两分钟后「录音已满」紧接着「录音太长」，整段话被丢弃。
 *
 * 纯函数、零 Android 依赖，与 [rmsToVolumeLevel] 同风格，可在 JVM 单测里直接断言。
 * 上限字节数按 `maxDurationMs * sampleRate * 2 / 1000` 取整后再向下取偶，保证 16 位样本边界。
 *
 * @return 未超上限时原样返回（同一引用）；超出时返回前 [上限字节数] 的副本。
 */
internal fun truncatePcmToMaxDuration(
    pcm: ByteArray,
    sampleRate: Int,
    maxDurationMs: Int,
): ByteArray {
    require(sampleRate > 0) { "采样率必须为正，实际 $sampleRate" }
    require(maxDurationMs > 0) { "maxDurationMs 必须为正，实际 $maxDurationMs" }
    val maxBytes = maxDurationMs.toLong() * sampleRate.toLong() * 2L / 1000L
    if (maxBytes <= 0L) return ByteArray(0) // 极小采样率 + 1ms 上限：连半个样本都不到
    val evenMax = (maxBytes - (maxBytes % 2L)).toInt() // 16 位样本必须落在偶数字节边界
    return if (pcm.size <= evenMax) pcm else pcm.copyOfRange(0, evenMax)
}

/**
 * 麦克风录音器：PCM16 / 单声道 / 指定采样率，直接补 WAV 头输出。任务 V2.3.2
 *
 * **WAV 头活路径**：本类只用 [WavHeader.buildWavHeader] 补头。
 * 同包 `PcmUtils.pcmToWav`（V2.3.1，ASR 侧）产出等价 44 字节头，但不属于录音路径，勿混改。
 *
 * 设计要点：
 * - 采样率默认 16k、单声道、PCM16，天然对齐 DeepSeek 云端 ASR 要求，无需转码。
 * - 音频源用 `VOICE_RECOGNITION`：避开 MIC 的 AGC / 降噪 / 通话音量策略，小声说话不被压掉。
 * - 录制循环在 start() 拉起的后台 Thread 里；stop() 同步 join 后拼 PCM，
 *   全程 volatile 标志 + Thread.join，不引入协程（避免单测复杂度）。
 * - 120 秒硬上限：到点置 [isAutoStopped] 并触发 [onAutoStop] 回调，线程随后退出。
 * - 失败（未 start / 权限拒绝 / 设备占用 / 空录音）一律返回 `ByteArray(0)`，不抛异常。
 *
 * **线程契约**：[onAutoStop] 在录制线程触发（UI 如需切主线程自行 post）；
 * [stop] 是阻塞调用（内部 join 上限 5 s，末尾还有一次阻塞读），**必须在 IO 线程调用**，勿放主线程。
 * 每次录制使用独立 Session，即使 stop() 超时，滞留 worker 也只写自己的缓冲，不会污染下一次 start()。
 */
class AudioRecorder(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_MAX_DURATION_MS = 120_000

        /** 单次读取的帧数（短 buffer，降低波形延迟）。16bit 单声道每帧 2 字节。 */
        private const val READ_FRAMES = 2048
    }

    init {
        require(maxDurationMs in 1..120_000) {
            "maxDurationMs 必须在 1..120000（ASR 单次上限 120 秒），实际 $maxDurationMs"
        }
        require(sampleRate > 0) { "采样率必须为正，实际 $sampleRate" }
    }

    /**
     * 120 秒到点自动停止时回调，在录制线程触发。
     * UI 收到后到 IO 线程调 [stop] 取数据；同时可读 [isAutoStopped] 区分「到点」与「用户松手」。
     */
    var onAutoStop: (() -> Unit)? = null

    /** 一次录制对应的全部可变状态；stop() 超时后滞留 worker 只会碰旧 Session。 */
    private class Session {
        val pcm = ByteArrayOutputStream()
        @Volatile var recording = true
        @Volatile var failed = false
        @Volatile var autoStopped = false
        @Volatile var volume = 0
        @Volatile var worker: Thread? = null
        var recorder: AudioRecord? = null
    }

    @Volatile private var session: Session? = null

    /** 是否因达到 maxDurationMs 而自动停止（用户主动 stop() 时为 false）。 */
    val isAutoStopped: Boolean get() = session?.autoStopped ?: false

    /** 是否正在录制。 */
    val isRecording: Boolean get() = session?.recording ?: false

    /**
     * 开始录制：创建并启动 AudioRecord，随后拉起后台读取线程。
     * 正在录制或上一次 worker 尚未退出时不重复启动（避免两个 AudioRecord 抢麦）。
     * 初始化失败（权限/占用）仅置失败标志，由 stop() 返回空数组。
     */
    fun start() {
        val current = session
        if (current?.recording == true || current?.worker?.isAlive == true) return

        val s = Session()
        session = s
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                s.failed = true
                s.recording = false
                return
            }
            val bufSize = maxOf(minBuf, READ_FRAMES * 2)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                safeRelease(r)
                s.failed = true
                s.recording = false
                return
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                safeRelease(r)
                s.failed = true
                s.recording = false
                return
            }
            s.recorder = r
            s.worker = Thread({ readLoop(s, r, bufSize) }, "companion-audio-recorder").apply { start() }
        } catch (t: Throwable) {
            // 权限被拒、设备被占用等：不抛出，交给 stop() 返回空数组
            s.failed = true
            s.recording = false
            s.recorder?.let { safeRelease(it) }
            s.recorder = null
        }
    }

    /** 后台读取循环：先读再判标志，天然把最后一小段缓冲数据纳入结果。 */
    private fun readLoop(s: Session, r: AudioRecord, bufSize: Int) {
        val buf = ByteArray(bufSize)
        val startNs = System.nanoTime()
        val limitNs = maxDurationMs.toLong() * 1_000_000L
        try {
            while (s.recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(s.pcm) { s.pcm.write(buf, 0, n) }
                    s.volume = rmsToVolumeLevel(buf, 0, n)
                } else if (n < 0) {
                    s.failed = true // 读取错误码
                    break
                }
                if (System.nanoTime() - startNs >= limitNs) {
                    s.autoStopped = true
                    s.recording = false
                    // 在录制线程回调；UI 若需切主线程请自行 post
                    onAutoStop?.let { cb ->
                        try { cb() } catch (_: Throwable) {}
                    }
                    break
                }
            }
            // 退出前再读一次残余。注意 AudioRecord.read 是阻塞调用：麦克风在线时通常几十毫秒内
            // 返回，会额外纳入约一个 buffer 的音频（16k 下 2048 帧 ≈ 128 ms），不是"立即返回"。
            if (!s.failed) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(s.pcm) { s.pcm.write(buf, 0, n) }
            }
        } catch (t: Throwable) {
            s.failed = true
        } finally {
            safeStop(r)
            safeRelease(r)
            s.recording = false
        }
    }

    /**
     * 停止录制并返回完整 WAV（44 字节头 + PCM）。
     * 同步等待后台线程结束（上限 5 s）。失败 / 未 start / 空录音一律返回 ByteArray(0)。
     * 阻塞方法，调用方必须放 IO 线程。
     */
    fun stop(): ByteArray {
        val s = session ?: return ByteArray(0)
        s.recording = false
        s.worker?.let { w ->
            try {
                w.join(5_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (s.failed) return ByteArray(0)

        val pcmBytes = synchronized(s.pcm) {
            val copy = s.pcm.toByteArray()
            s.pcm.reset()
            copy
        }
        if (pcmBytes.isEmpty()) return ByteArray(0) // 不把"什么都没录到"伪装成 44 字节空 WAV

        // 自动停会多录 1~2 批残余（128ms/批），截断到 maxDurationMs 以内，
        // 否则 120 秒录音会被 ASR 本地闸门判成「录音太长」而整段丢弃。
        val trimmed = truncatePcmToMaxDuration(pcmBytes, sampleRate, maxDurationMs)
        return WavHeader.buildWavHeader(
            pcmLength = trimmed.size,
            sampleRate = sampleRate,
            channels = 1,
            bitsPerSample = 16,
        ) + trimmed
    }

    /** 最近一帧 RMS 音量（0–100 线性刻度），未录制时返回 0；UI 波形需自行 sqrt/log 拉伸。 */
    fun volumeLevel(): Int {
        val s = session ?: return 0
        return if (s.recording) s.volume else 0
    }

    private fun safeStop(r: AudioRecord) {
        try { r.stop() } catch (_: Throwable) {}
    }

    private fun safeRelease(r: AudioRecord) {
        try { r.release() } catch (_: Throwable) {}
    }
}
