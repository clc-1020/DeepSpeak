package com.companion.chat.domain.environment

import java.time.Instant
import java.time.ZoneId

/**
 * 环境钩子去重键的**唯一出处**。任务 V2.1.2。
 *
 * `data/` 侧的 `EnvironmentHookService` 查键、`CompanionApp` 的送达回调记账、测试断言，
 * 全部调这里。**任何地方都不许自己拼 `now - 4h` 或格式化日期**——两边算法一旦分叉，
 * 熬夜就会每半小时发一次（查键用「今天」、记账用「今晚」，永远查不到）。
 */
object EnvironmentHooks {

    /** 熬夜钩子的 stableId。 */
    const val LATE_NIGHT = "late_night"

    /**
     * 熬夜的「夜」归属：本地时刻在凌晨 `nightEndHour` 之前 → 归到**前一天**。
     *
     * **为什么不能再用固定偏移 `now - 4h`**（原实现，V2.1.4 发现 bug）：
     * 熬夜窗口是 `hour >= 23 || hour < 5`（23:00–04:59），而去重键若按 `now - 4h`，
     * `04:30 - 4h = 00:30` 落在**当天**，于是同一夜被劈成两个键：
     * `23:30 → 11-20`、`04:30 → 11-21`。整点闹钟 04:00 一响，
     * `recall()` 查 `env_sent_late_night_11-21` 查不到 → **再发一条**。
     *
     * 改为按**本地小时**判断：`hour < nightEndHour(5)` 就归前一天。
     * 这样 23:30 / 00:30 / 04:30 全落同一夜，05:00 才开新的一天。
     * 20:00 起的夜间（23:00–04:59 同属「今晚」）也天然正确：
     * `20:00` 时 `hour=20 >= 5` → 归当天，与凌晨归前一天恰好拼成完整一夜。
     */
    internal const val NIGHT_START_HOUR = 20   // 20:00 起算「夜」的归属日期（仅文档用）
    internal const val NIGHT_END_HOUR = 5      // 凌晨 5 点起算新的一天（与规则引擎的 nightEndHour 一致）

    /**
     * 去重用的「日」。
     *
     * @param stableId [EnvironmentHook.stableId]；只有 [LATE_NIGHT] 走按夜聚合，其余按当天
     * @param nowMillis 当前时刻（epoch millis）
     * @param zone 用户时区
     * @return `yyyy-MM-dd`
     */
    fun dateKeyOf(stableId: String, nowMillis: Long, zone: ZoneId): String {
        val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val date = if (stableId == LATE_NIGHT && zoned.hour < NIGHT_END_HOUR) {
            zoned.toLocalDate().minusDays(1)
        } else {
            zoned.toLocalDate()
        }
        return date.toString()
    }

    /**
     * 环境钩子的泛化文案（计划书 §3.4 B7 决策 = a：**不拼数值**）。
     *
     * `Rain(80)` / `TempDrop(6)` 的数字只在规则引擎判阈值时用，到文案层已经丢了；
     * 只按 [stableId] 给一句通用关心。**不要在这里去读天气缓存补数字**——
     * 那需要网络、会拖慢送达，而且送达时天气可能已经变了。
     *
     * 未知 stableId 给一句兜底，永不返回空串（空串会被送达层当成生成失败）。
     */
    fun genericText(stableId: String): String = when {
        stableId == LATE_NIGHT -> "该睡了，别熬了"
        stableId == "rain" -> "今天有雨，记得带伞"
        stableId == "temp_drop" -> "今天降温了，记得添衣"
        stableId == "high_temp" -> "今天很热，注意防暑"
        stableId == "mealtime_lunch" -> "该吃午饭了"
        stableId == "mealtime_dinner" -> "该吃晚饭了"
        else -> "注意照顾自己"
    }
}