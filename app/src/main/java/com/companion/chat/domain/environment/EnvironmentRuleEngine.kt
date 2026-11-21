package com.companion.chat.domain.environment

import java.time.LocalDateTime

/**
 * 环境规则引擎：把「现在几点 + 今天/昨天天气」翻译成一串该发的钩子。任务 V2.1.2。
 *
 * 纯函数、无 Android 依赖、无 IO、无当前时间读取（时间由参数注入），所以主机上能 100% 跑完。
 * 调用方（V2.1.4 `EnvironmentHookService.recall()`）只取第一条——**排序就是优先级**。
 *
 * 排序（高 → 低）：熬夜 > 带伞 > 添衣 > 饭点 > 高温。
 * 逻辑：熬夜是「人还没睡」最该管的事；天气类属于「出门前提醒」，带伞比添衣更紧急（会淋湿）；
 * 饭点属于日常关怀，不该压过天气预警；高温放最后是因为它常和「带伞/添衣」同时出现，
 * 一次只发一条，说最要紧的那件。
 */
class EnvironmentRuleEngine {

    /**
     * 评估当前该发的钩子。
     *
     * @param now 当前本地时间（含时区语义，调用方传 `LocalDateTime.now()`）
     * @param today 今天的天气快照；null 表示天气不可用 → **只返回时间类钩子**
     * @param yesterday 昨天的天气快照；null 表示拿不到 → 不返回 [EnvironmentHook.TempDrop]
     *                  （没有基准温度就算不出降温，硬算会误报）
     * @param cfg 阈值配置
     * @return 按优先级排序的钩子列表，可能为空
     */
    fun evaluate(
        now: LocalDateTime,
        today: WeatherSnapshot?,
        yesterday: WeatherSnapshot?,
        cfg: EnvironmentRuleConfig = EnvironmentRuleConfig(),
    ): List<EnvironmentHook> {
        val out = ArrayList<EnvironmentHook>(5)

        // 1. 熬夜（只看时间，与天气无关）
        val hour = now.hour
        if (hour >= cfg.lateNightHour || hour < cfg.nightEndHour) {
            out += EnvironmentHook.LateNight(hour)
        }

        if (today != null) {
            // 2. 带伞：降水概率严格大于阈值
            val precip = today.precipProbabilityMax
            if (precip != null && precip > cfg.rainThreshold) {
                out += EnvironmentHook.Rain(precip)
            }

            // 3. 添衣：今天最低温比昨天最低温低超过阈值；任一侧缺失则跳过
            val todayMin = today.tempMin
            val yesterdayMin = yesterday?.tempMin
            if (todayMin != null && yesterdayMin != null) {
                val delta = yesterdayMin - todayMin
                if (delta > cfg.tempDropThreshold) {
                    out += EnvironmentHook.TempDrop(delta)
                }
            }
        }

        // 4. 饭点
        mealtimeOf(now, cfg)?.let { out += it }

        if (today != null) {
            // 5. 高温
            val max = today.tempMax
            if (max != null && max > cfg.hotThreshold) {
                out += EnvironmentHook.HighTemp(max)
            }
        }

        return out
    }

    /**
     * 饭点判定。窗口是 `[center - half, center + half)`：
     * 11:30 命中、12:00 命中、12:29 命中、12:30 不命中（右开，避免和下午连成一片）。
     */
    private fun mealtimeOf(now: LocalDateTime, cfg: EnvironmentRuleConfig): EnvironmentHook.Mealtime? {
        val minuteOfDay = now.hour * 60 + now.minute
        val half = cfg.mealtimeWindowMinutes
        return when {
            inWindow(minuteOfDay, EnvironmentRuleConfig.MealtimeCenters.LUNCH, half) ->
                EnvironmentHook.Mealtime("lunch")
            inWindow(minuteOfDay, EnvironmentRuleConfig.MealtimeCenters.DINNER, half) ->
                EnvironmentHook.Mealtime("dinner")
            else -> null
        }
    }

    private fun inWindow(minuteOfDay: Int, center: Int, half: Int): Boolean =
        minuteOfDay >= center - half && minuteOfDay < center + half
}