package com.companion.chat.domain.environment

import java.time.Instant
import java.time.ZoneId

/**
 * 环境钩子：一次性触发「该关心一下用户了」的信号。任务 V2.1.2。
 *
 * 每个钩子带两个标识：
 * - [stableId]：类型标识，用于同日去重（`env_sent_<stableId>_<dateKey>`）
 * - [dateKey]：去重用的「日」。**熬夜必须按夜计**，其余按当天，否则会出现 23:30 发一次、
 *   次日 00:30 再发一次的连发问题（见 [EnvironmentHooks.dateKeyOf]）
 *
 * 熬夜为什么单独处理：真人熬夜是「一晚上」而不是「两个日期」，00:30 和 23:30 属于同一件事。
 * 用 `now - 4h` 的日期作键，20:00–03:59 自然落进同一个「夜」。
 */
sealed class EnvironmentHook {

    /** 类型标识，同日去重键的前半段。 */
    abstract val stableId: String

    /**
     * 去重用的「日」（`yyyy-MM-dd` 字符串）。默认当天。
     *
     * 子类若要按「夜」聚合（只有 [LateNight]）必须覆写。
     *
     * @param nowMillis 当前时刻（epoch millis），由调用方注入以便测试
     * @param zone 用户时区，不能硬编码
     */
    open fun dateKey(nowMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()

    /**
     * 熬夜。同一「夜」只发一条。
     *
     * @param hour 触发时的整点（0..23），仅用于文案，不参与去重
     */
    data class LateNight(val hour: Int) : EnvironmentHook() {
        override val stableId: String = EnvironmentHooks.LATE_NIGHT

        /**
         * 按夜计：凌晨 `hour < 5` 归前一天（与 [EnvironmentHooks.dateKeyOf] 同一逻辑）。
         *
         * 23:30 / 00:30 / 04:30 同属一夜；05:00 起开新的一天。
         * 不能按固定 `now - 4h`：那会把 04:00–04:59 劈成「新一夜」，整点闹钟一响就重发。
         */
        override fun dateKey(nowMillis: Long, zone: ZoneId): String =
            EnvironmentHooks.dateKeyOf(EnvironmentHooks.LATE_NIGHT, nowMillis, zone)
    }

    /**
     * 降水概率高，提醒带伞。
     *
     * @param probability 降水概率百分比（已过阈值，直接用于文案）
     */
    data class Rain(val probability: Int) : EnvironmentHook() {
        override val stableId: String = "rain"
    }

    /**
     * 降温，提醒添衣。
     *
     * @param delta 降温幅度：**正数表示降了多少度**（`昨天最低温 - 今天最低温`），已过阈值。
     *   文案层直接引用这个数（「降了 6 度」），**不要在那个方向再做一次减法**——
     *   反着算会得出负数，文案会变成「降了 -6 度」。
     */
    data class TempDrop(val delta: Int) : EnvironmentHook() {
        override val stableId: String = "temp_drop"
    }

    /**
     * 高温，提醒防暑。
     *
     * @param temp 当日最高温（已过阈值）
     */
    data class HighTemp(val temp: Int) : EnvironmentHook() {
        override val stableId: String = "high_temp"
    }

    /**
     * 饭点。午/晚各一条，`stableId` 带类型后缀所以互不覆盖。
     *
     * @param type `"lunch"` 或 `"dinner"`
     */
    data class Mealtime(val type: String) : EnvironmentHook() {
        override val stableId: String = "mealtime_$type"
    }
}