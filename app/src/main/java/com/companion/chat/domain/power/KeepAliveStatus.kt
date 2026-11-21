package com.companion.chat.domain.power

/**
 * 保活状态快照（任务 6.2）：设置页 / 首次启动引导读取这一个对象即可。
 *
 * @param rom 识别到的 ROM 家族（决定文案与跳转页）
 * @param ignoringBatteryOptimizations 当前是否已在电池优化白名单
 * @param guideShown 是否已经引导过（持久化在 `app_state[keepalive_guide_shown]`）
 * @param rootAutoHandleSupported root「自动处理」按钮是否可用（第一版恒 false，占位）
 */
data class KeepAliveStatus(
    val rom: RomFamily,
    val ignoringBatteryOptimizations: Boolean,
    val guideShown: Boolean,
    val rootAutoHandleSupported: Boolean = KeepAliveGuide.rootAutoHandleSupported()
) {
    /** 是否该自动弹引导（等价于 [KeepAliveGuide.shouldShowGuide]）。 */
    val needsGuide: Boolean
        get() = KeepAliveGuide.shouldShowGuide(guideShown, ignoringBatteryOptimizations)
}