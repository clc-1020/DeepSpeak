package com.companion.chat.data.proactive

import com.companion.chat.domain.settings.DndSettings

/**
 * 用户设置 → 闸门配置的**唯一**映射点（任务 7.1 / 7.2）。
 *
 * 抽出来是为了让「设置项到底管不管用」可 JVM 单测——之前这段逻辑内联在 `CompanionApp` 的
 * lambda 里，只能靠肉眼确认。
 *
 * 四条映射规则（对应设置页文案）：
 *  1. 主动消息总开关关 → `enabled = false` → 闸门直接 `MASTER_OFF`
 *  2. 每日上限原样透传（并在 [ConfigRepository] 侧 clamp 0..10；0 表示完全不自发）
 *  3. **勿扰是否拦通知** = 勿扰开关 **且** 「区间内不发通知」开关：
 *     - 两个都开 → 区间内自发消息不发（连通知都不弹）
 *     - 只开勿扰、关掉「不发通知」→ 闸门**不拦**，通知照发，仅语音静音（4.3 的语义）
 *  4. 勿扰区间起止时间原样透传（闸门内部支持跨午夜）
 */
object ProactiveSettingsMapping {

    fun gateConfig(
        proactiveEnabled: Boolean,
        dailyLimit: Int,
        dnd: DndSettings
    ): ProactiveGateConfig = ProactiveGateConfig(
        enabled = proactiveEnabled,
        dailyLimit = dailyLimit.coerceIn(0, 10),
        dndEnabled = dnd.enabled && dnd.suppressNotification,
        dndStartMinute = dnd.startMinute,
        dndEndMinute = dnd.endMinute
    )
}