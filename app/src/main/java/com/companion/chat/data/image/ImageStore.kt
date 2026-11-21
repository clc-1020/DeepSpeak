package com.companion.chat.data.image

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 把相册选中的图片复制一份到 App 私有目录，返回绝对路径，供聊天气泡长期展示。
 * 原因：相册 content:// Uri 的读取授权是临时的，App 重启后可能失效，因此必须拷贝。
 * 任务 1.6 补充（图片气泡展示）
 *
 * V1.6.2.5：HEIC 存的是**转换后的 JPEG**。否则会写出"扩展名 .jpg、内容却是 HEIC"的文件，
 * 气泡里的 Coil 会解码失败（显示空白），等于发出去能看、自己的历史里看不到。
 */
class ImageStore(private val context: Context) {

    fun save(uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            val head = readFileHead(context, uri)

            val converted = if (HeicToJpeg.looksLikeHeic(head, mime)) {
                HeicToJpeg.convert(context, uri) ?: return null
            } else {
                null
            }

            val bytes = converted
                ?: resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null

            val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
            val ext = if (converted != null) ".jpg" else extOf(mime)
            val file = File(dir, "${UUID.randomUUID()}$ext")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun extOf(mime: String?): String = when (mime) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }
}