package com.companion.chat.data.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * ONNX Runtime 本地 embedding 实现（all-MiniLM-L6-v2，384 维，mean pooling + L2 归一化）。任务 2.1
 *
 * 内存池（memory pool）：
 *  - [OrtEnvironment] 全局复用（进程级单例），绝不反复创建；
 *  - [OrtSession] **单例常驻**在 [session] 里，构造一次、反复 `run()` 复用；
 *    绝不每次调用新建 session/环境（那样每次都会泄漏/占用一份 native 内存，很快 OOM）。
 *
 * 队列化（防抢占）：推理跑在 [Dispatchers.Default]，且约定**必须**由 [SerialEmbeddingService]
 * 包一层串行化——同一 session 上不并发 run。见 `EmbeddingServiceFactory`。
 *
 * 说明：onnxruntime-android 无桌面 native，本类**无法在 JVM 单测运行**，
 * 数值验收（同句 >0.99 等）需真机。纯逻辑（分词/相似度/编解码/排队）已由单测覆盖。
 */
class OnnxEmbeddingService(
    private val modelFile: File,
    private val tokenizer: BertTokenizer,
    private val outputDimensions: Int = ModelFiles.DIMENSIONS,
    /** 池化方式：BGE 用 [Pooling.CLS]（取首 token 隐态），一般 BERT 句向量用 [Pooling.MEAN]。 */
    private val pooling: Pooling = Pooling.MEAN
) : EmbeddingService, Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession by lazy {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(1) // 串行推理 + 省电，单线程足够
        opts.setInterOpNumThreads(1)
        env.createSession(modelFile.absolutePath, opts)
    }

    /** session 实际输入名，用于只提交模型声明过的输入（不同导出可能缺 token_type_ids）。 */
    private val inputNames: Set<String> by lazy { session.inputNames }

    override val isReady: Boolean = true
    override val dimensions: Int = outputDimensions

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext FloatArray(dimensions)
        val tk = tokenizer.tokenize(text)
        val shape = longArrayOf(1L, tk.inputIds.size.toLong())

        val tensors = HashMap<String, OnnxTensor>(3)
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(tk.inputIds), shape)
            if ("attention_mask" in inputNames) {
                tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(tk.attentionMask), shape)
            }
            if ("token_type_ids" in inputNames) {
                tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(tk.tokenTypeIds), shape)
            }
            val result = session.run(tensors)
            try {
                val outTensor = selectOutputTensor(result)
                val pooled = extractEmbedding(outTensor, tk.attentionMask)
                VectorMath.l2Normalize(pooled)
            } finally {
                runCatching { result.close() }
            }
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    /** 优先取名为 last_hidden_state / sentence_embedding 的输出，否则取第 0 个。 */
    private fun selectOutputTensor(result: OrtSession.Result): OnnxTensor {
        var fallback: OnnxTensor? = null
        for (entry in result) {
            val t = entry.value as? OnnxTensor ?: continue
            if (fallback == null) fallback = t
            if (entry.key == "last_hidden_state" || entry.key == "sentence_embedding") return t
        }
        @Suppress("UNCHECKED_CAST")
        val first = result.get(0) as OnnxTensor
        return fallback ?: first
    }

    /**
     * 张量 → 句向量（未归一化）。
     * - rank 2 [1, hidden]：已 pooled，直接取 [0]
     * - rank 3 [1, seq, hidden]：按 attention mask 做 mean pooling（all-MiniLM 走这条）
     */
    private fun extractEmbedding(tensor: OnnxTensor, mask: LongArray): FloatArray {
        val shape = tensor.info.shape
        val value = tensor.value
        return when (shape.size) {
            2 -> {
                @Suppress("UNCHECKED_CAST")
                val rows = value as Array<FloatArray>
                rows[0].copyOf()
            }
            3 -> {
                @Suppress("UNCHECKED_CAST")
                val batch = value as Array<Array<FloatArray>>
                val seq = batch[0]
                when (pooling) {
                    Pooling.CLS -> seq[0].copyOf()
                    Pooling.MEAN -> {
                        val maskInt = IntArray(mask.size) { mask[it].toInt() }
                        VectorMath.meanPool(seq, maskInt)
                    }
                }
            }
            else -> throw IllegalStateException("意外的输出形状: ${shape.toList()}")
        }
    }

    override fun close() {
        runCatching { session.close() }
        // 不关闭全局 OrtEnvironment（进程级共享）
    }
}

/** 句向量池化策略。 */
enum class Pooling { CLS, MEAN }
