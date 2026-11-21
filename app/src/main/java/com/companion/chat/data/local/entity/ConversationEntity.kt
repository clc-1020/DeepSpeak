package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话表：一次「对话」= 一个会话，包含多条消息。
 * 任务 1.1
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 会话标题（默认取首条用户消息或「新对话」） */
    val title: String,
    /** 创建时间（epoch millis） */
    val createTime: Long,
    /** 最后一条消息时间（epoch millis），用于排序 */
    val lastMessageTime: Long
)
