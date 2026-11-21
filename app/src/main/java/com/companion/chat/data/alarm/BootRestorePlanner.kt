package com.companion.chat.data.alarm

import com.companion.chat.data.local.entity.ReminderType

/**
 * 重启恢复的「要排哪些闹钟」计划（B9，纯逻辑）。
 *
 * 把「重启时 `reminders` 表里 pending 的行」翻译成「要设的闹钟」，**不碰 AlarmManager、
 * 不碰 Room、不碰 entity** —— 因此 100% 可 JVM 单测（这是原来焊死在 `CompanionApp` 里、
 * 只能靠真机凑数据的分支）。
 *
 * 输出与入参**同序**（DAO 的 `getPending()` 已是 `ORDER BY triggerTime ASC`），
 * 调用方用 `plan[i]` 与 `pending[i]` 一一对应即可，不必反查。
 *
 * @property requestCode 按 type 分配：followup → [AlarmCodes.followup]，其余 → [AlarmCodes.first]，
 *   与设钟侧同一公式，保证恢复出的闹钟命中同一个 `PendingIntent`
 * @property atMillis 实际设钟时刻
 * @property overdue 是否「关机期间已错过」（true = 改成 now+延迟 立即补发）
 * @property kind 闹钟用途：followup → [AlarmKind.FOLLOWUP]，其余 → [AlarmKind.INSTRUCTION]。
 *   V2.2.3 连带修复（坑 B）：若把 followup 行恢复成 first(id)+INSTRUCTION，到点会走
 *   `handleInstruction` 并 `updateState(DONE)`，整条追问链被静默吞掉。
 */
data class RestoreTarget(
    val requestCode: Int,
    val atMillis: Long,
    val overdue: Boolean,
    val kind: AlarmKind
)

object BootRestorePlanner {

    /**
     * @param pending 待恢复的 `(reminderId, triggerTime, type)` 列表，顺序保持
     * @param now 恢复动作开始时刻（**只取一次**，不在循环里刷新——否则多行处理时长会让
     *   后面的行算出不一致的基准）
     * @param delayMs 过期行延迟多久补发（默认 5s：够快，又避开开机瞬间的资源抢占）
     * @param staggerMs 多条过期行之间的错开间隔。**必须有**：否则十条 overdue 全排在同一秒，
     *   同一秒弹十条通知 + 十次播报
     */
    fun plan(
        pending: List<Triple<Long, Long, String>>,
        now: Long,
        delayMs: Long = 5_000L,
        staggerMs: Long = 1_000L
    ): List<RestoreTarget> {
        var overdueIndex = 0
        return pending.map { (reminderId, triggerTime, type) ->
            val late = triggerTime <= now
            val at = if (late) {
                now + delayMs + overdueIndex * staggerMs
            } else {
                triggerTime
            }
            if (late) overdueIndex++
            val isFollowup = type == ReminderType.FOLLOWUP
            RestoreTarget(
                requestCode = if (isFollowup) AlarmCodes.followup(reminderId) else AlarmCodes.first(reminderId),
                atMillis = at,
                overdue = late,
                kind = if (isFollowup) AlarmKind.FOLLOWUP else AlarmKind.INSTRUCTION
            )
        }
    }
}