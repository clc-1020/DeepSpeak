package com.companion.chat.data.remote

/** 依据文件头魔数判断图片真实格式（不信文件名/MIME）。任务 1.6 */
object ImageFormats {

    val SUPPORTED: Set<String> = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    fun detect(bytes: ByteArray): String? {
        fun b(i: Int): Int = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return "image/jpeg"
        if (bytes.size >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47) return "image/png"
        if (bytes.size >= 6 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) return "image/gif"
        if (bytes.size >= 12 &&
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
            b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50
        ) return "image/webp"
        return null
    }
}
