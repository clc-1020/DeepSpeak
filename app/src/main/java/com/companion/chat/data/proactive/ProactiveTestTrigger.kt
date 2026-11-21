package com.companion.chat.data.proactive

import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import java.time.Instant
import java.time.ZoneId

/**
 * 测试模式触发（任务 3.6）：手动跑完整链路一次。
 *
 * 召回记忆钩子（无则按当前时间兜底）→ 直接入队 → 强制送达（绕过冷却/上限/勿扰等所有闸门）：
 * 生成 → 入库 → 通知 →（语音策略允许）TTS。用于设置页「立即触发一次主动消息」按钮。
 *
 * [deliverById] 注入送达动作（生产用 `ProactiveDeliveryRunner.deliverById(id, force=true)`），
 * 便于单测断言「按本次入队的 id 强制送达」。
 *
 * @return 是否成功送达。
 */
class ProactiveTestTrigger(
    private val queue: PendingMessageQueue,
    private val deliverById: suspend (Long) -> ProactiveDeliveryOutcome,
    private val recallHook: suspend () -> Long?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    suspend fun triggerNow(): Boolean {
        val hookId = runCatching { recallHook() }.getOrNull()
        val content = if (hookId != null) {
            PendingMessageContent.Memory(hookId)
        } else {
            PendingMessageContent.PlainText(timeHookText())
        }
        val id = queue.enqueue(PendingMessageType.CHITCHAT, content, now())
        return deliverById(id) == ProactiveDeliveryOutcome.DELIVERED
    }

    /** 无记忆钩子时，用「当前时间」给模型一个可自然搭话的线索。 */
    private fun timeHookText(): String {
        val t = Instant.ofEpochMilli(now()).atZone(zone)
        val min = if (t.minute == 0) "" else "${t.minute}分"
        return "现在是${t.monthValue}月${t.dayOfMonth}日 ${t.hour}点${min}，随口问问对方此刻在忙什么"
    }
}