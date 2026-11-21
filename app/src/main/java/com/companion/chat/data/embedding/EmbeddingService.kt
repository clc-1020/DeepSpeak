package com.companion.chat.data.embedding

/**
 * 文本向量化服务抽象。任务 2.1（本地 embedding 模型集成）
 *
 * 约定：返回的向量已做 L2 归一化（模长 = 1），因此两向量点积即余弦相似度，
 * 记忆去重（任务 2.3）可直接比较。
 *
 * 实现可能是本地 ONNX 模型（离线、不花钱），也可能是模型缺失时的 [NoopEmbeddingService]。
 * 上层逻辑（记忆提取/去重/召回）先看 [isReady]，未就绪就跳过语义能力，保证主流程不受影响。
 */
interface EmbeddingService {

    /** 模型是否已就绪可编码。false 时 [embed] 会抛 [EmbeddingUnavailableException]。 */
    val isReady: Boolean

    /** 向量维度（all-MiniLM-L6-v2 = 384）；未就绪返回 0。 */
    val dimensions: Int

    /**
     * 把文本编码为归一化向量（FloatArray，长度 = [dimensions]）。
     * 模型未就绪或文本为空/异常时抛 [EmbeddingUnavailableException]。
     */
    suspend fun embed(text: String): FloatArray
}

/** embedding 不可用（模型缺失/加载失败/空文本）。调用方捕获后跳过语义逻辑即可。 */
class EmbeddingUnavailableException(message: String) : Exception(message)

/**
 * 不可用兜底实现。模型文件缺失或加载失败时使用，让主流程（聊天/记忆管理）照常运行。
 */
class NoopEmbeddingService(
    private val reason: String = "embedding model unavailable"
) : EmbeddingService {
    override val isReady: Boolean = false
    override val dimensions: Int = 0
    override suspend fun embed(text: String): FloatArray =
        throw EmbeddingUnavailableException(reason)
}
