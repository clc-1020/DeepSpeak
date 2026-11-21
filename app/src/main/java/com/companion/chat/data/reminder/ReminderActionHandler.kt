package com.companion.chat.data.reminder

import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.NotificationActions
import com.companion.chat.domain.reminder.FollowupPort

/**
 * 通知按钮回执的分支处理（V2.2.5）。
 *
 * 从 [com.companion.chat.worker.ReminderDoneReceiver] 抽出来的原因：receiver 是 Android 类，
 * JVM 单测跑不了；而「done / postpone 各只触发对应分支」是本任务必须测的验收项。
 *
 * 三件事的**顺序不可调换**（尤其 DONE）：
 *   1. 先让追问链「永久停」——`complete()` 内部先 updateState(DONE) 再 cancelAll 三个钟；
 *   2. 再清**已经入队**的残留追问（`cancelPendingFollowups`）。
 * 反过来的话：若先清队列而 complete 因 DB 异常失败，闹钟还在、链仍是 pending，
 * 下一次 FOLLOWUP 到点又入队 → 无限追问，比「漏清一条残留」严重得多。
 * 反之（先 complete 成功、清队列失败）最坏只是多收到一条追问，是噪音而非死链。
 */
class ReminderActionHandler(
    private val followupPort: FollowupPort,
    private val queue: PendingMessageQueue,
    private val deleteMemory: suspend (Long) -> Unit,
    /**
     * 1.9.4.0：长期记忆的"别提"回执（扣分 + 冷却，不删行）。
     * 返回 true = 该 id 命中了长期记忆，此时**不再**走短期 deleteMemory。
     */
    private val dismissLongMemory: suspend (Long) -> Boolean = { false },
) {

    /**
     * @param action [NotificationActions.ACTION_DONE] / [NotificationActions.ACTION_POSTPONE]，
     *   **允许 null**：旧的「已完成」PendingIntent 发出时还没有 EXTRA_ACTION，
     *   收不到就按 done 处理，否则升级后用户点旧通知会完全无反应。
     *   未知字符串一律忽略（不动作），避免将来加新 action 时拼错静默变成 done。
     * @return 是否真的做了事（false = 没有可用 id 或 action 未知）。
     */
    suspend fun handle(action: String?, memoryId: Long, reminderId: Long): Boolean = when (action) {
        NotificationActions.ACTION_POSTPONE -> {
            if (reminderId <= 0L) false
            else {
                followupPort.advance(reminderId)   // 不扣次数：advance 内部刻意不 incrementAttempt
                true
            }
        }

        null, NotificationActions.ACTION_DONE -> {
            var handled = false
            if (memoryId > 0L) {
                // 1.9.4.0：先试长期记忆（兜底话题通知）；命中则只扣分冷却，不删行。
                // 没命中再按旧的短期记忆删除语义处理（指令提醒"已完成"）。
                val longHandled = runCatching { dismissLongMemory(memoryId) }.getOrDefault(false)
                if (!longHandled) deleteMemory(memoryId)
                handled = true
            }
            if (reminderId > 0L) {
                followupPort.complete(reminderId)
                queue.cancelPendingFollowups(reminderId)
                handled = true
            }
            // 2026-11-20：这条链对应的短记忆由 FollowupPortImpl.complete() 内部清理
            // （对话里说「做完了」走的是同一条路，删在这里会漏掉那条）。
            handled
        }

        else -> false
    }
}