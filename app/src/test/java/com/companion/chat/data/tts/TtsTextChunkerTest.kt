package com.companion.chat.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 官方 TTS 计费字符分片单测（JVM）。任务 4.1 */
class TtsTextChunkerTest {

    @Test
    fun billable_units() {
        assertEquals(2, TtsTextChunker.billableOf('中'))
        assertEquals(1, TtsTextChunker.billableOf('a'))
        assertEquals(1, TtsTextChunker.billableOf(' '))
    }

    @Test
    fun chinese_split_by_billable() {
        val text = "中".repeat(6000) // 6000 汉字 = 12000 计费单位
        val chunks = TtsTextChunker.split(text) // 默认 9000
        assertEquals(2, chunks.size)
        assertEquals(4500, chunks[0].length) // 4500*2 = 9000
        assertEquals(1500, chunks[1].length)
        assertTrue(chunks.all { c -> c.fold(0) { acc, ch -> acc + TtsTextChunker.billableOf(ch) } <= 9000 })
    }

    @Test
    fun ascii_split_by_chars() {
        val text = "a".repeat(20000)
        val chunks = TtsTextChunker.split(text)
        assertEquals(3, chunks.size)
        assertEquals(9000, chunks[0].length)
        assertEquals(9000, chunks[1].length)
        assertEquals(2000, chunks[2].length)
    }

    @Test
    fun empty_returns_empty() {
        assertTrue(TtsTextChunker.split("").isEmpty())
    }
}
