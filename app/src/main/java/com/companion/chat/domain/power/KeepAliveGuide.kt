package com.companion.chat.domain.power

/**
 * 保活引导的纯逻辑（任务 6.2）：什么时候弹、每步文案、root 自动处理占位。
 *
 * 首次启动判据（[shouldShowGuide]）：
 *  - 还没弹过（`guide_shown != true`）
 *  - 且**当前不在电池优化白名单里**（已在白名单就不打扰）
 *
 * 用户点「已设置」或走完引导 → 调 `KeepAliveService.markGuideShown()` 持久化，不再自动弹；
 * 设置页仍可主动进入（[KeepAliveTarget.GUIDE_ORDER]）。
 */
object KeepAliveGuide {

    /** 每步标题（UI 直接显示，避免各处重复文案）。 */
    fun title(target: KeepAliveTarget): String = when (target) {
        KeepAliveTarget.BATTERY_WHITELIST -> "允许后台运行（电池优化白名单）"
        KeepAliveTarget.AUTOSTART -> "允许自启动"
        KeepAliveTarget.BACKGROUND_SETTINGS -> "关闭后台限制 / 耗电管理"
    }

    /** 每步说明（按 ROM 给具体路径提示）。 */
    fun description(target: KeepAliveTarget, rom: RomFamily): String = when (target) {
        KeepAliveTarget.BATTERY_WHITELIST ->
            "把本应用加入电池优化白名单，系统才不会在息屏后冻结后台任务（主动消息靠它按时发出）。"

        KeepAliveTarget.AUTOSTART -> when (rom) {
            RomFamily.MIUI -> "小米：安全中心 → 应用管理 → 权限 → 自启动，把本应用设为允许。"
            RomFamily.HUAWEI -> "华为/荣耀：手机管家 → 应用启动管理，把本应用设为手动管理并全部允许。"
            RomFamily.COLOROS -> "OPPO/realme：设置 → 应用管理 → 自启动管理，允许本应用自启动。"
            RomFamily.ONEPLUS -> "一加：设置 → 应用 → 自启动管理，允许本应用自启动。"
            RomFamily.VIVO -> "vivo/iQOO：i管家 → 应用管理 → 权限管理 → 自启动，允许本应用。"
            RomFamily.FLYME -> "魅族：手机管家 → 权限管理 → 后台管理，允许本应用后台运行。"
            RomFamily.SAMSUNG -> "三星：设置 → 电池 → 后台使用限制，把本应用加入「永不休眠的应用」。"
            RomFamily.GENERIC -> "在系统设置里找到本应用的自启动/后台启动开关并打开。"
        }

        KeepAliveTarget.BACKGROUND_SETTINGS -> when (rom) {
            RomFamily.MIUI -> "小米：设置 → 应用设置 → 本应用 → 省电策略选「无限制」，并允许后台弹出界面。"
            RomFamily.HUAWEI -> "华为/荣耀：应用启动管理里关闭「自动管理」，否则锁屏后会被清理。"
            RomFamily.COLOROS, RomFamily.ONEPLUS ->
                "ColorOS/OxygenOS：设置 → 电池 → 更多设置，关闭对本应用的智能省电限制。"
            RomFamily.VIVO -> "vivo/iQOO：设置 → 电池 → 后台耗电管理，把本应用设为「允许后台高耗电」。"
            RomFamily.FLYME -> "魅族：设置 → 电量管理 → 后台管理，把本应用设为「允许后台运行」。"
            RomFamily.SAMSUNG -> "三星：设置 → 电池 → 后台使用限制，取消对本应用的休眠。"
            RomFamily.GENERIC -> "在系统电池设置里取消对本应用的后台限制 / 省电优化。"
        }
    }

    /** 第几次启动之后才允许自动弹（避免首次启动就打断用户 / 和通知权限弹窗打架）。 */
    const val MIN_LAUNCH_COUNT = 2

    /**
     * 是否该自动弹引导。
     *
     * 四个条件全部满足才弹（顺序即优先级）：
     *  1. 还没弹过（`guide_shown != true`）
     *  2. 不在电池优化白名单里（已在就不打扰）
     *  3. **通知权限已授予** —— 否则用户会连着遇到两个系统/应用弹窗，先把通知权限处理完
     *  4. 启动次数 ≥ [MIN_LAUNCH_COUNT] —— 首次启动先让用户看到聊天界面
     */
    fun shouldShowGuide(
        guideShown: Boolean,
        ignoringBatteryOptimizations: Boolean,
        notificationsEnabled: Boolean = true,
        launchCount: Int = MIN_LAUNCH_COUNT,
        minLaunchCount: Int = MIN_LAUNCH_COUNT
    ): Boolean =
        !guideShown &&
            !ignoringBatteryOptimizations &&
            notificationsEnabled &&
            launchCount >= minLaunchCount

    /**
     * root「自动处理」按钮：**第一版只占位**（计划 6.2 明确「暂不实现」）。
     * 返回 false 时 UI 应把按钮置灰并提示「暂不支持」。
     */
    fun rootAutoHandleSupported(): Boolean = false
}