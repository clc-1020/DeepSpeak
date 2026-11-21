package com.companion.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 提醒任务类型 */
object ReminderType {
    const val INSTRUCTION = "instruction" // 用户明确指令的一次性提醒（如 7 点叫我起床）
    const val FOLLOWUP = "followup"       // 追问型任务（第二版）
    const val FESTIVAL = "festival"       // 特殊日期/节日（第二版）
    const val ENV = "env"                 // 环境关心（第二版）
    const val CHAT = "chat"               // 普通闲聊兜底
}

/** 提醒任务状态 */
object ReminderState {
    const val PENDING = "pending"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val ABANDONED = "abandoned" // 追问被放弃（V2.2.1）
}

/**
 * 提醒任务表：来自用户指令的定时提醒（第一版只用到 INSTRUCTION 类型）。
 * 追问链相关字段（followupInterval/stopCondition）先预留，第二版用。
 * 任务 1.1
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** ReminderType 之一 */
    val type: String,
    /** 任务描述，如「吃早餐」 */
    val taskDesc: String,
    /** 触发时间（epoch millis），来自绝对时间换算 */
    val triggerTime: Long,
    /** ReminderState 之一 */
    val state: String = ReminderState.PENDING,
    /** 追问间隔（分钟），第二版用；普通提醒为 null */
    val followupInterval: Int? = null,
    /** 停止条件（语义描述），第二版用 */
    val stopCondition: String? = null,
    /** 优先级：指令提醒(5) > 追问(4) > 节日(3) > 环境(2) > 闲聊(1) */
    val priority: Int = 5,
    /** 已发出的追问次数；在「FOLLOWUP 闹钟到点准备投队列」时 +1（V2.2.1） */
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    /** 追问上限，默认 5（V2.2.1） */
    @ColumnInfo(defaultValue = "5") val maxAttempts: Int = 5
)
