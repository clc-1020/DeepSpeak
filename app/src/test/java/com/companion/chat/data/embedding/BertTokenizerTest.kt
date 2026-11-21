package com.companion.chat.data.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BertTokenizer（WordPiece，uncased）单测。任务 2.1 */
class BertTokenizerTest {

    private val baseTokens = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
        "hello", "world", "un", "##aff", "##able", ",", "!", "你", "好"
    )

    private fun vocab(): Map<String, Int> = baseTokens.mapIndexed { i, t -> t to i }.toMap()
    private fun id(token: String): Long = vocab()[token]!!.toLong()

    @Test
    fun cls_sep_wrapping() {
        val tk = BertTokenizer(vocab()).tokenize("hello world")
        assertArrayEquals(
            longArrayOf(id("[CLS]"), id("hello"), id("world"), id("[SEP]")),
            tk.inputIds
        )
        assertArrayEquals(LongArray(4) { 1L }, tk.attentionMask)
        assertArrayEquals(LongArray(4) { 0L }, tk.tokenTypeIds)
    }

    @Test
    fun lower_and_punctuation_split() {
        val tk = BertTokenizer(vocab()).tokenize("HELLO, WORLD!")
        // -> hello , world !
        assertArrayEquals(
            longArrayOf(id("[CLS]"), id("hello"), id(","), id("world"), id("!"), id("[SEP]")),
            tk.inputIds
        )
    }

    @Test
    fun wordpiece_greedy_split() {
        val tk = BertTokenizer(vocab()).tokenize("unaffable")
        // un + ##aff + ##able
        assertArrayEquals(
            longArrayOf(id("[CLS]"), id("un"), id("##aff"), id("##able"), id("[SEP]")),
            tk.inputIds
        )
    }

    @Test
    fun unknown_word_maps_to_unk() {
        val tk = BertTokenizer(vocab()).tokenize("zzz")
        assertArrayEquals(
            longArrayOf(id("[CLS]"), id("[UNK]"), id("[SEP]")),
            tk.inputIds
        )
    }

    @Test
    fun cjk_split_per_char() {
        val tk = BertTokenizer(vocab()).tokenize("你好")
        assertArrayEquals(
            longArrayOf(id("[CLS]"), id("你"), id("好"), id("[SEP]")),
            tk.inputIds
        )
    }

    @Test
    fun truncates_to_maxlen() {
        val tok = BertTokenizer(vocab(), maxLen = 5)
        val tk = tok.tokenize("hello world hello world hello")
        assertEquals(5, tk.inputIds.size)
        assertEquals(id("[CLS]"), tk.inputIds.first())
        assertEquals(id("[SEP]"), tk.inputIds.last())
    }

    @Test
    fun blank_text_gives_cls_sep_only() {
        val tk = BertTokenizer(vocab()).tokenize("   ")
        assertArrayEquals(longArrayOf(id("[CLS]"), id("[SEP]")), tk.inputIds)
    }

    @Test
    fun attention_mask_len_matches_and_ones() {
        val tk = BertTokenizer(vocab()).tokenize("hello, world")
        assertEquals(tk.inputIds.size, tk.attentionMask.size)
        assertEquals(tk.inputIds.size, tk.tokenTypeIds.size)
        assertTrue(tk.attentionMask.all { it == 1L })
        assertTrue(tk.tokenTypeIds.all { it == 0L })
    }
}
