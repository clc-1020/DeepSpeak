package com.companion.chat.data.alarm

/**
 * 闹钟调度抽象（便于单测注入假实现）。任务 3.3 / 3.5
 * 生产实现见 [AlarmScheduler]（AlarmManager.setAlarmClock / setAndAllowWhileIdle）。
 */
interface AlarmScheduling {
    /**
     * 设一个闹钟；到点触发 [com.companion.chat.worker.ReminderAlarmReceiver]。
     *
     * 默认参数写在**接口**上：现有 3 参调用点（用户指令提醒）零改动。
     * 实现类（[AlarmScheduler] / 测试 Fake）**不能重复写默认值**，只写 5 参签名。
     *
     * @param reminderId 关联的提醒 id；环境检查传 null（不用 0L 哨兵——那会和真实 id=0 撞车）
     * @param kind 用途；[AlarmKind.INSTRUCTION] 是默认（用户提醒走 setAlarmClock）
     */
    fun schedule(
        requestCode: Int,
        triggerAtMillis: Long,
        taskDesc: String,
        reminderId: Long? = null,
        kind: AlarmKind = AlarmKind.INSTRUCTION
    )

    /** 取消对应 requestCode 的闹钟。 */
    fun cancel(requestCode: Int)
}
