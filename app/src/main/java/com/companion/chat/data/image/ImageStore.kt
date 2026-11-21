package com.companion.chat.data.image

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 把相册选中的图片复制一份到 App 私有目录，返回绝对路径，供聊天气泡长期展示。
 * 原因：相册 content:// Uri 的读取授权是临时的，App 重启后可能失效，因此必须拷贝。
 * 任务 1.6 补充（图片气泡展示）
 */
class ImageStore(private val context: Context) {

    fun save(uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
            val ext = extOf(context.contentResolver.getType(uri))
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
