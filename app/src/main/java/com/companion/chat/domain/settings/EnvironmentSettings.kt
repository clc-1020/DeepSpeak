package com.companion.chat.domain.settings

import com.companion.chat.domain.environment.EnvironmentRuleConfig

/**
 * 环境感知的用户设置。任务 V2.1.6。
 *
 * **为什么单独抽一个纯对象**：`ConfigRepository` 依赖 `Context` + `EncryptedSharedPreferences`，
 * 主机单测碰不了，于是它那 190 行 getter/setter 至今零测试。V2.1.6 的验收要求「单测覆盖默认值」，
 * 所以把**默认值、夹取范围、到引擎配置的映射**这三件纯事抽到这里，`ConfigRepository` 只负责
 * 把 prefs 里的原始值喂进来、把结果写回去。这样验收里真正会出错的逻辑是可测的。
 *
 * `manual_city_name` **故意不在这里**：它必须和 `manual_lat` / `manual_lon` 同库同生共死
 * （一起写、一起删，否则会出现「有城市名没坐标」的半残状态），所以三个键都放在 `app_state`
 * （V2.1.1 的 `LocationCache` 管）。设置页回显城市名请调 `LocationCache.manualCityName()`，
 * 不要读这里。
 *
 * @param environmentEnabled 环境感知总开关（默认开）。关掉后不排整点检查、不投环境钩子。
 * @param lateNightEnabled 熬夜提醒开关（默认开）。关掉只影响熬夜，不影响天气类。
 * @param lateNightHour 熬夜起点整点（默认 23）。v2 不对外开放编辑，留键位给以后。
 * @param rainThreshold 降水概率阈值（默认 60，严格大于才提醒）。
 * @param tempDropThreshold 降温阈值（默认 5℃，严格大于才提醒）。
 */
data class EnvironmentSettings(
    val environmentEnabled: Boolean = DEFAULT_ENVIRONMENT_ENABLED,
    val lateNightEnabled: Boolean = DEFAULT_LATE_NIGHT_ENABLED,
    val lateNightHour: Int = DEFAULT_LATE_NIGHT_HOUR,
    val rainThreshold: Int = DEFAULT_RAIN_THRESHOLD,
    val tempDropThreshold: Int = DEFAULT_TEMP_DROP_THRESHOLD,
) {

    /**
     * 把越界值夹回合法范围。
     *
     * 三处夹取的现实理由：
     * - `lateNightHour` 必须是合法整点，否则 `hour >= 23` 这种比较会永远/永不成立
     * - `rainThreshold` 是百分比，超 100 等于永不下雨
     * - `tempDropThreshold` **下限是 1 不是 0**：0 会让「降 0.4 度」也触发提醒，等于天天喊添衣
     */
    fun sanitized(): EnvironmentSettings = copy(
        lateNightHour = lateNightHour.coerceIn(0, 23),
        rainThreshold = rainThreshold.coerceIn(0, 100),
        tempDropThreshold = tempDropThreshold.coerceIn(MIN_TEMP_DROP_THRESHOLD, 30),
    )

    /**
     * 映射成规则引擎配置。
     *
     * `nightEndHour` 刻意不暴露给用户：夜间的「结束点」和「起点」必须分开考虑（23 点开始、
     * 5 点结束），让用户分别调会产生「起点 2 点 + 终点 5 点」这种莫名其妙的三小时熬夜窗口。
     */
    fun toRuleConfig(): EnvironmentRuleConfig = EnvironmentRuleConfig(
        lateNightHour = lateNightHour.coerceIn(0, 23),
        nightEndHour = EnvironmentRuleConfig().nightEndHour,
        rainThreshold = rainThreshold.coerceIn(0, 100),
        tempDropThreshold = tempDropThreshold.coerceIn(MIN_TEMP_DROP_THRESHOLD, 30),
        hotThreshold = EnvironmentRuleConfig().hotThreshold,
        mealtimeWindowMinutes = EnvironmentRuleConfig().mealtimeWindowMinutes,
    )

    companion object {
        const val DEFAULT_ENVIRONMENT_ENABLED = true
        const val DEFAULT_LATE_NIGHT_ENABLED = true
        const val DEFAULT_LATE_NIGHT_HOUR = 23
        const val DEFAULT_RAIN_THRESHOLD = 60
        const val DEFAULT_TEMP_DROP_THRESHOLD = 5

        /** 降温阈值下限：0 会让任何微小波动都触发，见 [sanitized]。 */
        const val MIN_TEMP_DROP_THRESHOLD = 1

        // prefs 键名（与 ConfigRepository 共用，避免两处写字符串）
        const val KEY_ENVIRONMENT_ENABLED = "environment_enabled"
        const val KEY_LATE_NIGHT_ENABLED = "late_night_enabled"
        const val KEY_LATE_NIGHT_HOUR = "late_night_hour"
        const val KEY_RAIN_THRESHOLD = "rain_threshold"
        const val KEY_TEMP_DROP_THRESHOLD = "temp_drop_threshold"
    }
}