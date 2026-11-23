package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日任务（2026-11-23）。
 *
 * 用户设定「每天某个时间点，让 AI 回答我写好的这段文字」，到点 App 自动新开/沿用该任务的
 * 专属会话、把 [text] 当用户消息发给模型、拿到回复后以通知形式弹出（可选语音）。
 *
 * - 每条任务**固定一个专属会话**（[conversationId]，首次执行时创建并记住）；
 * - 「今天覆盖昨天」：每次执行成功后会先清空该会话旧消息，只留今天这一问一答（省 token，见 DailyTaskRunner）；
 * - [mode] 存 [com.companion.chat.domain.chat.ChatMode.storageValue]：`companion`=陪聊（借人设语气），`normal`=对话（干净问答）。
 */
@Entity(tableName = "daily_tasks")
data class DailyTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 用户写的固定文本（到点把它当用户消息发给 AI） */
    val text: String,
    /** 触发时间：小时 0..23 */
    val hour: Int,
    /** 触发时间：分钟 0..59 */
    val minute: Int,
    /** 模式：ChatMode.storageValue（companion / normal） */
    val mode: String,
    /** 是否用语音念出 AI 的回答 */
    val voiceEnabled: Boolean = false,
    /** 是否启用（关掉则不排闹钟、到点不跑） */
    val enabled: Boolean = true,
    /** 该任务的专属会话 id（首次执行时创建并回填；会话被删则下次重建） */
    val conversationId: Long? = null,
    /** 创建时间（epoch millis） */
    val createdAt: Long
)
