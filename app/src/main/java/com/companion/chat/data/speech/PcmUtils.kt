package com.companion.chat.data.speech

import kotlin.math.roundToInt

/**
 * PCM / WAV 纯函数工具。任务 V2.3.1「ASR 端口 + PCM/WAV 工具」
 *
 * 不依赖任何 Android API，可直接在 JVM 单测里跑；data 层只负责调用这些纯函数。
 */
object PcmUtils {

    private const val RIFF = "RIFF"
    private const val WAVE = "WAVE"
    private const val DATA = "data"
    private const val FMT = "fmt "

    /** 16 位有符号量化下限 / 上限。 */
    private const val MIN_S16 = -32768
    private const val MAX_S16 = 32767

    /** 接近正满刻度时直接饱和到 32767（0.9999 在 16 位量化里已属顶端一档）。 */
    private const val SATURATE_FROM = 0.9999f

    /**
     * 剥掉 WAV 头部，只返回 data 块的 PCM 字节。
     *
     * 按 RIFF chunk 链表逐个解析（fmt / LIST / fact / data …），**不硬编码 44 字节**——
     * 手机录音常在 fmt 与 data 之间插入 LIST、fact 等块。chunk 长度按偶数字节对齐，
     * 奇数长度会跳过 1 字节 pad。
     *
     * @throws IllegalArgumentException RIFF/WAVE 标识不符、长度非法或没有 data 块。
     */
    fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 12) {
            throw IllegalArgumentException("不是有效的 WAV：长度不足 12 字节")
        }
        if (ascii(bytes, 0) != RIFF || ascii(bytes, 8) != WAVE) {
            throw IllegalArgumentException("不是有效的 RIFF/WAVE 文件")
        }

        var pos = 12L
        while (pos + 8 <= bytes.size.toLong()) {
            val id = ascii(bytes, pos.toInt())
            val size = readU32(bytes, pos.toInt() + 4)
            val dataStart = pos + 8
            if (id == DATA) {
                val dataEnd = dataStart + size
                if (dataEnd > bytes.size.toLong()) {
                    throw IllegalArgumentException("data 块声明长度超出文件实际长度")
                }
                return bytes.copyOfRange(dataStart.toInt(), dataEnd.toInt())
            }
            val next = dataStart + size + (size and 1L) // 奇数长度补 1 字节对齐
            if (next <= pos) throw IllegalArgumentException("WAV chunk 长度非法")
            pos = next
        }
        throw IllegalArgumentException("WAV 中未找到 data 块")
    }

    /**
     * 16 位小端 PCM → 归一化浮点（[-1, 1]）。
     *
     * 每个采样按 `(low or (high shl 8))` 合成 16 位有符号数后除以 32768f。
     * 0x00,0x00 → 0f；0xFF,0x7F → ≈0.99997f；0x00,0x80 → -1f。
     */
    fun s16leToFloat(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "16 位 PCM 字节数必须为偶数，实际 ${bytes.size}" }
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() and 0xFF
            out[i] = (low or (high shl 8)).toShort().toFloat() / 32768f
        }
        return out
    }

    /**
     * 归一化浮点 → 16 位小端 PCM。
     *
     * 先 clamp 到 [-1, 1]，再乘 32768 四舍五入；接近正满刻度（≥0.9999）饱和到 32767，
     * -1.0 映射到 -32768。NaN 按 0 处理。
     */
    fun floatToS16le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = quantize(samples[i])
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 给裸 PCM 补标准 44 字节 RIFF/WAVE 头。
     *
     * 字段：RIFF + 大小(36+pcm) + WAVE + fmt(16, PCM=1, channels, sampleRate,
     * byteRate=sampleRate*channels*2, blockAlign=channels*2, bits=16) + data + 大小。
     * 所有多字节字段按小端写入。默认 16 kHz / 单声道，符合 ASR 上传要求。
     */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        require(sampleRate > 0) { "采样率必须为正，实际 $sampleRate" }
        require(channels > 0) { "声道数必须为正，实际 $channels" }

        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val header = ByteArray(44)
        copyAscii(header, 0, RIFF)
        writeU32(header, 4, 36L + pcm.size)
        copyAscii(header, 8, WAVE)
        copyAscii(header, 12, FMT)
        writeU32(header, 16, 16)
        writeU16(header, 20, 1)                 // audioFormat = PCM
        writeU16(header, 22, channels)
        writeU32(header, 24, sampleRate.toLong())
        writeU32(header, 28, byteRate.toLong())
        writeU16(header, 32, blockAlign)
        writeU16(header, 34, 16)                // bitsPerSample
        copyAscii(header, 36, DATA)
        writeU32(header, 40, pcm.size.toLong())
        return header + pcm
    }

    private fun quantize(sample: Float): Int {
        val c = if (sample.isNaN()) 0f else sample.coerceIn(-1f, 1f)
        if (c >= SATURATE_FROM) return MAX_S16
        return (c * 32768f).roundToInt().coerceIn(MIN_S16, MAX_S16)
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun copyAscii(target: ByteArray, offset: Int, text: String) {
        for (i in text.indices) target[offset + i] = text[i].code.toByte()
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) target[offset + i] = ((value shr (i * 8)) and 0xFF).toByte()
    }
}
