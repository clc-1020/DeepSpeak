package com.companion.chat.data.proactive

import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.FestivalDedupe
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import java.time.ZoneId

/** 一轮主动检查的结果（便于日志/测试断言）。任务 3.2 / V2.1.7 */
enum class ProactiveCheckOutcome {
    GATE_BLOCKED, // 被决策闸门拦截
    NO_HOOK,      // 召不出钩子（无记忆话题）
    ENQUEUED,     // 成功投入待发队列
    ALREADY_SENT  // 节日今天已发或已在队列里（V2.1.7：区分「今天没节日」与「已发」）
}

/**
 * 定时主动检查的可测试编排（与 WorkManager 解耦）。任务 3.2
 *
 * 一轮流程（§任务 3.2）：**过决策闸门 → 召回记忆钩子 → 投入待发队列**。
 *  - [gate]：决策闸门，3.2 用 [com.companion.chat.domain.proactive.AllowAllGate] 占位，3.4 换真实规则。
 *  - [recallHook]：召回一条记忆话题，返回记忆 id；null=召不出（无钩子）。
 *  - [queue]：统一待发队列（3.1），生产用 [PendingMessageQueue]。
 *
 * 只**投递**不发送：真正生成/送达由 3.5 负责。本轮无钩子返回 [ProactiveCheckOutcome.NO_HOOK]，
 * 上层据此不发消息。
 */
class ProactiveCheckRunner(
    private val gate: ProactiveGate,
    /** 今天的节日/纪念日名（用户自存 + 内置节日，两个子类都算）；null=今天没有。V2.1.7 */
    private val recallFestival: (suspend () -> String?)? = null,
    private val recallHook: suspend () -> Long?,
    private val queue: PendingMessageQueue,
    /** 收到的是**完整去重键** `fes_sent_<name>_<dateKey>`，不是裸 name。V2.1.7 */
    private val checkSent: suspend (dedupeKey: String) -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { android.util.Log.w("ProactiveCheck", it) },
    /**
     * 无记忆钩子时的**兜底话题**（2026-11-21；1.9.4.0 改为带长期记忆 id）。
     *
     * 返回 Pair(长期记忆 id, hook 文本)：id 随消息入队，供通知"别提这个"回执扣分；
     * null = 本轮确实无话可说。若只有文本没有可用 id（纯关心），由 [plainTextFallback] 提供。
     */
    private val recallFallback: suspend () -> Pair<Long, String>? = { null },
    /** 纯关心兜底（无关联记忆 id，不带"别提"按钮）。 */
    private val plainTextFallback: suspend () -> String? = { null },
) {

    data class Result(val outcome: ProactiveCheckOutcome, val pendingId: Long? = null)

    /** 执行一轮检查，返回结果。任何协作者抛异常由调用方（Worker）捕获决定重试。 */
    suspend fun runOnce(): Result {
        // ① 节日优先（V2.1.7）：命中就只走节日，不回退试记忆。
        // 勿扰对所有类别都生效，节日被拦再回去试记忆纯属白召回。
        val festival = recallFestival?.invoke()
        if (festival != null) {
            val zone = ZoneId.systemDefault()
            // 日期由 runner 自己的 now() 派生（可注入），与记账侧共用 FestivalDedupe 避免算法分叉
            val dedupeKey = FestivalDedupe.key(festival, now(), zone)
            // ② 送达回调已经记过账（今天发过）
            if (checkSent(dedupeKey)) {
                log("节日今天已发，跳过 name=$festival key=$dedupeKey")
                return Result(ProactiveCheckOutcome.ALREADY_SENT)
            }
            // ③ 同 content 已在队列里（同一轮内的洞：去重键只在送达后写）
            val content = PendingMessageContent.Festival(festival).encode()
            if (queue.hasPendingWithContent(content)) {
                log("节日已在队列，跳过 name=$festival")
                return Result(ProactiveCheckOutcome.ALREADY_SENT)
            }
            // ④ 闸门预筛：本轮不入队；周期性检查下轮会重试，避免总开关长期关闭时堆消息
            if (!gate.allow(ProactiveMessageCategory.SPECIAL_DATE)) {
                log("闸门拦截(SPECIAL_DATE)，不入队 name=$festival")
                return Result(ProactiveCheckOutcome.GATE_BLOCKED)
            }
            // ⑤ 入队：priority=3 由 PendingMessageType 枚举自带，不传
            val pendingId = queue.enqueue(
                type = PendingMessageType.SPECIAL_DATE,
                content = PendingMessageContent.Festival(festival),
                triggerTime = now()
            )
            log("投入节日队列 id=$pendingId name=$festival")
            return Result(ProactiveCheckOutcome.ENQUEUED, pendingId)
        }

        // ⑥ 记忆分支保持原样：闸门 → 召回 → CHITCHAT + Memory(id)
        if (!gate.allow(ProactiveMessageCategory.SPONTANEOUS)) {
            log("闸门拦截，本轮不投递")
            return Result(ProactiveCheckOutcome.GATE_BLOCKED)
        }
        val hookId = recallHook()
        if (hookId == null) {
            // 兜底话题（2026-11-21）：短期记忆空了也要能说句话，否则「随机关心」永远不发。
            // 1.9.4.0：优先带 id 的长期记忆兜底（通知"别提"用）；没有则退到纯文本。
            val fallback = runCatching { recallFallback() }
                .onFailure { log("兜底话题失败：${it.message}") }
                .getOrNull()
            if (fallback != null && fallback.second.isNotBlank()) {
                val pendingId = queue.enqueue(
                    type = PendingMessageType.CHITCHAT,
                    content = PendingMessageContent.LongMemory(fallback.first),
                    triggerTime = now()
                )
                log("投入兜底话题队列 id=$pendingId（长期记忆 ${fallback.first}）")
                return Result(ProactiveCheckOutcome.ENQUEUED, pendingId)
            }
            val plain = runCatching { plainTextFallback() }.onFailure { log("纯关心兜底失败：${it.message}") }.getOrNull()
            if (plain.isNullOrBlank()) {
                log("无记忆钩子，跳过本轮")
                return Result(ProactiveCheckOutcome.NO_HOOK)
            }
            val pendingId = queue.enqueue(
                type = PendingMessageType.CHITCHAT,
                content = PendingMessageContent.PlainText(plain),
                triggerTime = now()
            )
            log("投入兜底话题队列 id=$pendingId")
            return Result(ProactiveCheckOutcome.ENQUEUED, pendingId)
        }
        val pendingId = queue.enqueue(
            type = PendingMessageType.CHITCHAT, // 记忆话题驱动的自发闲聊，最低优先级兜底
            content = PendingMessageContent.Memory(hookId),
            triggerTime = now()
        )
        log("投入待发队列 id=$pendingId（记忆 $hookId）")
        return Result(ProactiveCheckOutcome.ENQUEUED, pendingId)
    }

    companion object {
        /**
         * 从 content 解析来源记忆 id（非记忆来源返回 null）。
         * 委托给类型安全 codec [PendingMessageContent.memoryIdOrNull]，3.5 应统一用它解析。
         */
        fun memoryIdOf(content: String): Long? = PendingMessageContent.memoryIdOrNull(content)
    }
}
