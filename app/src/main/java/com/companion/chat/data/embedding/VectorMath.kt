package com.companion.chat.data.embedding

import kotlin.math.sqrt

/**
 * 向量数学工具（纯函数，可 JVM 单测）。任务 2.1
 *
 * 记忆语义去重（任务 2.3）与召回都用余弦相似度；向量已 L2 归一化时点积即余弦。
 * 全部做成纯函数，便于脱离 Android / ONNX 做单元测试。
 */
object VectorMath {

    /** 点积。维度不一致抛 [IllegalArgumentException]。 */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "向量维度不一致: ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** L2 模长。 */
    fun l2Norm(a: FloatArray): Float {
        var s = 0f
        for (v in a) s += v * v
        return sqrt(s)
    }

    /** 返回归一化后的**副本**（不改入参）。零向量原样返回零向量。 */
    fun l2Normalize(a: FloatArray): FloatArray {
        val n = l2Norm(a)
        if (n == 0f) return a.copyOf()
        val out = FloatArray(a.size)
        for (i in a.indices) out[i] = a[i] / n
        return out
    }

    /**
     * 余弦相似度，范围 [-1, 1]。
     * 任一为零向量、维度不一致或为空时返回 0（视为不相似；不抛异常，方便去重循环容错）。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        val na = l2Norm(a)
        val nb = l2Norm(b)
        if (na == 0f || nb == 0f) return 0f
        return dot(a, b) / (na * nb)
    }

    /**
     * 按 attention mask 做 mean pooling，得到句向量（未归一化）。
     *
     * @param tokenEmbeddings [seq][hidden]，模型最后一层每个 token 的隐藏态
     * @param mask [seq]，非 0 的 token 参与平均（0 是 padding）
     * @return [hidden] 维句向量；若全被 mask 掉返回零向量
     */
    fun meanPool(tokenEmbeddings: Array<FloatArray>, mask: IntArray): FloatArray {
        if (tokenEmbeddings.isEmpty()) return FloatArray(0)
        val hidden = tokenEmbeddings[0].size
        val out = FloatArray(hidden)
        var count = 0
        for (i in tokenEmbeddings.indices) {
            if (i < mask.size && mask[i] == 0) continue
            val row = tokenEmbeddings[i]
            val n = minOf(hidden, row.size)
            for (j in 0 until n) out[j] += row[j]
            count++
        }
        if (count > 0) {
            val inv = 1f / count
            for (j in 0 until hidden) out[j] = out[j] * inv
        }
        return out
    }
}
