package com.companion.chat.data.remote

import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.dto.ApiMessageDto
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.data.remote.dto.ContentPart
import com.companion.chat.data.remote.dto.ImageUrl

/**
 * 把「文本历史 + 当前图片」组装成 API 消息数组。任务 1.6
 *
 * 规则（与 DeepSeek 文档一致：assistant 消息不能带图）：
 * - 无图片 → 全部按纯文本发送。
 * - 有图片 → 仅在「最后一条 user 消息」上挂图片，content 变为
 *   [{type:text,...}, {type:image_url,image_url:{url:dataURL}}, ...]。
 * - 历史图片不回传（省 token，也避免 assistant 带图报 400）。
 */
object MultimodalRequestBuilder {

    fun build(messages: List<ChatMessageDto>, images: List<ImageAttachment>): List<ApiMessageDto> {
        val out = ArrayList<ApiMessageDto>(messages.size)
        for ((i, m) in messages.withIndex()) {
            val isLast = i == messages.lastIndex
            if (isLast && images.isNotEmpty() && m.role == MessageRole.USER) {
                val parts = ArrayList<ContentPart>()
                if (m.content.isNotBlank()) {
                    parts.add(ContentPart(type = "text", text = m.content))
                }
                images.forEach {
                    parts.add(ContentPart(type = "image_url", imageUrl = ImageUrl(it.dataUrl)))
                }
                out.add(ApiMessageDto(m.role, parts))
            } else {
                out.add(ApiMessageDto(m.role, m.content))
            }
        }
        return out
    }
}
