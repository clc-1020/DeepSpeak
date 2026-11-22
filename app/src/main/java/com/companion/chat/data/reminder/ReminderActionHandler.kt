package com.companion.chat.data.reminder

import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.proactive.NotificationActions
import com.companion.chat.domain.reminder.FollowupPort

/**
 * 通知按钮回执的分支处理（V2.2.5；1.9.4.2 三态分流）。
 *
 * 从 [com.companion.chat.worker.ReminderDoneReceiver] 抽出来的原因：receiver 是 Android 类，
 * JVM 单测跑不了；而「done / postpone / dismiss 各只触发对应分支」是本任务必须测的验收项。
 *
 * 顺序约束（DONE，不可调换）：
 *   1. 先让追问链「永久停」——`complete()` 内部先 updateState(DONE) 再 cancelAll 三个钟；
 *   2. 再清**已经入队**的残留追问（`cancelPendingFollowups`）。
 * 反过来的话：若先清队列而 complete 因 DB 异常失败，闹钟还在、链仍是 pending，
 * 下一次 FOLLOWUP 到点又入队 → 无限追问，比「漏清一条残留」严重得多。
 *
 * **1.9.4.2（B 方案）按消息角色三态分流，不再靠记忆编号猜类型**（短期/长期两表编号会撞车）：
 *  - [NotificationActions.ACTION_DONE]    → 只删**短期**记忆（指令提醒）；
 *  - [NotificationActions.ACTION_DISMISS] → 只给**长期**记忆降权/冷却，绝不删行、绝不动短期表；
 *  - [NotificationActions.ACTION_POSTPONE]→ 追问链只重排。
 */
class ReminderActionHandler(
    private val followupPort: FollowupPort,
    private val queue: PendingMessageQueue,
    private val deleteMemory: suspend (Long) -> Unit,
    /**
     * 1.9.4.2：长期记忆「别提这个」回执（扣分 + 冷却，不删行）。
     * **只由 [NotificationActions.ACTION_DISMISS] 调用**，返回 true = 命中了长期记忆。
     * 注意：ACTION_DONE 不再调用它（那条路径只删短期记忆），彻底避免两表编号撞车误删。
     */
    private val dismissLongMemory: suspend (Long) -> Boolean = { false },
) {

    /**
     * @param action [NotificationActions.ACTION_DONE] / [NotificationActions.ACTION_POSTPONE] /
     *   [NotificationActions.ACTION_DISMISS]，**允许 null**：旧的「已完成」PendingIntent 发出时
     *   还没有 EXTRA_ACTION，收不到就按 done 处理，否则升级后用户点旧通知会完全无反应。
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
            // 1.9.4.2：「已完成」**只**删短期记忆（指令提醒）。
            // 旧逻辑「先试长期、失败退回删短期」会因短期/长期编号撞车误删，已移除。
            if (memoryId > 0L) {
                deleteMemory(memoryId)
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

        NotificationActions.ACTION_DISMISS -> {
            // 1.9.4.2：「别提这个」只对长期记忆降权/冷却，绝不删行、绝不动短期表。
            if (memoryId > 0L) {
                runCatching { dismissLongMemory(memoryId) }
                true
            } else {
                false
            }
        }

        else -> false
    }
}
