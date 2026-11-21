package com.companion.chat.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * HEIC / HEIF → JPEG 本地转换。V1.6.2.5
 *
 * **为什么必须有**：`ImageFormats.SUPPORTED` 只有 jpeg/png/gif/webp，而现代安卓相机默认出 HEIC。
 * 不转的话用户相册里**每张照片**都会在发送时被 "不支持的图片格式：image/heic" 拒掉。
 *
 * **实现要点**
 * - 用系统 `ImageDecoder`（API 28+）；`setTargetSize` 先把长边压到 [MAX_EDGE] 再解码，
 *   避免 12MP 原图解码成 48 MB Bitmap 在低端机上 OOM；
 * - 重编码固定 JPEG quality 90：照片视觉无损，体积比 HEIC 略大但可接受；
 * - API 26/27 没有 ImageDecoder，返回 null，由调用方提示"转成 JPG 再发"；
 * - 全程不抛异常（调用方只拿到 null）。
 *
 * **为什么不复用 ImageLoader 的 32MiB 闸门**：HEIC 是重新编码的，原始体积无意义；
 * 解码后按 [MAX_EDGE] 输出，十几 MB 的手机原图也会变成 ~200KB 的 JPEG。
 */
internal object HeicToJpeg {

    /** 重编码后长边上限。1568 是主流视觉模型的常见边长上限，再大也没收益。 */
    private const val MAX_EDGE = 1568
    private const val JPEG_QUALITY = 90

    /** 各 ROM 报的 HEIC/HEIF MIME 不一样，全部收进来。 */
    private val HEIC_MIMES = setOf(
        "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"
    )

    /**
     * ISO-BMFF 头：`[4..8)` 是 "ftyp"，`[8..12)` 是 brand。
     * 手机拍的 HEIC 通常是 `ftypheic` / `ftypheix`；苹果/部分机型用 `mif1` / `msf1` 容器。
     */
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

    /** 依据 MIME 或文件头判断是不是 HEIC（不信后缀名）。 */
    fun looksLikeHeic(head: ByteArray?, mime: String?): Boolean {
        if (mime != null && mime.lowercase() in HEIC_MIMES) return true
        if (head == null || head.size < 12) return false
        if (String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        return String(head, 8, 4, Charsets.US_ASCII) in HEIC_BRANDS
    }

    /** 转成 JPEG 字节；不可用（系统 < 9 / 解码失败 / 编码失败）一律返回 null。 */
    fun convert(context: Context, uri: Uri): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = false
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_EDGE) {
                    val scale = MAX_EDGE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
            ByteArrayOutputStream().use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bitmap.recycle()
                if (ok) out.toByteArray() else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/** 读文件头若干字节：类型判断只靠它，不加载整张图。读不到返回 null。 */
internal fun readFileHead(context: Context, uri: Uri, size: Int = 16): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(size)
        val n = input.read(buf)
        if (n > 0) buf.copyOf(n) else null
    }
}.getOrNull()