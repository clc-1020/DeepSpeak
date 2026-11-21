package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.domain.memory.ShortMemoryCapacity

/**
 * 短期记忆容量淘汰的执行器（2026-11-20）。
 *
 * 策略全在 [ShortMemoryCapacity]（纯函数、可单测）；这里只负责「读 → 算 → 删 → 记日志」。
 *
 * **触发时机**：每次**写入**短期记忆之后（对话提炼 / 指令提醒到点），不做定时扫描 ——
 * 用户要的语义是「攒满就清」，而且半夜定时删东西很难解释。
 *
 * **保护对象**：正在 `pending` 的提醒/追问任务（`instruction` / `followup`）对应的记忆。
 * 用 [com.companion.chat.domain.reminder.ReminderMemoryMatcher] 判内容是否属于该任务，
 * 与「点完成时删记忆」是同一套规则，避免两套判定打架。
 *
 * 失败一律静默（返回 0）：清理是后台维护，绝不能因为它影响主对话或提醒送达。
 */
class MemoryCapacityEnforcer(
    private val shortDao: MemoryShortDao,
    private val reminderDao: ReminderDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.w(TAG, it) }
) {

    /** @return 实际删除的条数（0 = 没到阈值 / 全被保护 / 失败） */
    suspend fun enforceOnce(): Int = runCatching {
        val all = shortDao.getAll()
        if (all.size < ShortMemoryCapacity.DEFAULT_MAX) return@runCatching 0

        // 只保护「正在跑」的提醒/追问；节日/环境/闲聊类没有"在做这件事"的语义，不保护
        val pendingTasks = reminderDao.getPending()
            .filter { it.type == ReminderType.INSTRUCTION || it.type == ReminderType.FOLLOWUP }
            .map { it.taskDesc }
            .filter { it.isNotBlank() }

        val evictIds = ShortMemoryCapacity.plan(all, pendingTasks, now())
        if (evictIds.isEmpty()) {
            log("容量 ${all.size} 已满但无可淘汰（全部在保护窗口或关联活跃任务）")
            return@runCatching 0
        }
        evictIds.forEach { shortDao.deleteById(it) }
        log("短期记忆淘汰 ${evictIds.size} 条（总量 ${all.size} → ${all.size - evictIds.size}），保护任务 ${pendingTasks.size} 条")
        evictIds.size
    }.getOrElse {
        log("淘汰失败（已忽略）：${it.message}")
        0
    }

    private companion object {
        const val TAG = "MemoryCapacity"
    }
}