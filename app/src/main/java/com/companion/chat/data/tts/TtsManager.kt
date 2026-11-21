package com.companion.chat.data.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TTS 管理器：按配置选择通道，官方失败自动降级系统。任务 4.1
 *
 * 选择规则：
 * - **总开关关闭** → 直接跳过（返回成功，不做任何播放）。
 * - 未开启官方 TTS，或官方引擎不可用（无 Key）→ 系统 TTS。
 * - 开启官方且可用 → 先试官方；失败（网络/HTTP/播放错误）→ 回退系统 TTS，保证不崩。
 *
 * 用 [mutex] 串行化：**初始化未完成 / 连续多条消息**时会排队依次念，不会互相打断或抢占。
 */
class TtsManager(
    private val settingsProvider: () -> TtsSettings,
    private val systemEngine: TtsEngine,
    private val deepSeekEngine: TtsEngine
) {

    private val mutex = Mutex()

    suspend fun speak(text: String): Result<Unit> = mutex.withLock {
        if (text.isBlank()) return@withLock Result.success(Unit)
        val settings = settingsProvider()
        if (!settings.enabled) return@withLock Result.success(Unit) // 语音总开关关闭
        if (settings.deepSeekEnabled && deepSeekEngine.isAvailable()) {
            val r = deepSeekEngine.speak(text)
            if (r.isSuccess) return@withLock r
            // 官方失败 → 系统兜底
        }
        systemEngine.speak(text)
    }

    /** 用哪条通道（仅供 UI 展示「当前通道」用）。 */
    fun currentChannel(): TtsChannel {
        val s = settingsProvider()
        return if (s.deepSeekEnabled && deepSeekEngine.isAvailable()) TtsChannel.DEEPSEEK else TtsChannel.SYSTEM
    }

    fun stop() {
        deepSeekEngine.stop()
        systemEngine.stop()
    }
}

enum class TtsChannel { SYSTEM, DEEPSEEK }