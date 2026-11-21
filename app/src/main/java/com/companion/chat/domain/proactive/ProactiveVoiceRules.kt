package com.companion.chat.domain.proactive

import java.time.ZoneId

/**
 * 主动消息「是否自动念语音」的纯规则。任务 4.3（可 JVM 单测）
 *
 * 需同时满足：
 *  - 用户开启「主动消息自动语音」开关；
 *  - 当前**不在勿扰区间**（勿扰关闭则不受限）。
 *
 * 注意：勿扰只拦「语音」，通知仍会弹（可能发通知、但不自动念）。
 */
object ProactiveVoiceRules {

    fun shouldSpeak(
        nowMillis: Long,
        voiceEnabled: Boolean,
        dndEnabled: Boolean,
        dndStartMinute: Int = 23 * 60,
        dndEndMinute: Int = 7 * 60,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!voiceEnabled) return false
        if (dndEnabled && ProactiveGateRules.inQuietHours(nowMillis, dndStartMinute, dndEndMinute, zone)) {
            return false
        }
        return true
    }
}