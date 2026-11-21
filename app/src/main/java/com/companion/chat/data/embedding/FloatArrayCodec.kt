package com.companion.chat.data.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatArray <-> ByteArray 编解码，用于把 384 维向量存进 Room 的 embedding 列
 * （任务 1.1 预留的 `MemoryShortEntity.embedding` / `MemoryLongEntity.embedding`）。任务 2.1 / 2.3
 *
 * 固定**小端序、每维 4 字节**，保证跨设备、跨持久化一致。
 */
object FloatArrayCodec {

    /** 每维字节数。 */
    const val BYTES_PER_FLOAT = 4

    fun toBytes(array: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(array.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        for (v in array) buf.putFloat(v)
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        require(bytes.size % BYTES_PER_FLOAT == 0) {
            "字节数必须是 $BYTES_PER_FLOAT 的倍数，实际 ${bytes.size}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / BYTES_PER_FLOAT)
        for (i in out.indices) out[i] = buf.getFloat()
        return out
    }
}
