package com.companion.chat.data.speech

/**
 * 语音识别端口（Port）。任务 V2.3.1「ASR 端口 + PCM/WAV 工具」
 *
 * 只约定「输入 WAV 字节 → 返回识别结果」，不关心底层是云端 DeepSeek ASR（V2.3.3）
 * 还是本地引擎。录音层（V2.3.2）依赖这个接口而非具体实现，便于替换与单测。
 */
interface AsrPort {

    /**
     * 识别一段音频。
     *
     * @param wav 16 位 PCM WAV（或 Ogg Opus）字节；单/双声道，双声道只取左声道；
     *            限制 ≤ 8 MiB 且 ≤ 120 秒。
     * @return 成功返回文本与时长；失败返回带中文文案的 [AsrResult.Failure]。
     */
    suspend fun transcribe(wav: ByteArray): AsrResult
}
