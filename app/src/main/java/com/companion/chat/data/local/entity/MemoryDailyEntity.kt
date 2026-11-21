package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每日日志：按日期记录事件流水。
 * - date：yyyy-MM-dd（本地时区）。
 * - events：事件列表的 JSON 字符串（List<String> 序列化），保持轻量、避免额外表。
 * 任务 1.1
 */
@Entity(
    tableName = "memory_daily",
    indices = [Index(value = ["date"], unique = true)]
)
data class MemoryDailyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 日期字符串 yyyy-MM-dd */
    val date: String,
    /** 事件列表 JSON，如 ["去了公司","面试通过"] */
    val events: String
)
