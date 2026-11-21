package com.companion.chat.domain.environment

/**
 * 把天气快照压成一句给模型看的中文短语。任务 V2.1.8（对话天气可见，纯函数）。
 *
 * **与 [EnvironmentHooks.genericText] 的分工**：
 * - [EnvironmentHooks.genericText] 是**主动消息**用的泛化措辞（`"今天有雨，记得带伞"`），
 *   计划书 §3.4 B7 决策 = a：主动消息不拼数值（那条链上天气可能已经变了，且多调一次没意义）。
 * - 本类只服务**对话上下文**（`latest_reminder` 时间注记）。这里用户正主动和模型说话，
 *   模型必须拿到真实数值才能回答「今天热不热」，所以**必须带数字**。
 *
 * 输出形如：
 * - `"18~25°C 降水概率10%"`
 * - `"18~25°C"`（API 缺降水概率字段时）
 * - `"最高25°C"` / `"最低18°C"`（只有一端温度时）
 * - `null`（快照为空，或三个字段全缺）
 *
 * **绝不编造**：字段缺失就是缺失，不补 0、不猜「晴/多云」——Open-Meteo 这条请求
 * 只取温度与降水概率，没有天气状况（晴/云/雨）字段，凭空写「晴」等于让模型
 * 把假信息复述给用户。要加状况文字得先在 [com.companion.chat.data.environment.WeatherCache]
 * 的请求里加 `weather_code` 并做映射，不在本任务范围内。
 *
 * 用空格分隔而不是中文逗号：`UserMetaMarker` 的字段以逗号切分，
 * 段内逗号会被 sanitize 成空格，与其被改写不如一开始就用空格。
 */
object WeatherSummary {

    /** 降水概率的措辞。纯客观描述，不替模型下「要带伞」的结论（那是它该干的事）。 */
    fun line(snapshot: WeatherSnapshot?): String? {
        if (snapshot == null) return null

        val temp = when {
            snapshot.tempMin != null && snapshot.tempMax != null -> "${snapshot.tempMin}~${snapshot.tempMax}°C"
            snapshot.tempMax != null -> "最高${snapshot.tempMax}°C"
            snapshot.tempMin != null -> "最低${snapshot.tempMin}°C"
            else -> null
        }
        val rain = snapshot.precipProbabilityMax?.let { "降水概率${it}%" }

        val parts = listOfNotNull(temp, rain)
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}