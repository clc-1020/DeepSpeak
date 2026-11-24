package com.companion.chat.data.tts

/**
 * 语音合成引擎抽象。任务 4.1（TTS 双通道）
 *
 * 返回 Result：成功=播放完成；失败=异常（由 TtsManager 决定是否降级）。
 * 之所以用 Result 而不是 onDone/onError 回调，是为了让「官方失败 → 系统兜底」能顺序 await。
 */
interface TtsEngine {
    /** 朗读文本，挂起到播放结束（或失败）。 */
    suspend fun speak(text: String): Result<Unit>

    /** 立即停止当前播放。 */
    fun stop()

    /** 当前是否可用（如系统 TTS 已初始化 / 官方 TTS 有 Key）。 */
    fun isAvailable(): Boolean
}

/** TTS 相关配置快照（与 ApiConfig 解耦，便于单测）。任务 4.1 */
data class TtsSettings(
    /** 语音总开关：关闭时所有朗读直接跳过（任务 4.1）。 */
    val enabled: Boolean = true,
    /** 是否用 DeepSeek 官方 TTS（联网、按字符计费），否则系统 TTS。 */
    val deepSeekEnabled: Boolean = false,
    /** 官方音色：stella(女，默认) / tide(男)。 */
    val voiceId: String = "stella",
    /** 语速倍率（0.5~2.0，1.0=正常）。 */
    val speechRate: Float = 1.0f,
    /** 是否用小爱同学当前的音色（需手机装 Xposed 桥模块，见[xiaoaiEnabled]说明）。 */
    val xiaomiEnabled: Boolean = false,
    /** 系统 TTS 引擎包名；null=系统默认引擎。 */
    val systemEngine: String? = null
)
