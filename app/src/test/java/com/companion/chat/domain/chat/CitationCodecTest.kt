package com.companion.chat.domain.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 联网引用 Codec / Parser 纯函数测试（2026-11-23）。 */
class CitationCodecTest {

    @Test
    fun encode_then_decode_roundtrip() {
        val list = listOf(
            Citation("https://a.com/x", "标题A", "A站"),
            Citation("https://b.com/y")
        )
        val raw = CitationCodec.encode(list)
        assertTrue(raw != null)
        val back = CitationCodec.decode(raw)
        assertEquals(2, back.size)
        assertEquals("https://a.com/x", back[0].url)
        assertEquals("标题A", back[0].title)
        assertEquals("A站", back[0].siteName)
        assertEquals("https://b.com/y", back[1].url)
    }

    @Test
    fun encode_empty_returns_null() {
        assertNull(CitationCodec.encode(emptyList()))
    }

    @Test
    fun decode_blank_or_bad_or_no_url_returns_empty() {
        assertEquals(0, CitationCodec.decode(null).size)
        assertEquals(0, CitationCodec.decode("").size)
        assertEquals(0, CitationCodec.decode("not json").size)
        // 无 url 的项被丢弃（不允许死链进库）
        assertEquals(0, CitationCodec.decode("[{\"title\":\"x\"}]").size)
    }

    @Test
    fun host_strips_scheme_and_www() {
        assertEquals("example.com", Citation("https://www.example.com/a/b").host)
        assertEquals("example.com", Citation("example.com/a").host)
        assertTrue(Citation("http://sub.example.com.cn/x").host.startsWith("sub.example.com.cn"))
    }

    @Test
    fun display_label_prefers_title_then_site_then_host() {
        assertEquals("标题", Citation("https://a.com", title = "标题").displayLabel)
        assertEquals("站点", Citation("https://a.com", siteName = "站点").displayLabel)
        assertEquals("a.com", Citation("https://a.com").displayLabel)
    }

    @Test
    fun parser_extracts_from_annotations_array() {
        val json = JsonParser.parseString(
            "[{\"url\":\"https://x.com/1\",\"title\":\"T1\",\"site_name\":\"S1\"},{\"title\":\"no-url\"}]"
        )
        val cites = CitationParser.parse(json)
        assertEquals(1, cites.size)
        assertEquals("https://x.com/1", cites[0].url)
        assertEquals("T1", cites[0].title)
        assertEquals("S1", cites[0].siteName)
    }

    @Test
    fun parser_null_or_non_array_returns_empty() {
        assertEquals(0, CitationParser.parse(null).size)
        assertEquals(0, CitationParser.parse(JsonParser.parseString("{\"a\":1}")).size)
    }

    @Test
    fun parser_extracts_from_nested_url_citation() {
        // 真实 Chat Completions 流式结构（2026-11-23 真机实测）：url/title 嵌在 url_citation 里
        val json = JsonParser.parseString(
            "[{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://x.com/1\",\"title\":\"T1\",\"start_index\":0,\"end_index\":10}}," +
                "{\"type\":\"url_citation\",\"url_citation\":{\"title\":\"no-url\"}}]"
        )
        val cites = CitationParser.parse(json)
        assertEquals(1, cites.size)
        assertEquals("https://x.com/1", cites[0].url)
        assertEquals("T1", cites[0].title)
    }

    @Test
    fun parser_handles_mixed_nested_and_flat() {
        val json = JsonParser.parseString(
            "[{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://n.com\",\"title\":\"N\"}}," +
                "{\"type\":\"web_search_result_location\",\"url\":\"https://f.com\",\"title\":\"F\",\"site_name\":\"FS\"}]"
        )
        val cites = CitationParser.parse(json)
        assertEquals(2, cites.size)
        assertEquals("https://n.com", cites[0].url)
        assertEquals("N", cites[0].title)
        assertEquals("https://f.com", cites[1].url)
        assertEquals("FS", cites[1].siteName)
    }
}
