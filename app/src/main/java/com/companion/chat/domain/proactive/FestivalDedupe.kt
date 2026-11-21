package com.companion.chat.domain.proactive

import java.time.Instant
import java.time.ZoneId

/**
 * 节日祝福去重键的**唯一出处**。任务 V2.1.7。
 *
 * 与 [com.companion.chat.domain.environment.EnvironmentHooks] 同一套路：
 * `ProactiveCheckRunner` 查键、`CompanionApp` 送达回调记账、单测断言，全部调这里。
 * 两边一旦各自拼字符串，「查键用入队那天、记账用送达那天」就会分叉——
 * 节日被勿扰延到次日再送达时，键写到次日，次日检查查不到 → **同一个祝福连发两天**。
 *
 * 注意时间语义：本对象只负责「millis → yyyy-MM-dd」，**用哪个时刻由调用方决定**：
 * - 检查侧用 runner 注入的 `now()`（可钉跨日边界）
 * - 记账侧必须用 `msg.triggerTime`（入队时刻），不能用送达时刻
 */
object FestivalDedupe {

    /** 去重键固定前缀。 */
    const val PREFIX = "fes_sent_"

    /**
     * 完整去重键：`fes_sent_<name>_<dateKey>`。
     *
     * [name] 不能含年份：用户 2026 年存的生日，2027 年同月日还要再命中一次。
     */
    fun key(name: String, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + name + "_" + dateKey(atMillis, zone)

    /** 本地日期键 `yyyy-MM-dd`。 */
    fun dateKey(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toString()
}
