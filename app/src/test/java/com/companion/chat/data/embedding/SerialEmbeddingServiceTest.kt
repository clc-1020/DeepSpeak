package com.companion.chat.data.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** SerialEmbeddingService 队列化/串行化单测（假实现，JVM）。任务 2.1 */
class SerialEmbeddingServiceTest {

    private class FakeEmbedding(
        override val isReady: Boolean = true,
        override val dimensions: Int = 3,
        private val delayMs: Long = 10
    ) : EmbeddingService {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val calls = AtomicInteger(0)

        override suspend fun embed(text: String): FloatArray {
            calls.incrementAndGet()
            val cur = active.incrementAndGet()
            maxActive.updateAndGet { m -> maxOf(m, cur) }
            delay(delayMs)
            active.decrementAndGet()
            return floatArrayOf(1f, 0f, 0f)
        }
    }

    @Test
    fun serializes_concurrent_calls() = runBlocking {
        val fake = FakeEmbedding()
        val svc = SerialEmbeddingService(fake)
        coroutineScope {
            repeat(8) { launch(Dispatchers.Default) { svc.embed("x") } }
        }
        // 关键断言：被 Mutex 串行化 → 同时只会有 1 个推理在跑
        assertEquals(1, fake.maxActive.get())
        assertEquals(8, fake.calls.get())
    }

    @Test
    fun passes_through_result_and_metadata() = runBlocking {
        val fake = FakeEmbedding(isReady = true, dimensions = 3)
        val svc = SerialEmbeddingService(fake)
        val v = svc.embed("x")
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), v, 1e-6f)
        assertTrue(svc.isReady)
        assertEquals(3, svc.dimensions)
    }

    @Test
    fun propagates_not_ready_state() {
        val fake = FakeEmbedding(isReady = false, dimensions = 0)
        val svc = SerialEmbeddingService(fake)
        assertFalse(svc.isReady)
        assertEquals(0, svc.dimensions)
    }
}
