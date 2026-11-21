package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.domain.memory.MemoryFeedback

/**
 * 记忆打分三态业务入口。任务 2.8
 *
 * 主动消息通知三按钮（任务 3.4/3.5 完成后接上）回调到这里：
 *  - 回复 → [onReply]：`weight += 0.2`（[MemoryFeedback.afterReply] 封顶）。
 *  - 忽略 → [onIgnore]：不改记忆，仅上层不回复（打日志便于真机确认被调用）。
 *  - 别提这个 → [onDismiss]：第 1 次冷却 30 天，第 2 次永久拉黑。
 *
 * 记忆不存在（例如用户先在记忆管理界面删了）时静默返回，不抛异常。
 *
 * `log` 注入以便单测（默认走 `android.util.Log.d`，单测可传 `{}` 或记录器，避免 JVM 无 Log 实现）。
 */
class MemoryFeedbackService(
    private val shortDao: MemoryShortDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { android.util.Log.d(TAG, it) }
) {

    /** 「回复」：权重 +0.2。 */
    suspend fun onReply(memoryId: Long) {
        val m = shortDao.getById(memoryId) ?: return
        shortDao.updateWeight(memoryId, MemoryFeedback.afterReply(m.weight))
        log("回复记忆 $memoryId → weight=${MemoryFeedback.afterReply(m.weight)}")
    }

    /** 「忽略」：不修改记忆（仅表示用户这次没兴趣）。打日志便于真机确认被正确调用。 */
    suspend fun onIgnore(memoryId: Long) {
        log("用户忽略记忆 $memoryId（占位，暂不改库）")
    }

    /**
     * 「别提这个」：第 1 次减权 + 冷却 30 天，第 2 次起永久拉黑。
     * @return 本次操作后是否已处于拉黑状态（便于上层提示「以后不再提」）。
     */
    suspend fun onDismiss(memoryId: Long): Boolean {
        val m = shortDao.getById(memoryId) ?: return false
        val r = MemoryFeedback.afterDismiss(
            currentWeight = m.weight,
            currentDismissCount = m.dismissCount,
            currentCoolingUntil = m.coolingUntil,
            nowMillis = now()
        )
        shortDao.applyFeedback(memoryId, r.weight, r.dismissCount, r.coolingUntil, r.blacklisted)
        log(
            "别提这个 记忆 $memoryId → dismissCount=${r.dismissCount}, " +
                "blacklisted=${r.blacklisted}, coolingUntil=${r.coolingUntil}"
        )
        return r.blacklisted
    }

    private companion object {
        const val TAG = "MemoryFeedbackService"
    }
}
