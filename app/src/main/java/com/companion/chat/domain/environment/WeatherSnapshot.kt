package com.companion.chat.domain.environment

import java.time.LocalDate

/**
 * 一天天气快照。任务 V2.1.2（定义）/ V2.1.1（填充）。
 *
 * 纯数据类，**不带任何 Android 依赖**：`data/` 侧的 `WeatherFetcher` 把网络响应解析成这个对象，
 * `EnvironmentRuleEngine` 只认它。字段全为可空是刻意的——免费天气 API 常缺降水概率或最低温，
 * 缺的字段对应的规则直接跳过，绝不用 0 或默认值冒充真实数据（否则会凭空触发「添衣」「带伞」）。
 *
 * @param date 快照对应的日期（不是抓取时刻：抓取可能在深夜，拿到的是次日的预报）
 * @param tempMax 当日最高温（摄氏度），缺失为 null
 * @param tempMin 当日最低温（摄氏度），缺失为 null
 * @param precipProbabilityMax 当日最大降水概率（百分比 0..100），缺失为 null
 */
data class WeatherSnapshot(
    val date: LocalDate,
    val tempMax: Int? = null,
    val tempMin: Int? = null,
    val precipProbabilityMax: Int? = null,
)