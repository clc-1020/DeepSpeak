package com.companion.chat.data.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PcmUtils 的纯 JVM 单测。任务 V2.3.1「ASR 端口 + PCM/WAV 工具」 */
class PcmUtilsTest {

    // ---------- 测试用小工具（手工拼字节，不复用被测代码，避免自证） ----------

    private fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun u32(value: Int): ByteArray =
        ByteArray(4) { i -> ((value.toLong() shr (i * 8)) and 0xFF).toByte() }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /** 按有符号 16 位读出一个采样，便于直接断言 32767 / -32768。 */
    private fun sampleAt(bytes: ByteArray, byteIndex: Int): Int =
        ((bytes[byteIndex].toInt() and 0xFF) or
            ((bytes[byteIndex + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    /**
     * 手工拼 WAV：RIFF/WAVE + fmt(16) + [extraChunks] + data。
     * extraChunks 的 payload 按 RIFF 规则做偶数字节对齐。
     */
    private fun buildWav(
        pcm: ByteArray,
        extraChunks: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val fmt = byteArrayOf(
            1, 0,                       // audioFormat = PCM
            1, 0,                       // 单声道
            *u32(16000),                // sampleRate
            *u32(32000),                // byteRate
            2, 0,                       // blockAlign
            16, 0                       // bitsPerSample
        )
        val parts = mutableListOf<ByteArray>()
        parts += ascii("fmt ") + u32(fmt.size) + fmt
        for ((id, payload) in extraChunks) {
            val pad = if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
            parts += ascii(id) + u32(payload.size) + payload + pad
        }
        parts += ascii("data") + u32(pcm.size) + pcm
        val body = ascii("WAVE") + parts.reduce { a, b -> a + b }
        return ascii("RIFF") + u32(body.size) + body
    }

    @Test
    fun stripWavHeader_standard44ByteHeader_returnsPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4, 0x7f, 0x80.toByte())
        val wav = buildWav(pcm)
        assertEquals(44 + pcm.size, wav.size) // 证明这是标准 44 字节头
        assertArrayEquals(pcm, PcmUtils.stripWavHeader(wav))
    }

    @Test
    fun stripWavHeader_withListAndFactChunks_stillFindsData() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val listPayload = ascii("INFO") + byteArrayOf(1, 2, 3, 4, 5) // 9 字节，需补 pad
        val wav = buildWav(pcm, listOf("LIST" to listPayload, "fact" to u32(2)))
        assertTrue(wav.size > 44 + pcm.size) // 证明头部已不是 44 字节
        assertArrayEquals(pcm, PcmUtils.stripWavHeader(wav))
    }

    @Test
    fun stripWavHeader_noDataChunk_throws() {
        val fmt = ByteArray(16)
        val body = ascii("WAVE") + ascii("fmt ") + u32(fmt.size) + fmt
        val wav = ascii("RIFF") + u32(body.size) + body
        try {
            PcmUtils.stripWavHeader(wav)
            fail("没有 data 块时应抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望
        }
    }

    @Test
    fun s16leToFloat_representativeValues() {
        val floats = PcmUtils.s16leToFloat(
            byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        )
        assertEquals(3, floats.size)
        assertEquals(0f, floats[0], 0f)
        assertEquals(0.99997f, floats[1], 0.0001f)
        assertEquals(-1f, floats[2], 0f)
    }

    @Test
    fun floatToS16le_nearFullScale_saturatesToMaxShort() {
        val bytes = PcmUtils.floatToS16le(floatArrayOf(0.9999f))
        assertEquals(2, bytes.size)
        assertEquals(32767, sampleAt(bytes, 0))
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x7F.toByte(), bytes[1])
    }

    @Test
    fun floatToS16le_minusOne_mapsToMinShort() {
        val bytes = PcmUtils.floatToS16le(floatArrayOf(-1f))
        assertEquals(-32768, sampleAt(bytes, 0))
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x80.toByte(), bytes[1])
    }

    @Test
    fun floatToS16le_outOfRange_clamps() {
        val bytes = PcmUtils.floatToS16le(floatArrayOf(3f, -3f))
        assertEquals(32767, sampleAt(bytes, 0))
        assertEquals(-32768, sampleAt(bytes, 2))
    }

    @Test
    fun pcmToWav_headerFieldsAreLittleEndianAndCorrect() {
        val pcm = ByteArray(8) { (it + 1).toByte() }
        val wav = PcmUtils.pcmToWav(pcm, sampleRate = 44100, channels = 2)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", readAscii(wav, 0, 4))
        assertEquals(36L + pcm.size, readU32(wav, 4))
        assertEquals("WAVE", readAscii(wav, 8, 4))
        assertEquals("fmt ", readAscii(wav, 12, 4))
        assertEquals(16L, readU32(wav, 16))
        assertEquals(1, readU16(wav, 20))                    // PCM
        assertEquals(2, readU16(wav, 22))                    // channels
        assertEquals(44100L, readU32(wav, 24))               // sampleRate
        assertEquals((44100 * 2 * 2).toLong(), readU32(wav, 28)) // byteRate
        assertEquals(4, readU16(wav, 32))                    // blockAlign
        assertEquals(16, readU16(wav, 34))                   // bitsPerSample
        assertEquals("data", readAscii(wav, 36, 4))
        assertEquals(pcm.size.toLong(), readU32(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun pcmToWav_defaults_are16kMono() {
        val wav = PcmUtils.pcmToWav(ByteArray(4))
        assertEquals(16000L, readU32(wav, 24))
        assertEquals(1, readU16(wav, 22))
        assertEquals(32000L, readU32(wav, 28))
        assertEquals(2, readU16(wav, 32))
    }

    @Test
    fun pcmToWav_roundTrip_restoresOriginalPcm() {
        val pcm = ByteArray(64) { ((it * 7) % 256).toByte() }
        assertArrayEquals(pcm, PcmUtils.stripWavHeader(PcmUtils.pcmToWav(pcm)))
        val stereo = ByteArray(40) { ((it * 13) % 256).toByte() }
        assertArrayEquals(stereo, PcmUtils.stripWavHeader(PcmUtils.pcmToWav(stereo, 48000, 2)))
    }

    @Test
    fun s16FloatRoundTrip_preservesSamples() {
        val raw = shortArrayOf(0, 1, -1, 32767, -32768, 12345, -23456)
        val pcm = ByteArray(raw.size * 2)
        for (i in raw.indices) {
            pcm[i * 2] = (raw[i].toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((raw[i].toInt() shr 8) and 0xFF).toByte()
        }
        val floats = PcmUtils.s16leToFloat(pcm)
        assertArrayEquals(pcm, PcmUtils.floatToS16le(floats))
    }
}
