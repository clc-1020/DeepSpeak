package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.ConversationDao
import com.companion.chat.data.local.dao.MessageDao
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.domain.proactive.ConversationWriter

/**
 * 把主动消息写入对话历史（Room 实现）。任务 3.5
 *
 * 写进「最近活跃的会话」；若一个会话都没有则新建一个（保证点通知能看到这条消息）。
 * 消息只在此处写一次（配合队列 SENT 认领，天然不重复）。
 */
class RoomConversationWriter(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ConversationWriter {

    override suspend fun appendAssistant(text: String, atMillis: Long): Long? {
        val convId = ensureConversation(atMillis) ?: return null
        messageDao.insert(
            MessageEntity(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = text,
                timestamp = atMillis
            )
        )
        conversationDao.updateLastMessageTime(convId, atMillis)
        return convId
    }

    private suspend fun ensureConversation(atMillis: Long): Long? {
        conversationDao.getAll().firstOrNull()?.let { return it.id }
        val id = conversationDao.insert(
            ConversationEntity(title = DEFAULT_TITLE, createTime = atMillis, lastMessageTime = atMillis)
        )
        return id
    }

    private companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}