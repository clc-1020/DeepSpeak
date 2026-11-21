package com.companion.chat.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VectorMath 纯函数单测：点积/模长/归一化/余弦/mean pooling。任务 2.1 */
class VectorMathTest {

    private val eps = 1e-5f

    @Test
    fun dot_product() {
        assertEquals(32f, VectorMath.dot(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f)), eps)
    }

    @Test
    fun l2_norm() {
        assertEquals(5f, VectorMath.l2Norm(floatArrayOf(3f, 4f)), eps)
    }

    @Test
    fun l2_normalize_makes_unit_length() {
        val v = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], eps)
        assertEquals(0.8f, v[1], eps)
        assertEquals(1f, VectorMath.l2Norm(v), eps)
    }

    @Test
    fun l2_normalize_zero_vector_stays_zero() {
        val v = VectorMath.l2Normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, VectorMath.l2Norm(v), eps)
    }

    @Test
    fun cosine_identical_is_one() {
        val a = floatArrayOf(0.2f, 0.5f, -0.1f, 0.9f)
        assertEquals(1f, VectorMath.cosineSimilarity(a, a), eps)
    }

    @Test
    fun cosine_orthogonal_is_zero() {
        assertEquals(0f, VectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), eps)
    }

    @Test
    fun cosine_opposite_is_minus_one() {
        assertEquals(-1f, VectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), eps)
    }

    @Test
    fun cosine_zero_vector_is_zero() {
        assertEquals(0f, VectorMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), eps)
    }

    @Test
    fun cosine_mismatched_dims_is_zero() {
        assertEquals(0f, VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), eps)
    }

    @Test
    fun cosine_empty_is_zero() {
        assertEquals(0f, VectorMath.cosineSimilarity(floatArrayOf(), floatArrayOf()), eps)
    }

    @Test
    fun dot_mismatched_throws() {
        try {
            VectorMath.dot(floatArrayOf(1f), floatArrayOf(1f, 2f))
            assertTrue("应当抛 IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // 期望
        }
    }

    @Test
    fun mean_pool_all_tokens() {
        val rows = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(3f, 3f))
        val p = VectorMath.meanPool(rows, intArrayOf(1, 1))
        assertEquals(2f, p[0], eps)
        assertEquals(2f, p[1], eps)
    }

    @Test
    fun mean_pool_respects_mask() {
        val rows = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(9f, 9f))
        val p = VectorMath.meanPool(rows, intArrayOf(1, 0))
        assertEquals(1f, p[0], eps)
        assertEquals(1f, p[1], eps)
    }

    @Test
    fun mean_pool_all_masked_is_zero() {
        val rows = arrayOf(floatArrayOf(5f, 5f))
        val p = VectorMath.meanPool(rows, intArrayOf(0))
        assertEquals(0f, p[0], eps)
    }

    @Test
    fun threshold_sanity_near_parallel_vs_orthogonal() {
        // 384 维：b 仅一维微调 → 近义(>0.85)；c 交替翻转 → 正交(<0.5)
        val a = FloatArray(384) { 1f }
        val b = FloatArray(384) { if (it == 0) 1.2f else 1f }
        val c = FloatArray(384) { if (it % 2 == 0) 1f else -1f }
        assertTrue(VectorMath.cosineSimilarity(a, b) > 0.85f)
        assertTrue(VectorMath.cosineSimilarity(a, c) < 0.5f)
    }
}
