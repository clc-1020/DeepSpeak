package com.companion.chat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ImageFormats 魔数识别测试（JVM）。任务 1.6 */
class ImageFormatsTest {

    @Test
    fun detect_jpeg() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("image/jpeg", ImageFormats.detect(bytes))
    }

    @Test
    fun detect_png() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("image/png", ImageFormats.detect(bytes))
    }

    @Test
    fun detect_gif() {
        val bytes = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        assertEquals("image/gif", ImageFormats.detect(bytes))
    }

    @Test
    fun detect_webp() {
        val bytes = ByteArray(12)
        bytes[0] = 0x52; bytes[1] = 0x49; bytes[2] = 0x46; bytes[3] = 0x46
        bytes[8] = 0x57; bytes[9] = 0x45; bytes[10] = 0x42; bytes[11] = 0x50
        assertEquals("image/webp", ImageFormats.detect(bytes))
    }

    @Test
    fun unknown_returns_null() {
        assertNull(ImageFormats.detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }
}
