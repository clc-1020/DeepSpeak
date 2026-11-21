package com.companion.chat.domain.environment

/**
 * 环境规则阈值。任务 V2.1.2。
 *
 * 默认值全部写在构造参数上，调用方（`CompanionApp`）直接 `EnvironmentRuleConfig()` 即可；
 * 测试要打边界值时显式传参，不要改默认值。
 *
 * @param lateNightHour 熬夜起点整点（含）：默认 23，即 23:00 起算熬夜
 * @param nightEndHour 熬夜终点整点（不含）：默认 5，即 04:59 仍算熬夜、05:00 不算。
 *   **必须与 [EnvironmentHooks.NIGHT_END_HOUR] 一致**——前者判「是否熬夜」，后者判「归哪一夜」，
 *   两处分叉会把同一夜劈成两个去重键（见 V2.1.4 的 04:30 连发 bug）
 * @param rainThreshold 降水概率阈值（**严格大于**才提醒带伞）：默认 60，即 61 触发、60 不触发
 * @param tempDropThreshold 降温阈值（**严格大于**才提醒添衣，单位摄氏度）：默认 5
 * @param hotThreshold 高温阈值（**严格大于**才提醒防暑）：默认 35
 * @param mealtimeWindowMinutes 饭点窗口半宽（分钟）：默认 30，即 12:00 前后半小时 = [11:30, 12:30)
 */
data class EnvironmentRuleConfig(
    val lateNightHour: Int = 23,
    val nightEndHour: Int = EnvironmentHooks.NIGHT_END_HOUR,
    val rainThreshold: Int = 60,
    val tempDropThreshold: Int = 5,
    val hotThreshold: Int = 35,
    val mealtimeWindowMinutes: Int = 30,
) {
    /** 饭点中心（分钟数，从 0 点算）：午餐 12:00、晚餐 18:00。 */
    internal object MealtimeCenters {
        const val LUNCH = 12 * 60
        const val DINNER = 18 * 60
    }
}