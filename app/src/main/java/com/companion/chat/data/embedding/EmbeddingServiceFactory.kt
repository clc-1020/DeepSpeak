package com.companion.chat.data.embedding

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * embedding 服务的单例工厂 / 装配点。任务 2.1
 *
 * 装配链：ModelFiles.ensure()（模型落盘）→ OnnxEmbeddingService（单 session 内存池）
 *        → SerialEmbeddingService（队列化防抢占）。
 *
 * 首次 [ensure] 会把 86MB 模型从 assets 拷到 filesDir 并加载 session——都是重活，
 * 因此放在 [Dispatchers.IO] 的协程里，绝不在主线程做。
 * 模型缺失/加载失败 → 降级 [NoopEmbeddingService]，主流程不受影响。
 */
object EmbeddingServiceFactory {

    @Volatile
    private var cached: EmbeddingService? = null

    /** 同步窥视：已就绪返回实例，未初始化返回 Noop（不触发重活）。 */
    fun peek(): EmbeddingService = cached ?: NoopEmbeddingService("embedding 尚未初始化")

    /** 确保已初始化（首次在 IO 线程落盘 + 加载），返回实例；后续复用。 */
    suspend fun ensure(context: Context): EmbeddingService {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            cached ?: synchronized(this@EmbeddingServiceFactory) {
                cached ?: build(context.applicationContext).also { cached = it }
            }
        }
    }

    private fun build(appContext: Context): EmbeddingService {
        return when (val r = ModelFiles(appContext).ensure()) {
            is ModelFiles.InstallResult.Ok -> {
                val raw = OnnxEmbeddingService(
                    modelFile = r.ready.modelFile,
                    tokenizer = BertTokenizer(r.ready.vocab, maxLen = ModelFiles.MAX_TOKENS),
                    outputDimensions = ModelFiles.DIMENSIONS,
                    // 池化策略与模型绑定，集中在 ModelFiles.POOLING_STRATEGY，换模型改一处即可
                    pooling = when (ModelFiles.POOLING_STRATEGY.uppercase()) {
                        "MEAN" -> Pooling.MEAN
                        else -> Pooling.CLS
                    }
                )
                SerialEmbeddingService(raw) // 队列化防抢占
            }
            is ModelFiles.InstallResult.Missing -> NoopEmbeddingService(r.reason)
        }
    }
}
