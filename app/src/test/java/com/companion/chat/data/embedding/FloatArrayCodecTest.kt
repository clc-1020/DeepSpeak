package com.companion.chat.data.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** FloatArray <-> ByteArray 编解码单测（Room embedding 列持久化用）。任务 2.1 */
class FloatArrayCodecTest {

    @Test
    fun roundtrip_preserves_values() {
        val v = floatArrayOf(0f, 1f, -1.5f, 3.14159f, 1e-3f, 12345.678f)
        val back = FloatArrayCodec.fromBytes(FloatArrayCodec.toBytes(v))
        org.junit.Assert.assertArrayEquals(v, back, 0f)
    }

    @Test
    fun roundtrip_384_dim() {
        val v = FloatArray(384) { it * 0.001f }
        val back = FloatArrayCodec.fromBytes(FloatArrayCodec.toBytes(v))
        assertEquals(384, back.size)
        org.junit.Assert.assertArrayEquals(v, back, 0f)
    }

    @Test
    fun bytes_length_is_4_per_float() {
        assertEquals(12, FloatArrayCodec.toBytes(floatArrayOf(1f, 2f, 3f)).size)
    }

    @Test
    fun empty_roundtrip() {
        assertEquals(0, FloatArrayCodec.fromBytes(FloatArrayCodec.toBytes(floatArrayOf())).size)
    }

    @Test
    fun invalid_bytes_length_throws() {
        try {
            FloatArrayCodec.fromBytes(ByteArray(3))
            fail("应当抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望
        }
    }
}
