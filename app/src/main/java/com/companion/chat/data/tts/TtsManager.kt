package com.companion.chat.data.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TTS 管理器：按配置选择通道，失败自动降级。任务 4.1（双通道）→ 1.13.0（三通道）
 *
 * 选择规则（优先级从上到下）：
 * - **总开关关闭** → 直接跳过（返回成功，不做任何播放）。
 * - **小爱音色**：开启且装了小爱 → 先试小爱；失败/未安装 → 继续往下。
 * - **DeepSeek 官方**：开启且有 Key → 试官方；失败（网络/HTTP/播放错误）→ 继续往下。
 * - **系统 TTS**：无条件兜底，保证不崩。
 *
 * 用 [mutex] 串行化：**初始化未完成 / 连续多条消息**时会排队依次念，不会互相打断或抢占。
 *
 * 1.13.1：全部通道都失败时通过 [errorSink] 上报一句可读的原因 —— 之前对话朗读是静默失败，
 * 用户点了喇叭没声音且完全不知道原因（典型场景：手机上没装任何 TTS 引擎、又没开小爱音色）。
 */
class TtsManager(
    private val settingsProvider: () -> TtsSettings,
    private val systemEngine: TtsEngine,
    private val deepSeekEngine: TtsEngine,
    private val xiaoaiEngine: TtsEngine? = null,
    private val errorSink: (String) -> Unit = {}
) {

    private val mutex = Mutex()

    suspend fun speak(text: String): Result<Unit> = mutex.withLock {
        if (text.isBlank()) return@withLock Result.success(Unit)
        val settings = settingsProvider()
        if (!settings.enabled) return@withLock Result.success(Unit) // 语音总开关关闭

        // ① 小爱音色（本机音色，不走网络；依赖 Xposed 桥模块）
        if (settings.xiaomiEnabled) {
            val engine = xiaoaiEngine
            if (engine != null && engine.isAvailable()) {
                val r = engine.speak(text)
                if (r.isSuccess) return@withLock r
                // 小爱失败 → 继续往下降级
            }
        }

        // ② DeepSeek 官方（联网计费）
        if (settings.deepSeekEnabled && deepSeekEngine.isAvailable()) {
            val r = deepSeekEngine.speak(text)
            if (r.isSuccess) return@withLock r
            // 官方失败 → 系统兜底
        }

        // ③ 系统兜底
        val r = systemEngine.speak(text)
        if (r.isFailure) {
            errorSink(describeFailure(settings, r.exceptionOrNull()))
        }
        r
    }

    /** 用哪条通道（仅供 UI 展示「当前通道」用）。 */
    fun currentChannel(): TtsChannel {
        val s = settingsProvider()
        if (s.xiaomiEnabled && xiaoaiEngine?.isAvailable() == true) return TtsChannel.XIAOAI
        return if (s.deepSeekEnabled && deepSeekEngine.isAvailable()) TtsChannel.DEEPSEEK else TtsChannel.SYSTEM
    }

    fun stop() {
        xiaoaiEngine?.stop()
        deepSeekEngine.stop()
        systemEngine.stop()
    }

    /**
     * 兜底也失败时的提示文案。
     * 小爱/官方都没开 → 基本只剩「本机没有语音引擎」这一种可能，直接给出两条出路；
     * 否则把底层原因原样透出，便于排查（例如系统 TTS 初始化失败的状态码）。
     */
    private fun describeFailure(settings: TtsSettings, cause: Throwable?): String =
        if (!settings.xiaomiEnabled && !settings.deepSeekEnabled) {
            "朗读失败：本机没有可用的语音引擎（可开启「小爱同学音色」，或安装一个系统 TTS 引擎）"
        } else {
            "朗读失败：${cause?.message ?: "未知错误"}"
        }
}

enum class TtsChannel { XIAOAI, DEEPSEEK, SYSTEM }