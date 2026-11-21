package com.companion.chat.data.tts

import com.companion.chat.data.power.WakeLockHandle
import com.companion.chat.domain.emotion.EmotionKaomoji
import com.companion.chat.data.power.WakeLockPolicy

/**
 * 对话朗读控制器（任务 4.2 / 6.1）：朗读整段消息，期间持有 WakeLock，念完/失败/停止都释放。
 *
 * 一次性念整段（不逐字、不边生成边念）；[TtsManager] 内部已 Mutex 串行，这里再保证
 * 「持锁 → 播完 → 释放」成对，异常也不会漏释放（超时兜底 + finally）。
 *
 * **任务 6.1**：TTS 是超时策略里唯一的例外 —— 长段落需要更久，用 [WakeLockPolicy.TTS_TIMEOUT_MS]（10 分钟）。
 */
class ReadAloudController(
    private val ttsManager: TtsManager,
    private val wakeLock: WakeLockHandle,
    private val maxWakeMs: Long = MAX_WAKE_MS
) {

    /** 朗读一段文本；持锁期间播放，返回播放结果。空文本直接成功。 */
    suspend fun speak(text: String): Result<Unit> {
        // 2026-11-21 情绪系统 P1：颜文字必须剥离，否则会被念成「括号 点 笑脸」。
        // 按表内精确匹配删除（不用正则猜），句中/末尾都处理。
        val spoken = EmotionKaomoji.strip(text)
        if (spoken.isBlank()) return Result.success(Unit)
        wakeLock.acquire(maxWakeMs)
        return try {
            ttsManager.speak(spoken)
        } finally {
            wakeLock.release()
        }
    }

    /** 立即停止当前朗读（并释放锁，由 speak 的 finally 兜底）。 */
    fun stop() {
        ttsManager.stop()
        wakeLock.release()
    }

    companion object {
        /** 10 分钟上限（= [WakeLockPolicy.TTS_TIMEOUT_MS]）；主要靠 finally 释放，超时仅防崩溃漏锁。 */
        const val MAX_WAKE_MS = WakeLockPolicy.TTS_TIMEOUT_MS
    }
}