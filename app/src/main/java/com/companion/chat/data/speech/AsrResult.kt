package com.companion.chat.data.speech

/**
 * 语音识别（ASR）结果。任务 V2.3.1「ASR 端口 + PCM/WAV 工具」
 *
 * 用密封类承载成功 / 失败两种状态，调用方（V2.3.3 网络实现、后续 UI）可以用 when 穷尽处理，
 * 不必从异常里解析文案。
 */
sealed class AsrResult {

    /** 识别成功。[text] 是转写出的文本，[durationMs] 是音频时长（毫秒）。 */
    data class Success(val text: String, val durationMs: Int) : AsrResult()

    /** 识别失败。[reason] 是面向用户的中文文案，可直接展示。 */
    data class Failure(val reason: String) : AsrResult()
}
