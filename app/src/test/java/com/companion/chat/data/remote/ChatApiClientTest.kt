package com.companion.chat.data.remote

import com.companion.chat.data.remote.dto.ChatMessageDto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatApiClient 错误路径测试（JVM）：断网/HTTP 错误应走 Failed，不崩溃。任务 1.2。
 */
class ChatApiClientTest {

    private val messages = listOf(ChatMessageDto("user", "你好"))

    @Test
    fun blank_api_key_emits_failed() = runBlocking {
        val cfg = ApiConfig(
            providerId = "custom",
            baseUrl = "https://example.com/v1",
            apiKey = "",
            model = "m"
        )
        val events = ChatApiClient().streamChat(cfg, messages).toList()
        assertTrue(events.first() is StreamEvent.Failed)
    }

    @Test
    fun http_error_emits_failed() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
            server.start()
            val cfg = ApiConfig(
                providerId = "custom",
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                model = "m"
            )
            val events = ChatApiClient().streamChat(cfg, messages).toList()
            assertTrue(events.last() is StreamEvent.Failed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun json_title_thinking_serialized_in_request_body() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n")
            )
            server.start()
            val cfg = ApiConfig(
                providerId = "deepseek",
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                model = "deepseek-flash"
            )
            val opts = ChatOptions(
                jsonMode = true,
                titleEnabled = true,
                thinkingEnabled = true,
                reasoningEffort = "high"
            )
            ChatApiClient().streamChat(cfg, messages, opts = opts).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
            assertTrue(body.contains("\"title\":{\"enabled\":true}"))
            assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
            assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun thinking_disabled_by_default_for_deepseek() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n")
            )
            server.start()
            val cfg = ApiConfig(
                providerId = "deepseek",
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                model = "deepseek-flash"
            )
            ChatApiClient().streamChat(cfg, messages).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
            assertTrue(!body.contains("response_format"))
            assertTrue(!body.contains("\"title\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun thinking_omitted_for_non_deepseek_provider() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n")
            )
            server.start()
            val cfg = ApiConfig(
                providerId = "openai",
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                model = "gpt-4o"
            )
            ChatApiClient().streamChat(cfg, messages).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(!body.contains("\"thinking\""))
        } finally {
            server.shutdown()
        }
    }
}
