package com.companion.chat.data.power

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WakeLockScope 持锁编排单测（假锁）：acquire→block→必 release。任务 6.1 */
class WakeLockScopeTest {

    private class FakeWakeLock(private val throwOnAcquire: Boolean = false) : WakeLockHandle {
        val events = mutableListOf<String>()
        var lastTimeoutMs: Long = -1
        var acquireCalls = 0
        private var held = false

        override fun acquire(timeoutMs: Long) {
            if (throwOnAcquire) throw IllegalStateException("acquire boom")
            acquireCalls++
            lastTimeoutMs = timeoutMs
            held = true
            events.add("acquire")
        }

        override fun release() {
            if (held) {
                held = false
                events.add("release")
            }
        }

        override fun isHeld(): Boolean = held
    }

    // ---------- run：自动 acquire ----------

    @Test
    fun run_acquires_then_releases_on_success() = runBlocking {
        val lock = FakeWakeLock()
        val value = WakeLockScope.run(lock) { 42 }
        assertEquals(42, value)
        assertEquals(listOf("acquire", "release"), lock.events)
        assertFalse(lock.isHeld())
    }

    @Test
    fun run_passes_default_five_minute_timeout() = runBlocking {
        val lock = FakeWakeLock()
        WakeLockScope.run(lock) { }
        assertEquals(WakeLockPolicy.TIMEOUT_MS, lock.lastTimeoutMs)
        assertEquals(5 * 60_000L, lock.lastTimeoutMs)
    }

    @Test
    fun run_honours_custom_timeout() = runBlocking {
        val lock = FakeWakeLock()
        WakeLockScope.run(lock, timeoutMs = 12_345L) { }
        assertEquals(12_345L, lock.lastTimeoutMs)
    }

    @Test
    fun run_releases_and_rethrows_when_block_fails() = runBlocking {
        val lock = FakeWakeLock()
        var thrown: Throwable? = null
        try {
            WakeLockScope.run(lock) { error("block boom") }
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("block boom", thrown?.message)
        assertEquals(listOf("acquire", "release"), lock.events)
        assertFalse(lock.isHeld())
    }

    @Test
    fun run_releases_when_coroutine_cancelled() = runBlocking {
        val lock = FakeWakeLock()
        val job = launch {
            WakeLockScope.run(lock) { delay(60_000) }
        }
        yield()
        assertTrue(lock.isHeld())
        job.cancel()
        job.join()
        assertFalse(lock.isHeld())
        assertEquals(listOf("acquire", "release"), lock.events)
    }

    @Test
    fun run_releases_even_when_block_outlives_timeout() = runBlocking {
        // 系统级超时只是兜底；finally 的 release 必须在 block 跑完后照样执行
        val lock = FakeWakeLock()
        WakeLockScope.run(lock, timeoutMs = 1L) { delay(30) }
        assertFalse(lock.isHeld())
        assertEquals(listOf("acquire", "release"), lock.events)
    }

    @Test
    fun run_does_not_run_block_when_acquire_fails() = runBlocking {
        val lock = FakeWakeLock(throwOnAcquire = true)
        var ran = false
        var thrown: Throwable? = null
        try {
            WakeLockScope.run(lock) { ran = true }
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertFalse(ran)
        assertEquals("acquire boom", thrown?.message)
        assertTrue(lock.events.isEmpty()) // 没拿到锁就不该 release
    }

    @Test
    fun run_emits_log_after_release() = runBlocking {
        val lock = FakeWakeLock()
        val logs = mutableListOf<String>()
        WakeLockScope.run(lock, log = { logs.add(it) }) { }
        assertEquals(1, logs.size)
        assertTrue(logs.first().contains("released"))
        assertFalse(lock.isHeld())
    }

    @Test
    fun run_with_null_value_returns_null_and_releases() = runBlocking {
        val lock = FakeWakeLock()
        val value: String? = WakeLockScope.run(lock) { null }
        assertNull(value)
        assertFalse(lock.isHeld())
    }

    // ---------- runHolding：锁已提前同步拿到（Receiver 用） ----------

    @Test
    fun runHolding_releases_without_acquiring_again() = runBlocking {
        val lock = FakeWakeLock()
        lock.acquire(WakeLockPolicy.RECEIVER_TIMEOUT_MS) // 模拟 onReceive 里的同步 acquire
        val value = WakeLockScope.runHolding(lock) { 7 }
        assertEquals(7, value)
        assertEquals(1, lock.acquireCalls)                 // 没有重复 acquire
        assertEquals(listOf("acquire", "release"), lock.events)
        assertFalse(lock.isHeld())
    }

    @Test
    fun runHolding_releases_when_block_throws() = runBlocking {
        val lock = FakeWakeLock()
        lock.acquire(WakeLockPolicy.RECEIVER_TIMEOUT_MS)
        var thrown: Throwable? = null
        try {
            WakeLockScope.runHolding(lock) { error("db boom") }
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("db boom", thrown?.message)
        assertFalse(lock.isHeld())
    }

    @Test
    fun runHolding_is_safe_when_lock_was_never_acquired() = runBlocking {
        val lock = FakeWakeLock()
        WakeLockScope.runHolding(lock) { } // release 幂等：未持锁时 no-op
        assertFalse(lock.isHeld())
        assertTrue(lock.events.isEmpty())
    }

    @Test
    fun runHolding_logs_on_release() = runBlocking {
        val lock = FakeWakeLock()
        val logs = mutableListOf<String>()
        lock.acquire(WakeLockPolicy.RECEIVER_TIMEOUT_MS)
        WakeLockScope.runHolding(lock, log = { logs.add(it) }) { }
        assertEquals(1, logs.size)
        assertTrue(logs.first().contains("released"))
    }
}