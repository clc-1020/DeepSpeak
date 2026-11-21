package com.companion.chat.domain.power

/**
 * 保活引导的跳转目标（任务 6.2）。
 *
 * - [AUTOSTART]：自启动 / 允许后台自启动（厂商白名单页）
 * - [BATTERY_WHITELIST]：电池优化白名单（AOSP 标准入口，各版本都在）
 * - [BACKGROUND_SETTINGS]：后台运行 / 耗电管理（厂商页，兜底到电池优化列表）
 */
enum class KeepAliveTarget {
    AUTOSTART,
    BATTERY_WHITELIST,
    BACKGROUND_SETTINGS;

    companion object {
        /** 引导页展示顺序（先白名单，再自启动，最后后台运行）。 */
        val GUIDE_ORDER = listOf(BATTERY_WHITELIST, AUTOSTART, BACKGROUND_SETTINGS)
    }
}

/**
 * 一条跳转候选：要么是隐式 action，要么是指定组件（厂商设置页常见）。
 *
 * 纯数据，不含 Android 类型，便于 JVM 单测；真正构造 `Intent` 在 `data/power/KeepAliveLauncher`。
 */
data class IntentSpec(
    val action: String,
    val packageName: String? = null,
    val className: String? = null,
    /** 附在 Intent 上的 data uri（目前只用于 `package:...`）。 */
    val data: String? = null
) {
    /** 指定了包名 + 类名 → 显式组件（厂商页都是这么跳的）。 */
    val isExplicit: Boolean
        get() = !packageName.isNullOrBlank() && !className.isNullOrBlank()

    fun withData(uri: String): IntentSpec = copy(data = uri)

    /** 日志/诊断用的短标签（纯字符串，可单测）。 */
    val label: String
        get() = if (isExplicit) "$packageName/$className" else action + (data?.let { " ($it)" } ?: "")
}

/**
 * 各 ROM 的设置页跳转候选表（任务 6.2）。纯常量 + 纯函数，可 JVM 单测。
 *
 * 说明：厂商页面的组件名会随版本改名，所以每个目标都给**多条候选**，运行时按
 * `PackageManager.resolveActivity` 逐个试，第一个能跳的用；全都不行则回落
 * [appDetails]（应用详情页，任何设备都有）。
 */
object KeepAliveIntents {

    // AOSP 标准入口
    const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val ACTION_APPLICATION_DETAILS_SETTINGS =
        "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** 电池优化白名单：先直接申请（弹系统对话框），再进列表页。 */
    fun batteryWhitelist(packageName: String): List<IntentSpec> = listOf(
        IntentSpec(
            action = ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            data = "package:$packageName"
        ),
        IntentSpec(action = ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    /** 自启动管理页（厂商专属；无对应页时返回空表，调用方回落应用详情）。 */
    fun autostart(rom: RomFamily): List<IntentSpec> = when (rom) {
        RomFamily.MIUI -> listOf(
            spec("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            spec("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        )

        RomFamily.HUAWEI -> listOf(
            spec("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            spec("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            spec("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )

        RomFamily.COLOROS -> listOf(
            spec("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            spec("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            spec("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
        )

        RomFamily.ONEPLUS -> listOf(
            spec("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            spec("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            spec("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        )

        RomFamily.VIVO -> listOf(
            spec("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            spec("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        )

        RomFamily.FLYME -> listOf(
            spec("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            spec("com.meizu.safe", "com.meizu.safe.security.ShowAppInProtectActivity")
        )

        RomFamily.SAMSUNG -> listOf(
            spec("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )

        RomFamily.GENERIC -> emptyList()
    }

    /** 后台运行 / 耗电管理页（厂商专属；同样可回落）。 */
    fun backgroundSettings(rom: RomFamily): List<IntentSpec> = when (rom) {
        RomFamily.MIUI -> listOf(
            spec("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            spec("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )

        RomFamily.HUAWEI -> listOf(
            spec("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            spec("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")
        )

        RomFamily.COLOROS, RomFamily.ONEPLUS -> listOf(
            spec("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            spec("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")
        )

        RomFamily.VIVO -> listOf(
            spec("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        )

        RomFamily.FLYME -> listOf(
            spec("com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity")
        )

        RomFamily.SAMSUNG -> listOf(
            spec("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )

        RomFamily.GENERIC -> emptyList()
    }

    /** 兜底：应用详情页（所有设备都有）。 */
    fun appDetails(packageName: String): IntentSpec =
        IntentSpec(ACTION_APPLICATION_DETAILS_SETTINGS, data = "package:$packageName")

    /** 某个目标的完整候选链（含兜底，最后一条一定是应用详情页）。 */
    fun specsFor(target: KeepAliveTarget, rom: RomFamily, packageName: String): List<IntentSpec> {
        val primary = when (target) {
            KeepAliveTarget.BATTERY_WHITELIST -> batteryWhitelist(packageName)
            KeepAliveTarget.AUTOSTART -> autostart(rom)
            KeepAliveTarget.BACKGROUND_SETTINGS ->
                backgroundSettings(rom) + IntentSpec(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return primary + appDetails(packageName)
    }

    /**
     * 候选链逐条尝试（任务 6.2）。**纯逻辑**：`attempt` 返回 true 表示这条跳转成功。
     *
     * 语义（对应审查项）：
     *  - 每条候选**独立**执行，`attempt` 自己负责 try/catch（[KeepAliveLaunchPolicy] 里说明）；
     *  - 失败**必然**记录日志并继续下一条，不会短路；
     *  - `attempt` 万一抛异常，这里也兜住（不把异常丢给调用方）；
     *  - 整条链跑完仍失败 → 返回 null（调用方兜底应用详情页）。
     */
    fun openLoop(
        specs: List<IntentSpec>,
        attempt: (IntentSpec) -> Boolean,
        log: (String) -> Unit = {}
    ): IntentSpec? {
        if (specs.isEmpty()) {
            log("候选链为空")
            return null
        }
        for ((index, spec) in specs.withIndex()) {
            val ok = try {
                attempt(spec)
            } catch (e: Exception) {
                log("候选 ${index + 1}/${specs.size} 抛异常（${e.javaClass.simpleName}）：${spec.label}")
                false
            }
            if (ok) {
                log("候选 ${index + 1}/${specs.size} 跳转成功：${spec.label}")
                return spec
            }
            log("候选 ${index + 1}/${specs.size} 不可用，继续下一条：${spec.label}")
        }
        log("全部 ${specs.size} 条候选都失败")
        return null
    }

    private fun spec(pkg: String, cls: String) = IntentSpec(
        action = "android.intent.action.MAIN",
        packageName = pkg,
        className = cls
    )
}