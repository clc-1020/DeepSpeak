package com.companion.chat.domain.proactive

/**
 * 通知按钮的「计划」（V2.2.5）。
 *
 * 为什么要单独一层：计划书要求单测「followupReminderId == null 时不加追问按钮」，
 * 而 AndroidProactiveNotifier 需要 Context + NotificationCompat.Builder，JVM 单测跑不起来
 * （本项目没有 Robolectric）。把「加哪些按钮、requestCode 是多少」抽成纯整数运算后，
 * 这条验收才能真测。渲染（icon / label / PendingIntent）留在 data 层。
 */
enum class NotificationActionKind {
    /** 「已完成」：指令提醒删关联网记忆；追问链完成整条链 + 清掉已入队的追问。 */
    DONE,

    /** 「再等等」：只对追问链有效，等价于点完后重排下一次（不扣次数）。 */
    POSTPONE
}

/** 一个要挂到通知上的按钮。[requestCode] 供 PendingIntent 用，同一通知内必须互不相同。 */
data class PlannedAction(
    val kind: NotificationActionKind,
    val requestCode: Int
)

object NotificationActions {

    // ── wire 值（写进 ReminderDoneReceiver 的 EXTRA_ACTION，改了就与已发出的旧通知不兼容）──
    const val ACTION_DONE = "done"
    const val ACTION_POSTPONE = "postpone"

    // ── requestCode 偏移。三个必须互不相同，见 4.2 的「PendingIntent 覆写」说明 ──
    private const val MEMORY_DONE_OFFSET = 100_000   // 沿用任务 3.7 的既有偏移
    private const val FOLLOWUP_DONE_OFFSET = 300_000
    private const val POSTPONE_OFFSET = 400_000

    /**
     * 决定这条通知挂哪些按钮。
     *
     * 一条通知的 content 只可能是单一类型（[PendingMessageContent] 是 sealed），
     * 因此两个 id **正常情况互斥**。但纯函数必须有确定行为，故定：
     * **两者都非空时按追问处理**（追问的「完成」语义更强：要停整条链）。
     * 这个兜底由 `both_ids_prefer_followup` 单测钉住。
     */
    fun planFor(
        notificationId: Int,
        completeMemoryId: Long?,
        followupReminderId: Long?
    ): List<PlannedAction> {
        if (followupReminderId != null) {
            return listOf(
                PlannedAction(NotificationActionKind.DONE, code(notificationId, FOLLOWUP_DONE_OFFSET)),
                PlannedAction(NotificationActionKind.POSTPONE, code(notificationId, POSTPONE_OFFSET))
            )
        }
        if (completeMemoryId != null) {
            return listOf(PlannedAction(NotificationActionKind.DONE, code(notificationId, MEMORY_DONE_OFFSET)))
        }
        return emptyList()
    }

    /**
     * 用 Long 相加再截断成 Int。
     *
     * 不要写成 `notificationId + OFFSET`（Int 加法）：notificationId 来自
     * `msg.id % Int.MAX_VALUE`，可以非常接近 Int.MAX_VALUE，Int 加法会静默溢出。
     * 溢出本身不致命（两个按钮的差值 100_000 在模 2^32 下仍保留，不会相等），
     * 但「靠溢出后仍恰好不等」不该是设计。Long 相加后 toInt 截断是明确行为。
     */
    private fun code(notificationId: Int, offset: Int): Int =
        (notificationId.toLong() + offset).toInt()
}