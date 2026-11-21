package com.companion.chat.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** WAV 头字段 + 音量换算单测（纯 JVM，无 Robolectric）。任务 V2.3.2 */
class WavHeaderTest {

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    // ---------- WAV 头字段 ----------

    @Test
    fun header_is_44_bytes_and_all_default_fields_correct() {
        val pcm = 3200
        val h = WavHeader.buildWavHeader(pcm)
        assertEquals(44, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals(36L + pcm, u32(h, 4))       // ChunkSize
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16L, u32(h, 16))            // Subchunk1Size
        assertEquals(1, u16(h, 20))              // AudioFormat = PCM
        assertEquals(1, u16(h, 22))              // 单声道
        assertEquals(16000L, u32(h, 24))         // SampleRate
        assertEquals(32000L, u32(h, 28))         // ByteRate = 16000*1*16/8
        assertEquals(2, u16(h, 32))              // BlockAlign = 1*16/8
        assertEquals(16, u16(h, 34))             // BitsPerSample
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(pcm.toLong(), u32(h, 40))   // Subchunk2Size
    }

    @Test
    fun stereo_44100_fields_are_correct() {
        val pcm = 44100 * 2 * 2 // 1 秒 44.1k 立体声 16bit
        val h = WavHeader.buildWavHeader(
            pcmLength = pcm,
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
        )
        assertEquals(44, h.size)
        assertEquals(2, u16(h, 22))
        assertEquals(44100L, u32(h, 24))
        assertEquals(176400L, u32(h, 28))        // 44100*2*16/8
        assertEquals(4, u16(h, 32))              // 2*16/8
        assertEquals(16, u16(h, 34))
        assertEquals(36L + pcm, u32(h, 4))
        assertEquals(pcm.toLong(), u32(h, 40))
    }

    @Test
    fun zero_pcm_length_is_valid() {
        val h = WavHeader.buildWavHeader(0)
        assertEquals(44, h.size)
        assertEquals(36L, u32(h, 4))
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(0L, u32(h, 40))
    }

    @Test
    fun invalid_args_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { WavHeader.buildWavHeader(-1) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.buildWavHeader(0, sampleRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.buildWavHeader(0, channels = 0) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.buildWavHeader(0, bitsPerSample = 12) }
    }

    // ---------- 音量换算 ----------

    @Test
    fun volume_all_zero_is_zero() {
        assertEquals(0, rmsToVolumeLevel(ByteArray(64), 0, 64))
    }

    @Test
    fun volume_full_scale_is_100() {
        // +满幅 32767、-满幅 -32768、两者混合
        assertEquals(100, rmsToVolumeLevel(byteArrayOf(0xFF.toByte(), 0x7F), 0, 2))
        assertEquals(100, rmsToVolumeLevel(byteArrayOf(0x00, 0x80.toByte()), 0, 2))
        assertEquals(
            100,
            rmsToVolumeLevel(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), 0, 4),
        )
    }

    @Test
    fun volume_half_scale_is_50() {
        assertEquals(50, rmsToVolumeLevel(byteArrayOf(0x00, 0x40), 0, 2)) // 0x4000 = 16384
    }

    @Test
    fun volume_odd_length_discards_last_byte() {
        // 只有前两字节构成一个半幅样本；若末字节被误当高字节，结果会偏离 50
        assertEquals(50, rmsToVolumeLevel(byteArrayOf(0x00, 0x40, 0x00), 0, 3))
    }

    @Test
    fun volume_zero_length_is_zero() {
        assertEquals(0, rmsToVolumeLevel(ByteArray(8), 0, 0))
    }

    @Test
    fun volume_respects_offset() {
        val data = byteArrayOf(0, 0, 0x00, 0x40, 0, 0)
        assertEquals(50, rmsToVolumeLevel(data, 2, 2))
    }

    // ---------- 自动停截断（V2.3.2 遗留缺陷修复） ----------

    @Test
    fun truncate_keeps_pcm_when_under_limit() {
        val pcm = ByteArray(3_840_000) { it.toByte() }
        assertSame(pcm, truncatePcmToMaxDuration(pcm, 16000, 120_000))
    }

    @Test
    fun truncate_cuts_to_exact_max_bytes() {
        val pcm = ByteArray(3_846_144) { (it and 0xFF).toByte() }
        val out = truncatePcmToMaxDuration(pcm, 16000, 120_000)
        assertEquals(3_840_000, out.size) // 16000 * 120 * 2
        for (i in 0 until 8) assertEquals(pcm[i], out[i])
    }

    @Test
    fun truncate_fixes_the_939_batch_auto_stop_overrun() {
        // 审查实算：939 批 × 4096 B = 3,846,144 B → ASR 算成 120,192 ms，被 120,000 ms 闸门拦掉。
        val overrun = ByteArray(939 * 4096)
        val trimmed = truncatePcmToMaxDuration(overrun, 16000, 120_000)
        val durationMs = trimmed.size.toLong() * 1000L / (16000L * 1L * 16L / 8L)
        assertEquals(3_840_000, trimmed.size)
        assertEquals(120_000L, durationMs) // 恰好不超闸门
    }

    @Test
    fun truncate_rounds_max_bytes_down_to_even_sample_boundary() {
        // 1500 * 1 * 2 / 1000 = 3 → 向下取偶 = 2，不能切出半个 16 位样本
        val out = truncatePcmToMaxDuration(ByteArray(10), 1500, 1)
        assertEquals(2, out.size)
    }

    @Test
    fun truncate_tiny_limit_returns_empty() {
        // 100 * 1 * 2 / 1000 = 0：连半个样本都不到
        assertEquals(0, truncatePcmToMaxDuration(ByteArray(10), 100, 1).size)
    }

    @Test
    fun truncate_rejects_invalid_args() {
        assertThrows(IllegalArgumentException::class.java) {
            truncatePcmToMaxDuration(ByteArray(4), 0, 120_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            truncatePcmToMaxDuration(ByteArray(4), 16000, 0)
        }
    }
}
