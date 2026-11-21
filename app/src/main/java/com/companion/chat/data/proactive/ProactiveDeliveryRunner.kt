package com.companion.chat.data.proactive

import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.ConversationWriter
import com.companion.chat.domain.proactive.EmotionNotificationTitle
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveDeliveryOutcome
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import com.companion.chat.domain.proactive.ProactiveMessageGenerator
import com.companion.chat.domain.proactive.ProactiveNotification
import com.companion.chat.domain.proactive.ProactiveNotifier
import com.companion.chat.domain.proactive.ProactiveSpeaker
import com.companion.chat.domain.proactive.ProactiveVoicePolicy
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId

/**
 * 生成与送达编排（可 JVM 单测，与 Android 解耦）。任务 3.5 / 3.6
 *
 * 一轮：取队列最高优先级到点消息 →（自发再跑一次 3.4 闸门）→ 生成文本 →
 * 认领(SENT) → 写入对话历史 → 通知 →（语音策略允许则）TTS → 计数 +1。
 *
 * - 生成失败不认领（保留 PENDING 等下轮重试）。
 * - 指令提醒(INSTRUCTION) 跳过闸门（到点必发）。
 * - **勿扰顺延（任务 7.2）**：`dndBlocksDelivery()` 为真时（勿扰 + 区间内不发通知），
 *   把队列里这条（含指令提醒）的 triggerTime 推到区间结束并安排一次延迟送达，
 *   返回 [ProactiveDeliveryOutcome.DND_DEFERRED]；不丢消息、不念语音。
 * - 测试模式（3.6）：[deliverById] + [force]=true 绕过所有闸门，精确送达指定消息。
 * - 「语音开关」尚不存在，[voicePolicy] 默认 false，待 4.3/7.2 接真实开关 + 勿扰。
 */
class ProactiveDeliveryRunner(
    private val queue: PendingMessageQueue,
    private val gate: ProactiveGate,
    private val generator: ProactiveMessageGenerator,
    private val writer: ConversationWriter,
    private val notifier: ProactiveNotifier,
    private val speaker: ProactiveSpeaker,
    private val voicePolicy: ProactiveVoicePolicy = ProactiveVoicePolicy { false },
    private val onDelivered: suspend () -> Unit = {},

    /**
     * 送达成功后的**逐条回调**（V2.1.4 新增）。
     *
     * 与 [onDelivered] 分开是有意的：`onDelivered` 是「今日已发自发计数」的记账口，
     * 计数在测试模式（`force=true`）下**不能**累加（否则连点几次测试就把当日额度吃满、
     * 之后每 2 小时的检查全被 DAILY_LIMIT 拦死）；而环境/节日**同日去重**必须在送达后
     * 无条件记账——否则下一轮检查会把同一个钩子再发一遍，用户实际收到两条。
     * 两者的「要不要在 force 时跳过」完全相反，所以不共用一个回调。
     */
    private val onDeliveredMessage: suspend (PendingMessageEntity) -> Unit = {},
    /** 任务 7.2：当前是否「勿扰 + 区间内不发 AI 消息」→ 送达层把消息顺延到区间结束。 */
    private val dndBlocksDelivery: () -> Boolean = { false },
    /**
     * BUG-1：当前是否「勿扰 + 区间内连用户提醒也压掉」。
     * 默认 `{ false }` = 指令提醒永远准时送达；开关见 [com.companion.chat.domain.settings.DndSettings.suppressInstruction]。
     */
    private val dndBlocksInstruction: () -> Boolean = { false },
    /** 任务 7.2：勿扰结束时刻（null=不在区间内）。 */
    private val dndEndMillisProvider: () -> Long? = { null },
    /** 任务 7.2：顺延后到点触发一次送达（生产接 WorkManager 延迟任务）。 */
    private val scheduleDeferredDelivery: suspend (delayMs: Long) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val notificationTitle: String = "陪你聊聊",
    /**
     * P2（2026-11-21）：按她的情绪换通知标题。返回空 map = 用 [notificationTitle] 默认值。
     * 放在送达层（suspend 上下文）而不是 notifier（Android 类、拿不到状态）里，才好测。
     */
    private val emotionTitleProvider: suspend () -> Map<String, Double> = { emptyMap() },
    private val log: (String) -> Unit = { android.util.Log.w("ProactiveDelivery", it) }
) {

    /** 送达队列里当前最高优先级到点的一条。[force]=true 时绕过闸门（测试模式）。 */
    suspend fun deliverNext(force: Boolean = false): ProactiveDeliveryOutcome {
        val msg = queue.peekNext() ?: return ProactiveDeliveryOutcome.EMPTY
        return deliver(msg, force)
    }

    /** 按 id 送达指定消息（测试模式精确送达本次入队的一条）。 */
    suspend fun deliverById(id: Long, force: Boolean = false): ProactiveDeliveryOutcome {
        val msg = queue.get(id) ?: return ProactiveDeliveryOutcome.EMPTY
        if (msg.state != PendingMessageState.PENDING) return ProactiveDeliveryOutcome.EMPTY
        return deliver(msg, force)
    }

    private suspend fun deliver(msg: PendingMessageEntity, force: Boolean): ProactiveDeliveryOutcome {
        // 出队时再跑一次闸门（状态可能已变，如刚聊过进入冷却）；测试模式跳过。
        // **按消息类型决定类别**（BUG-2）：环境/节日/追问只受「总开关 + 勿扰」，
        // 不再被当成闲聊吃冷却与每日上限。
        val category = ProactiveMessageCategory.ofPendingType(msg.type)
        // 指令提醒/追问「到点必发」：不进闸门。它们在勿扰区间里由下面的 DND_DEFERRED 分支
        // 顺延到区间结束，**不能**在这里被判丢弃（否则设了勿扰就丢提醒）。
        val gateExempt = category == ProactiveMessageCategory.INSTRUCTION ||
            category == ProactiveMessageCategory.FOLLOWUP
        if (!gateExempt && !force && !gate.allow(category)) {
            // 被拦时如何处理：闲聊/追问/指令=丢弃（过期无意义）；
            // 节日/环境=必须保留，顺延后再送（否则当天就永久丢了）
            if (deferInsteadOfDiscard(msg, category)) {
                return ProactiveDeliveryOutcome.BLOCKED
            }
            queue.discard(msg.id)
            log("闸门拦截(${category})，丢弃 id=${msg.id}")
            return ProactiveDeliveryOutcome.BLOCKED
        }

        // 任务 7.2 + BUG-1：勿扰期间的处置**按类别分开**
        //  - 指令提醒（用户自己设的闹钟）：默认**照发**，只有明确打开「压掉我的提醒」才顺延。
        //    闹钟的本质是准时，被勿扰吞掉等于核心功能失效。
        //  - 其余（自发/追问/节日/环境）：按「区间内不发 AI 消息」顺延到区间结束。
        val dndBlocked = when (category) {
            ProactiveMessageCategory.INSTRUCTION -> dndBlocksInstruction()
            // 熬夜提醒豁免勿扰（计划书 §3.7「送达层只豁免 LATE_NIGHT」）：
            // 它本来就该在 23 点后发，按区间顺延等于第二天早上再说「该睡了」，语义全错。
            // ⚠️ 不要顺手把 INSTRUCTION / FOLLOWUP 也放进来：它们在勿扰期是「顺延到区间结束」，
            //    硬发等于把用户的勿扰设置悄悄废掉。
            ProactiveMessageCategory.LATE_NIGHT_ALERT -> false
            else -> dndBlocksDelivery()
        }
        if (!force && dndBlocked) {
            val end = dndEndMillisProvider()
            val delay = if (end != null) end - now() else 0L
            if (end != null && delay > 0L) {
                // V2.1.7：节日祝福只在当天有效。勿扰结束后已跨到次日 → 丢弃；
                // 下轮检查会用「明天」的 dateKey 重新召回，不会漏掉明天的节日。
                // 只动勿扰分支，deferInsteadOfDiscard（闸门拦截，目标是当天末）一行不改。
                if (category == ProactiveMessageCategory.SPECIAL_DATE &&
                    !isSameLocalDay(now(), end)
                ) {
                    queue.discard(msg.id)
                    log("节日勿扰顺延跨日，丢弃 id=${msg.id}")
                    return ProactiveDeliveryOutcome.FESTIVAL_EXPIRED
                }
                queue.reschedule(msg.id, end)
                scheduleDeferredDelivery(delay)
                log("勿扰区间(${category})，顺延 id=${msg.id} 到 +${delay}ms")
                return ProactiveDeliveryOutcome.DND_DEFERRED
            }
        }

        // V2.1.7 防御（计划书 L725-728）：decode 兜底成 PlainText 却仍以已知 tag 开头，
        // 说明是尚未认识的 tag 或空 payload。不能把 "fest:春节" 当正文喂给模型/发给用户。
        // 不改 decode 的 else 兜底契约（memoryIdOrNull / 送达层 as? 都依赖它）。
        if (isUnknownTagContent(msg.content)) {
            queue.discard(msg.id)
            log("未知 tag 内容，丢弃 id=${msg.id} content=${msg.content}")
            return ProactiveDeliveryOutcome.BLOCKED
        }

        val text = generator.generate(msg)
        if (text.isNullOrBlank()) {
            log("生成失败，保留待发 id=${msg.id}")
            return ProactiveDeliveryOutcome.GENERATION_FAILED
        }

        // 认领（PENDING→SENT）；抢不到说明已被处理
        if (!queue.claim(msg.id)) return ProactiveDeliveryOutcome.EMPTY
        val convId = writer.appendAssistant(text, now())
        val notificationId = ((msg.id % Int.MAX_VALUE).toInt()).let { if (it == 0) 1 else it }
        // P2：按她的情绪换通知标题（空/异常 → 回落到 [notificationTitle] 默认值）
        val emotionForTitle = runCatching { emotionTitleProvider() }.getOrDefault(emptyMap())
        val title = EmotionNotificationTitle.forEmotions(emotionForTitle) ?: notificationTitle
        notifier.show(
            ProactiveNotification(
                notificationId = notificationId,
                title = title,
                body = text,
                conversationId = convId,
                // 1.9.4.0：长期记忆兜底也挂"别提"按钮（回执走 EmotionalMemoryService.dismissById）；
                // 短期 Instruction 的既有按钮口径不变。
                completeMemoryId = PendingMessageContent.completeMemoryIdOrNull(msg.content)
                    ?: PendingMessageContent.longMemoryIdOrNull(msg.content),
                // V2.2.5：追问消息把 reminderId 带到通知上供按钮回执使用。
                // 用现成的 followupOrNull（= decode(...) as? Followup），非追问恒 null。
                followupReminderId = PendingMessageContent.followupOrNull(msg.content)?.reminderId
            )
        )
        // **只有真实投递才计入「今日已发」计数**（任务 7.1 上限依据）。
        // 测试模式（force=true，设置页「立即触发一次」）不是自发消息，之前无条件 +1，
        // 实测里连着点几次测试就把当日额度吃满（count=11 > limit=3），
        // 之后每 2 小时的定时检查全部被 DAILY_LIMIT 拦死，表现为「主动消息再也不发」。
        //
        // 审查 R1（V2.2.4）：指令/追问是**用户自己建的**，同样不该消耗「自发」额度——
        // 契约见 [com.companion.chat.domain.proactive.ProactiveGateContext.spontaneousSentToday]
        // 「指令/追问/手动测试不计」。一条追问链跑满 5 条就吃掉 5/8，用户当天再设两条提醒后，
        // 当天剩余的自发消息全被 DAILY_LIMIT 拦死（与上面那段手动测试事故同构）。
        //
        // 刻意**只**排掉 INSTRUCTION / FOLLOWUP：环境/节日/熬夜仍计数，因为闸门第 2.5 步
        // 也拿这个计数卡它们（SPECIAL_DATE / ENVIRONMENT / LATE_NIGHT 受每日上限约束），
        // 一并排除等于把它们的每日上限关掉。
        val countsAsSpontaneous = category != ProactiveMessageCategory.INSTRUCTION &&
            category != ProactiveMessageCategory.FOLLOWUP
        if (!force && countsAsSpontaneous) onDelivered()
        // 逐条回调：环境/节日同日去重记账（V2.1.4）。**force 也要走**——
        // 测试模式送达后若不计账，下一轮整点检查会把同一钩子再发一遍。
        onDeliveredMessage(msg)
        if (voicePolicy.shouldSpeak(now())) {
            // 语音：持 WakeLock 念，5 分钟到即结束（超时保护，见任务 4.3）
            runCatching { withTimeout(SPEAK_TIMEOUT_MS) { speaker.speak(text) } }
        }
        log("已送达 id=${msg.id} conv=$convId force=$force")
        return ProactiveDeliveryOutcome.DELIVERED
    }

    /**
     * 被闸门拦下时，是否「保留并顺延」而不是直接丢弃（BUG-2）。
     *
     * 只有 [ProactiveMessageCategory.SPECIAL_DATE] 和 [ProactiveMessageCategory.ENVIRONMENT]
     * 值得保留：节日祝福当天必须送到，环境钩子过一会儿还有意义；闲聊/追问过期就是噪音。
     *
     * @return true=已顺延并排好延迟送达；false=调用方应 discard
     */
    private suspend fun deferInsteadOfDiscard(
        msg: PendingMessageEntity,
        category: ProactiveMessageCategory
    ): Boolean {
        val nowMs = now()
        val target = when (category) {
            ProactiveMessageCategory.SPECIAL_DATE -> endOfLocalDay(nowMs)
            ProactiveMessageCategory.ENVIRONMENT -> nowMs + ENVIRONMENT_RETRY_MS
            else -> return false
        }
        val delay = target - nowMs
        if (delay <= 0L) return false // 已经过了窗口，留着也没用
        queue.reschedule(msg.id, target)
        scheduleDeferredDelivery(delay)
        log("闸门拦截(${category})，保留并顺延 id=${msg.id} 到 +${delay}ms")
        return true
    }

    /** 当天 23:59:59.999 的 epoch millis。 */
    private fun endOfLocalDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /** 两个时刻是否落在同一个本地自然日（V2.1.7 节日勿扰跨日判定）。 */
    private fun isSameLocalDay(a: Long, b: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /**
     * decode 兜底成 PlainText 但文本仍以已知 tag 开头 → 不认识的新 tag / 空 payload。
     *
     * 放在 companion 里并标 `internal`：这条判据只有送达层会走到，而送达层全装配太重
     * （生成器/通知/闸门/语音一大串），提成纯函数后能直接单测——否则「KNOWN_TAGS 漏 tag」
     * 这种漏配只能靠真机碰出来（V2.2.4 审查 S1 就是这么漏掉 `followup:` 的）。
     */
    internal companion object {
        /** 与 [PendingMessageContent] 的 tag 集合同步；新增 tag 时这里也要加。 */
        private val KNOWN_TAGS = listOf(
            PendingMessageContent.MEMORY_PREFIX,
            PendingMessageContent.TEXT_PREFIX,
            PendingMessageContent.INSTR_PREFIX,
            PendingMessageContent.ENV_PREFIX,
            PendingMessageContent.FEST_PREFIX,
            PendingMessageContent.FU_PREFIX, // V2.2.4：漏了这个，坏的追问行会被当正文发出去
        )

        internal fun isUnknownTagContent(raw: String): Boolean {
            val decoded = PendingMessageContent.decode(raw)
            if (decoded !is PendingMessageContent.PlainText) return false
            val text = decoded.text
            return KNOWN_TAGS.any { text.startsWith(it) }
        }

        /** 主动消息语音上限 5 分钟，到点即结束并释放 WakeLock（任务 4.3）。 */
        private const val SPEAK_TIMEOUT_MS = 5 * 60_000L

        /** 环境钩子被拦后重试间隔（1 小时）：环境有时效，过太久就没意义了。 */
        private const val ENVIRONMENT_RETRY_MS = 3_600_000L
    }
}