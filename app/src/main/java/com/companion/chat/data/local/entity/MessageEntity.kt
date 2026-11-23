package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 消息角色常量 */
object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"

    /** 用户元数据标记（紧挨 user 消息前的 latest_reminder），内容如 2026-11-20,星期五,汕头,App,中文 */
    const val REMINDER = "latest_reminder"
}

/**
 * 消息表。
 * - tokPerSec / cacheHit / contextUsage 对应底部状态栏三项，来自 API 的 usage（可能为空）。
 * - 会话删除时级联删除其消息。
 * 任务 1.1
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    /** MessageRole 之一 */
    val role: String,
    val content: String,
    /** 消息时间（epoch millis） */
    val timestamp: Long,
    /** 生成速度 tokens/秒，仅 assistant 消息、API 返回时才有 */
    val tokPerSec: Double? = null,
    /** 缓存命中率（0.0~1.0），仅 API 返回 usage 时才有 */
    val cacheHit: Double? = null,
    /** 上下文占用（0.0~1.0），仅 API 返回 usage 时才有 */
    val contextUsage: Double? = null,
    /**
     * 与该条 user 消息成对持久化的 latest_reminder（日期/星期/地点/平台/语言）。
     * 仅 USER 消息可能有值；历史回传时紧挨在该 user 前一起带上（缓存友好）。非 user 消息为 null。
     */
    val reminder: String? = null,
    /**
     * 该条 user 消息附带图片的本地文件路径（已拷贝到 App 私有目录），多图用换行 "\n" 拼接。
     * 仅 USER 消息可能有；展示层据此在气泡里显示图片。非 user 或纯文本消息为 null。
     */
    val imagePaths: String? = null,
    /**
     * 联网搜索引用（2026-11-23）：仅 assistant 消息可能有，JSON 数组字符串（见 CitationCodec）。
     * 整条回复的附属品，没有独立生命周期，所以存 JSON 不建表。null = 无引用（老消息）。
     */
    val citations: String? = null
)
