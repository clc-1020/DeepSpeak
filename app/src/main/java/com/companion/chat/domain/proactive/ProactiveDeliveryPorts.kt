package com.companion.chat.domain.proactive

import com.companion.chat.data.local.entity.PendingMessageEntity

/** 一条待推送通知的内容。任务 3.5 */
data class ProactiveNotification(
    val notificationId: Int,
    val title: String,
    val body: String,
    /** 插入对话历史后的会话 id（点通知可定位到该会话）。 */
    val conversationId: Long?,
    /** 指令提醒关联的短期记忆 id：非空时通知带「已完成」按钮，点后删除该记忆。任务 3.7 */
    val completeMemoryId: Long? = null,
    /**
     * 1.9.4.2：长期记忆兜底「关心」话题的 id。非空时通知带「别提这个」按钮，
     * 点后**只给该长期记忆降权/冷却、绝不删除任何短期记忆**——与 [completeMemoryId] 语义彻底分开，
     * 修「长期 id 塞进完成槽位 → 编号撞车误删短期记忆」的 bug。
     */
    val dismissLongMemoryId: Long? = null,
    /**
     * 追问链的提醒 id（V2.2.5，计划书 §3.9）：非空时通知挂「已完成」「再等等」两个按钮。
     * 非追问消息恒为 null —— 不要给它默认值以外的语义。
     */
    val followupReminderId: Long? = null
)

/** 通知展示端口（Android 实现见 AndroidProactiveNotifier）。任务 3.5 */
interface ProactiveNotifier {
    fun show(notification: ProactiveNotification)
}

/** 语音播报端口（Android 实现包装 TtsManager）。任务 3.5 */
interface ProactiveSpeaker {
    suspend fun speak(text: String)
}

/** 是否播报语音的策略（语音总开关 + 勿扰）。默认关闭，待 4.3/7.2 接真实设置。任务 3.5 */
fun interface ProactiveVoicePolicy {
    fun shouldSpeak(nowMillis: Long): Boolean
}

/** 把待发消息生成成一条要发出去的文本；null=生成失败（保留待发以便重试）。任务 3.5 */
interface ProactiveMessageGenerator {
    suspend fun generate(pending: PendingMessageEntity): String?
}

/** 把生成的主动消息写入对话历史；返回会话 id（无则 null）。任务 3.5 */
interface ConversationWriter {
    suspend fun appendAssistant(text: String, atMillis: Long): Long?
}

/** 一轮送达结果。任务 3.5 */
enum class ProactiveDeliveryOutcome {
    EMPTY,             // 队列无可发
    BLOCKED,           // 出队时被闸门拦截（已 discard）
    GENERATION_FAILED, // 生成失败（保持待发待重试）
    DELIVERED,         // 已生成 + 入库 + 通知（+ 语音）
    DND_DEFERRED,      // 勿扰区间内不发通知 → 已顺延到区间结束（任务 7.2，既不算送达也不算丢弃）
    /** V2.1.7：节日祝福的勿扰顺延目标跨到次日 → 已 discard，明天再祝福就是过期内容。 */
    FESTIVAL_EXPIRED
}