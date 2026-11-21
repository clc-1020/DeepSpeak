package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待发消息类型 + 优先级（单一事实来源）。任务 3.1
 *
 * 优先级：指令提醒(5) > 追问(4) > 特殊日期(3) > 环境(2) > 闲聊(1)。
 * 队列里 priority 列冗余存 int，便于 SQL `ORDER BY priority DESC` 直接排序。
 */
enum class PendingMessageType(val code: String, val priority: Int, val label: String) {
    INSTRUCTION("instruction", 5, "指令提醒"),
    FOLLOW_UP("followup", 4, "追问"),
    SPECIAL_DATE("special_date", 3, "特殊日期"),
    ENVIRONMENT("environment", 2, "环境"),
    /**
     * 熬夜提醒（V2.1.4 新增）。优先级与环境相同（2）。
     *
     * **必须是独立类型**，不能在 [ENVIRONMENT] 的 content 里加标记：闸门只能看到 `type` 的
     * code，而 `content` 存的是生成后的中文正文，`content.contains("LateNight")` 永远匹配不上，
     * 于是「熬夜提醒要豁免勿扰」这条规则就无法实现。
     */
    LATE_NIGHT("late_night", 2, "熬夜提醒"),
    CHITCHAT("chitchat", 1, "闲聊");

    companion object {
        /** code → 类型；未知返回 null。 */
        fun fromCode(code: String): PendingMessageType? = values().firstOrNull { it.code == code }

        /** code → 优先级；未知按闲聊(1) 兜底，永不返回 null。 */
        fun priorityOf(code: String): Int = fromCode(code)?.priority ?: CHITCHAT.priority
    }
}

/** 待发消息状态。任务 3.1 */
object PendingMessageState {
    const val PENDING = "pending"     // 待发（在队列里等被取）
    const val SENT = "sent"           // 已被取走并发送
    const val DISCARDED = "discarded" // 被决策闸门丢弃（如冷却/上限/无钩子）
    const val CANCELLED = "cancelled" // 主动取消（如用户删了提醒）
}

/**
 * 统一待发队列。任务 3.1
 *
 * 所有触发源（指令提醒 / 追问 / 节日 / 环境 / 闲聊）先把消息投到这里，**不直接发**；
 * 发送层每次只从队列取「到点且优先级最高」的一条（同级按 triggerTime 早、再按 id 早）。
 * `priority` 由 [PendingMessageType] 推导后冗余存储，方便 SQL 排序。
 */
@Entity(
    tableName = "pending_messages",
    indices = [Index("state"), Index("priority"), Index("triggerTime")]
)
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** [PendingMessageType.code] 之一 */
    val type: String,
    /** 1..5，由 type 推导（冗余存，便于排序） */
    val priority: Int,
    /** 待发内容/钩子描述；生成前可能是触发源给的原始线索 */
    val content: String,
    /** 计划触发时间（epoch millis）；到点才出队 */
    val triggerTime: Long,
    /** [PendingMessageState] 之一 */
    val state: String = PendingMessageState.PENDING,
    /** 入队时间（epoch millis），同优先级同触发时间时作稳定排序兜底；默认取构造时刻，防漏传存 0 */
    val createdAt: Long = System.currentTimeMillis()
)
