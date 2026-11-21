package com.companion.chat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选图类型判定的纯函数单测（加固 A / V1.6.2.6）。
 *
 * 这段逻辑决定"用户挑的这一批文件能不能进预览条"，错一次就是把好照片拦掉、
 * 或者把 PDF 放进发送链路。纯函数，钉住不亏。
 */
class ImagePickValidationTest {

    private fun pngHead() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    private fun jpegHead() = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    )

    /** `ftyp` + brand 的 ISO-BMFF 头（HEIC / MP4 共用容器）。 */
    private fun ftypHead(brand: String): ByteArray {
        val head = ByteArray(16)
        head[3] = 0x18
        "ftyp".forEachIndexed { i, c -> head[4 + i] = c.code.toByte() }
        brand.forEachIndexed { i, c -> head[8 + i] = c.code.toByte() }
        return head
    }

    private fun pdfHead() = "%PDF-1.7\n%".toByteArray(Charsets.US_ASCII)

    @Test
    fun whitelisted_mimes_pass_without_touching_the_file() {
        assertTrue(isSendableImagePick("image/jpeg", null))
        assertTrue(isSendableImagePick("image/png", null))
        assertTrue(isSendableImagePick("image/gif", null))
        assertTrue(isSendableImagePick("image/webp", null))
    }

    @Test
    fun heic_passes_because_data_layer_converts_it() {
        // V1.6.2.5 刚做了 HEIC→JPG，这里拦住就等于把功能关回去
        assertTrue(isSendableImagePick("image/heic", null))
        assertTrue(isSendableImagePick("image/heif", null))
        assertTrue(isSendableImagePick(null, ftypHead("heic")))
        assertTrue(isSendableImagePick(null, ftypHead("mif1")))
    }

    @Test
    fun magic_number_rescues_lying_provider() {
        // 网盘/文件管理器常报 application/octet-stream，头是真 JPEG
        assertTrue(isSendableImagePick("application/octet-stream", jpegHead()))
        assertTrue(isSendableImagePick(null, pngHead()))
    }

    @Test
    fun documents_are_rejected() {
        assertFalse(isSendableImagePick("application/pdf", pdfHead()))
        assertFalse(isSendableImagePick("text/calendar", "BEGIN:VCALENDAR".toByteArray()))
        assertFalse(
            isSendableImagePick(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            )
        )
    }

    @Test
    fun mp4_is_not_mistaken_for_heic() {
        assertFalse(isSendableImagePick("video/mp4", ftypHead("mp42")))
    }

    @Test
    fun unknown_or_missing_input_is_rejected() {
        assertFalse(isSendableImagePick(null, null))
        assertFalse(isSendableImagePick(null, ByteArray(0)))
        assertFalse(isSendableImagePick(null, ByteArray(5)))
    }
}