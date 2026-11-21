package com.companion.chat.data.alarm

/**
 * 录制式 [AlarmScheduling]（V2.2.2 起用于 `FollowupPortImplTest`，V2.2.4 提到共用位置）。
 *
 * 原来它是 `FollowupPortImplTest` 里的 private 内嵌类；V2.2.4 的 `FollowupChainRunnerTest`
 * 需要同一套录制语义（断言「排了哪个 code、取消哪三个 code、排在什么时间」），
 * 就地复制会留下两份可能分叉的 fake，故提成 `data/alarm` 下的公共测试类。
 *
 * [events] 是给人看的字符串流水（格式与 V2.2.2 完全一致，旧断言不用改）；
 * [schedules] / [cancels] 是结构化版本，新测试按字段断言，避免解析字符串。
 */
class FakeAlarmScheduling : AlarmScheduling {

    /** 一次 schedule 调用的完整入参。 */
    data class Scheduled(
        val requestCode: Int,
        val triggerAtMillis: Long,
        val taskDesc: String,
        val reminderId: Long?,
        val kind: AlarmKind
    )

    /** 字符串流水：`schedule:code:kind:taskDesc:reminderId:at` / `cancel:code`（V2.2.2 格式）。 */
    val events = mutableListOf<String>()

    /** 结构化流水（断言用）。 */
    val schedules = mutableListOf<Scheduled>()

    /** 取消过的 requestCode（含重复取消，顺序保留）。 */
    val cancels = mutableListOf<Int>()

    /** 跨 fake 的调用顺序流水（V2.2.4），与 `FakeReminderDao.order` 塞同一个 list。 */
    var order: MutableList<String>? = null

    /** 下一次 schedule 抛这个异常（V2.2.4 审查 R1：补钟失败的容错分支要能测）。 */
    var failNextSchedule: Throwable? = null

    override fun schedule(
        requestCode: Int,
        triggerAtMillis: Long,
        taskDesc: String,
        reminderId: Long?,
        kind: AlarmKind
    ) {
        order?.add("schedule:${kind.name}")
        failNextSchedule?.let {
            failNextSchedule = null
            throw it
        }
        schedules += Scheduled(requestCode, triggerAtMillis, taskDesc, reminderId, kind)
        events += "schedule:$requestCode:$kind:$taskDesc:$reminderId:$triggerAtMillis"
    }

    override fun cancel(requestCode: Int) {
        order?.add("cancel:$requestCode")
        cancels += requestCode
        events += "cancel:$requestCode"
    }

    /** 某个 code 的所有排程（正常应为 0 或 1 条）。 */
    fun schedulesOf(requestCode: Int): List<Scheduled> =
        schedules.filter { it.requestCode == requestCode }

    /** 某个 code 被取消了几次。 */
    fun cancelCount(requestCode: Int): Int = cancels.count { it == requestCode }
}