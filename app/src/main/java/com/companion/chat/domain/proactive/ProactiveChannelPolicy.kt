package com.companion.chat.domain.proactive

/**
 * 通知渠道选择策略（1.13.3，纯逻辑，可 JVM 单测）。
 *
 * **指令提醒**（用户自己设的定时提醒，[ProactiveMessageCategory.INSTRUCTION]）必须走**独立**的
 * 高优先级「定时提醒」渠道，而不是与闲聊主动消息共用 `proactive_message`。
 *
 * 为什么：MIUI/HyperOS 上从通知栏「关闭」只关**当前渠道**（importance→NONE）。若两者共用同一渠道，
 * 用户为屏蔽闲聊主动消息而关掉该渠道时，**连自己设的定时提醒也一起不弹**——这就是 1.13.3 修的
 * 「定时任务没有弹窗通知」的根因。拆开后，静音闲聊不影响提醒。
 */
object ProactiveChannelPolicy {

    /** 该类别是否应使用独立「定时提醒」渠道（而非闲聊的主动消息渠道）。 */
    fun usesReminderChannel(category: ProactiveMessageCategory): Boolean =
        category == ProactiveMessageCategory.INSTRUCTION
}
