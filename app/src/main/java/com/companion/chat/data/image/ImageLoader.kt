package com.companion.chat.data.image

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.companion.chat.data.remote.ImageAttachment
import com.companion.chat.data.remote.ImageFormats
import java.io.IOException

/**
 * 把本地图片 Uri 读成 base64 data URL（供多模态请求）。任务 1.6
 * 约束：单图 ≤ 32MiB；JPEG/PNG/GIF/WebP 按文件头判断。
 *
 * V1.6.2.5：**HEIC/HEIF 不再拒绝，先本地转 JPEG 再走原链路**（[HeicToJpeg]）。
 * 判断顺序改成"先读 16 字节文件头"：这样 HEIC 不必先把十几 MB 原图全读进内存，
 * 也避免了"HEIC 超过 32MiB 直接被体积闸门拒掉"这种没意义的失败。
 */
class ImageLoader(private val context: Context) {

    fun load(uri: Uri): Result<ImageAttachment> {
        return try {
            val resolver = context.contentResolver
            val head = readFileHead(context, uri) ?: return Result.failure(IOException("无法读取图片"))
            val detected = ImageFormats.detect(head)
            val mime = runCatching { resolver.getType(uri) }.getOrNull()

            val format = detected ?: mime
                ?: return Result.failure(IllegalArgumentException("无法识别图片格式"))

            if (format in ImageFormats.SUPPORTED) {
                return loadRaw(uri, format)
            }

            // V1.6.2.5：系统相机默认 HEIC，不转等于每张照片都发不出去
            if (HeicToJpeg.looksLikeHeic(head, mime)) {
                val jpeg = HeicToJpeg.convert(context, uri)
                    ?: return Result.failure(
                        IllegalArgumentException(
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                                "Android 9 以下不支持 HEIC，请在相册里转成 JPG 再发"
                            } else {
                                "HEIC 转 JPG 失败，请换一张或先转成 JPG"
                            }
                        )
                    )
                return Result.success(ImageAttachment("data:image/jpeg;base64," + base64(jpeg)))
            }

            return Result.failure(IllegalArgumentException("不支持的图片格式：$format"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 原生支持的四种格式：整份读进来（有 32MiB 闸门），按魔数报出的真实类型发。 */
    private fun loadRaw(uri: Uri, format: String): Result<ImageAttachment> {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Result.failure(IOException("无法读取图片"))
        if (bytes.size > MAX_BYTES) {
            return Result.failure(IllegalArgumentException("图片超过 32MiB，请压缩后再发"))
        }
        val dataUrl = "data:$format;base64," + base64(bytes)
        runCatching { android.util.Log.w("MMDiag", "loadRaw format=$format rawBytes=${bytes.size} dataUrlChars=${dataUrl.length}") }
        return Result.success(ImageAttachment(dataUrl))
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}