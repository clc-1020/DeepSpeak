package com.companion.chat.data.remote

import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.data.remote.dto.ContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MultimodalRequestBuilder 单元测试（JVM）。任务 1.6 */
class MultimodalRequestBuilderTest {

    @Test
    fun text_only_passes_through() {
        val msgs = listOf(ChatMessageDto(MessageRole.USER, "你好"))
        val out = MultimodalRequestBuilder.build(msgs, emptyList())
        assertEquals(1, out.size)
        assertEquals("你好", out[0].content as String)
    }

    @Test
    fun image_attached_to_last_user_message() {
        val msgs = listOf(
            ChatMessageDto(MessageRole.USER, "旧消息"),
            ChatMessageDto(MessageRole.ASSISTANT, "嗯"),
            ChatMessageDto(MessageRole.USER, "看看这个")
        )
        val out = MultimodalRequestBuilder.build(
            msgs,
            listOf(ImageAttachment("data:image/png;base64,AAAA"))
        )
        assertEquals(3, out.size)
        assertTrue(out[0].content is String) // 旧消息仍是文本
        @Suppress("UNCHECKED_CAST")
        val parts = out[2].content as List<ContentPart>
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].type)
        assertEquals("看看这个", parts[0].text)
        assertEquals("image_url", parts[1].type)
        assertTrue(parts[1].imageUrl!!.url.startsWith("data:image/png"))
    }

    @Test
    fun image_only_empty_text_has_no_text_part() {
        val msgs = listOf(ChatMessageDto(MessageRole.USER, ""))
        val out = MultimodalRequestBuilder.build(
            msgs,
            listOf(ImageAttachment("data:image/jpeg;base64,BBBB"))
        )
        @Suppress("UNCHECKED_CAST")
        val parts = out[0].content as List<ContentPart>
        assertEquals(1, parts.size)
        assertEquals("image_url", parts[0].type)
    }
}
