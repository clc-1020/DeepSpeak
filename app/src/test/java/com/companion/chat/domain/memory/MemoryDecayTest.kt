package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/** MemoryDecay 纯计算单测。任务 2.5 */
class MemoryDecayTest {

    @Test
    fun increment_zero_days() {
        assertEquals(0.0, MemoryDecay.increment(0), 1e-9)
        assertEquals(0.0, MemoryDecay.increment(-2), 1e-9)
    }

    @Test
    fun increment_by_days() {
        assertEquals(0.3, MemoryDecay.increment(3), 1e-9)
    }

    @Test
    fun next_fade_score() {
        assertEquals(1.3, MemoryDecay.nextFadeScore(1.0, 3), 1e-9)
    }

    @Test
    fun days_between() {
        assertEquals(3, MemoryDecay.daysBetween(10L, 13L))
        assertEquals(0, MemoryDecay.daysBetween(13L, 10L))
    }
}
