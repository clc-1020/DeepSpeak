package com.companion.chat.data.embedding

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 序列化装饰器：把并发的 [embed] 调用**排队**（互斥），同一时刻只跑一个推理。
 * 任务 2.1「队列化防抢占」。
 *
 * 为什么必须串行：ONNX Runtime 单个 OrtSession 在多个线程并发 `run()` 时会争夺同一份
 * native 内存/线程池，轻则吞吐骤降、重则内存暴涨甚至崩溃。用 [Mutex] 让后到的请求排队等待，
 * 绝不在同一 session 上并行抢占。它把任意 [EmbeddingService] 变安全，也便于用假实现做单测。
 */
class SerialEmbeddingService(
    private val delegate: EmbeddingService
) : EmbeddingService {

    private val mutex = Mutex()

    override val isReady: Boolean get() = delegate.isReady
    override val dimensions: Int get() = delegate.dimensions

    override suspend fun embed(text: String): FloatArray =
        mutex.withLock { delegate.embed(text) }
}
