package com.companion.chat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MemoryTopicSelector.parseChoice 解析单测（纯函数）。任务 2.6 */
class MemoryTopicSelectorTest {

    @Test
    fun parse_id() {
        assertEquals(2, MemoryTopicSelector.parseChoice("""{"id":2}"""))
    }

    @Test
    fun parse_index_fallback() {
        assertEquals(3, MemoryTopicSelector.parseChoice("""{"index":3}"""))
    }

    @Test
    fun parse_zero_means_none() {
        assertEquals(0, MemoryTopicSelector.parseChoice("""{"id":0}"""))
    }

    @Test
    fun suitable_false_means_none() {
        assertEquals(0, MemoryTopicSelector.parseChoice("""{"suitable":false}"""))
    }

    @Test
    fun suitable_true_with_id() {
        assertEquals(2, MemoryTopicSelector.parseChoice("""{"suitable":true,"id":2}"""))
    }

    @Test
    fun parse_string_number() {
        assertEquals(2, MemoryTopicSelector.parseChoice("""{"id":"2"}"""))
    }

    @Test
    fun invalid_returns_null() {
        assertNull(MemoryTopicSelector.parseChoice("not json"))
        assertNull(MemoryTopicSelector.parseChoice(null))
        assertNull(MemoryTopicSelector.parseChoice("[1,2]"))
        assertNull(MemoryTopicSelector.parseChoice("""{"foo":1}"""))
    }
}
