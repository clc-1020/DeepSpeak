package com.companion.chat.data.speech

/**
 * 标准 44 字节 RIFF/WAVE 头构造。任务 V2.3.2
 *
 * 纯函数、零 Android 依赖：AudioRecorder 录到 PCM 后调用本函数补头，
 * 从而把「WAV 头构造」单独抽出来做 JVM 单测（依赖 AudioRecord 的路径无法在 JVM 单测）。
 * 所有多字节字段一律按小端序写入。
 *
 * **活路径说明**：本项目录音链路（V2.3.2）只调用本对象的 `buildWavHeader`；
 * 同包 `PcmUtils.pcmToWav`（V2.3.1，ASR 侧）产出等价 44 字节头，但不属于录音路径，勿混改。
 * `bitsPerSample` 保留是为了头部构造的通用性，本项目录音路径固定传 16。
 */
object WavHeader {

    /** 标准 PCM WAV 头长度（字节）。 */
    const val HEADER_SIZE = 44

    /**
     * 构造 44 字节 WAV 头。
     *
     * @param pcmLength PCM 数据字节数，即 data 段大小（≥0）
     * @param sampleRate 采样率，默认 16000（DeepSeek 云端 ASR 原生支持，无需转码）
     * @param channels 声道数，默认 1（单声道）
     * @param bitsPerSample 位深，默认 16；必须为大于 0 且为 8 的倍数
     * @return 44 字节的 RIFF/WAVE 头，所有大小字段为小端
     * @throws IllegalArgumentException 任一入参非法
     */
    fun buildWavHeader(
        pcmLength: Int,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        require(pcmLength >= 0) { "pcmLength 不能为负，实际 $pcmLength" }
        require(sampleRate > 0) { "采样率必须为正，实际 $sampleRate" }
        require(channels > 0) { "声道数必须为正，实际 $channels" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) {
            "位深必须为 8 的正倍数，实际 $bitsPerSample"
        }

        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(HEADER_SIZE)

        writeAscii(out, 0, "RIFF")
        writeInt32Le(out, 4, 36 + pcmLength)      // ChunkSize = 文件总字节数 - 8
        writeAscii(out, 8, "WAVE")

        writeAscii(out, 12, "fmt ")
        writeInt32Le(out, 16, 16)                 // Subchunk1Size：PCM 固定 16
        writeInt16Le(out, 20, 1)                  // AudioFormat = 1（线性 PCM）
        writeInt16Le(out, 22, channels)
        writeInt32Le(out, 24, sampleRate)
        writeInt32Le(out, 28, byteRate)
        writeInt16Le(out, 32, blockAlign)
        writeInt16Le(out, 34, bitsPerSample)

        writeAscii(out, 36, "data")
        writeInt32Le(out, 40, pcmLength)          // Subchunk2Size
        return out
    }

    /** 写入 4 个 ASCII 字符。 */
    private fun writeAscii(out: ByteArray, offset: Int, s: String) {
        for (i in s.indices) out[offset + i] = s[i].code.toByte()
    }

    /** 小端 16 位。 */
    private fun writeInt16Le(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    /** 小端 32 位。 */
    private fun writeInt32Le(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
