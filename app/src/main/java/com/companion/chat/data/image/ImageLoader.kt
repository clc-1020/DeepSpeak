package com.companion.chat.data.image

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.companion.chat.data.remote.ImageAttachment
import com.companion.chat.data.remote.ImageFormats
import java.io.IOException

/**
 * 把本地图片 Uri 读成 base64 data URL（供多模态请求）。任务 1.6
 * 约束：单图 ≤ 32MiB；仅 JPEG/PNG/GIF/WebP（按文件头判断）。
 */
class ImageLoader(private val context: Context) {

    fun load(uri: Uri): Result<ImageAttachment> {
        return try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IOException("无法读取图片"))
            if (bytes.size > MAX_BYTES) {
                return Result.failure(IllegalArgumentException("图片超过 32MiB，请压缩后再发"))
            }
            val format = ImageFormats.detect(bytes)
                ?: resolver.getType(uri)
                ?: return Result.failure(IllegalArgumentException("无法识别图片格式"))
            if (format !in ImageFormats.SUPPORTED) {
                return Result.failure(IllegalArgumentException("不支持的图片格式：$format"))
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Result.success(ImageAttachment("data:$format;base64,$b64"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
