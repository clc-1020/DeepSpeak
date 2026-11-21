package com.companion.chat.data.remote

import com.companion.chat.data.remote.dto.ChatStreamChunk
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 流式解析与 usage 计算单元测试（JVM，无需设备）。任务 1.2 验收。
 */
class ChatStreamParsingTest {

    private val gson = Gson()

    @Test
    fun parse_delta_chunk() {
        val json = """{"id":"1","choices":[{"delta":{"content":"你好"},"finish_reason":null}],"usage":null}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        assertEquals("你好", chunk.choices[0].delta?.content)
    }

    @Test
    fun parse_usage_chunk_deepseek() {
        val json = """{"id":"1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40}}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        val usage = UsageInfo.from(chunk.usage, 65536, 1000L)
        assertNotNull(usage)
        usage!!
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(0.6, usage.cacheHitRatio!!, 0.0001)       // 60/100
        assertEquals(50.0, usage.tokPerSec!!, 0.0001)          // 50 tokens / 1s
        assertEquals(150.0 / 65536.0, usage.contextUsage!!, 1e-9)
    }

    @Test
    fun parse_usage_openai_cached() {
        val json = """{"choices":[],"usage":{"prompt_tokens":200,"completion_tokens":20,"total_tokens":220,"prompt_tokens_details":{"cached_tokens":50}}}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        val usage = UsageInfo.from(chunk.usage, 128000, 2000L)!!
        assertEquals(50, usage.cachedTokens)
        assertEquals(0.25, usage.cacheHitRatio!!, 0.0001)      // 50/200
        assertEquals(10.0, usage.tokPerSec!!, 0.0001)          // 20 tokens / 2s
    }

    @Test
    fun parse_delta_title() {
        val json = """{"id":"1","choices":[{"delta":{"title":"关于面试"},"finish_reason":null}]}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        assertEquals("关于面试", chunk.choices[0].delta?.title)
    }

    @Test
    fun absent_title_is_null() {
        val json = """{"id":"1","choices":[{"delta":{"content":"你好"},"finish_reason":null}]}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        assertEquals(null, chunk.choices[0].delta?.title)
        assertEquals("你好", chunk.choices[0].delta?.content)
    }

    @Test
    fun parse_reasoning_content() {
        val json = """{"id":"1","choices":[{"delta":{"reasoning_content":"想一想","content":"答"}}]}"""
        val chunk = gson.fromJson(json, ChatStreamChunk::class.java)
        assertEquals("想一想", chunk.choices[0].delta?.reasoningContent)
        assertEquals("答", chunk.choices[0].delta?.content)
    }

    @Test
    fun null_usage_returns_null() {
        val usage = UsageInfo.from(null, 65536, 1000L)
        assertEquals(null, usage)
    }
}
