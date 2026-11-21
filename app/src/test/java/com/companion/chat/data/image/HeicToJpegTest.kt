package com.companion.chat.data.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HEIC 识别的纯函数单测。V1.6.2.5
 *
 * 这段逻辑只读 12~16 字节，却决定"照片能不能发"，很容易写错（brand 偏移、大小写、容器类型）。
 * AudioRecord/ImageDecoder 本身没法 JVM 测，但这部分可以，且值得钉住。
 */
class HeicToJpegTest {

    private fun ftyp(brand: String): ByteArray {
        // 00 00 00 18 | 'ftyp' | brand | minor version
        val head = ByteArray(16)
        head[0] = 0x00; head[1] = 0x00; head[2] = 0x00; head[3] = 0x18
        "ftyp".forEachIndexed { i, c -> head[4 + i] = c.code.toByte() }
        brand.forEachIndexed { i, c -> head[8 + i] = c.code.toByte() }
        return head
    }

    @Test
    fun mime_alone_is_enough() {
        assertTrue(HeicToJpeg.looksLikeHeic(null, "image/heic"))
        assertTrue(HeicToJpeg.looksLikeHeic(null, "image/heif"))
        assertTrue(HeicToJpeg.looksLikeHeic(null, "image/heic-sequence"))
        // 大小写不同的 ROM 也有
        assertTrue(HeicToJpeg.looksLikeHeic(null, "IMAGE/HEIC"))
    }

    @Test
    fun ftyp_brands_from_phones_are_recognized() {
        listOf("heic", "heix", "hevc", "mif1", "msf1").forEach { brand ->
            assertTrue("brand=$brand 应识别为 HEIC", HeicToJpeg.looksLikeHeic(ftyp(brand), null))
        }
    }

    @Test
    fun mp4_and_other_bmff_are_not_heic() {
        // 视频是同一套 ISO-BMFF 容器，brand 不同，不能被误判
        assertFalse(HeicToJpeg.looksLikeHeic(ftyp("mp42"), null))
        assertFalse(HeicToJpeg.looksLikeHeic(ftyp("isom"), null))
    }

    @Test
    fun regular_images_are_not_heic() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(HeicToJpeg.looksLikeHeic(jpeg, "image/jpeg"))
        assertFalse(HeicToJpeg.looksLikeHeic(png, "image/png"))
        // 图省事的 provider 报 octet-stream，头也不是 HEIC → 不能误判
        assertFalse(HeicToJpeg.looksLikeHeic(jpeg, "application/octet-stream"))
    }

    @Test
    fun too_short_or_missing_head_is_not_heic() {
        assertFalse(HeicToJpeg.looksLikeHeic(null, null))
        assertFalse(HeicToJpeg.looksLikeHeic(ByteArray(0), null))
        assertFalse(HeicToJpeg.looksLikeHeic(ByteArray(11), null))
        // 长度够但 [4..8) 不是 ftyp
        val junk = ByteArray(16) { 0x41 }
        assertFalse(HeicToJpeg.looksLikeHeic(junk, null))
    }
}