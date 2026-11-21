package com.companion.chat.data.power

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.domain.power.KeepAliveGuide
import com.companion.chat.domain.power.KeepAliveStatus
import com.companion.chat.domain.power.KeepAliveTarget
import com.companion.chat.domain.power.RomFamily

/**
 * 保活引导状态服务（任务 6.2）。
 *
 * 只负责「状态」：ROM、是否已进白名单、引导是否弹过；**真正跳设置页在 [KeepAliveLauncher]**。
 * 拆开是为了让「该不该弹 / 弹什么」可用假 DAO + 假白名单判断做 JVM 单测。
 */
class KeepAliveService(
    private val appStateDao: AppStateDao,
    private val rom: RomFamily,
    private val ignoringBatteryOptimizations: () -> Boolean,
    /** 通知权限是否已授予（任务 6.2 弹窗门槛之一）。 */
    private val notificationsEnabled: () -> Boolean = { true }
) {

    /** 引导顺序（白名单 → 自启动 → 后台限制）。 */
    fun steps(): List<KeepAliveTarget> = KeepAliveTarget.GUIDE_ORDER

    suspend fun status(): KeepAliveStatus = KeepAliveStatus(
        rom = rom,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
        guideShown = guideShown(),
        rootAutoHandleSupported = KeepAliveGuide.rootAutoHandleSupported()
    )

    suspend fun guideShown(): Boolean =
        appStateDao.get(AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN) == "true"

    /** 累计启动次数（每次 `recordLaunchAndShouldGuide` +1）。 */
    suspend fun launchCount(): Int =
        appStateDao.get(AppStateEntity.KEY_APP_LAUNCH_COUNT)?.toIntOrNull() ?: 0

    /** 是否该自动弹引导（不计数，供设置页刷新用）。 */
    suspend fun shouldShowGuide(): Boolean =
        KeepAliveGuide.shouldShowGuide(
            guideShown = guideShown(),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            notificationsEnabled = notificationsEnabled(),
            launchCount = launchCount()
        )

    /**
     * 启动时调用：启动次数 +1，并返回**本次**该不该弹引导。
     *
     * 弹窗时机（任务 6.2 审查改进）：没弹过 + 不在白名单 + **通知权限已授予** + 启动 ≥2 次。
     * 这样首次启动不会和系统通知权限弹窗抢注意力，也不会一进 App 就被打断。
     */
    suspend fun recordLaunchAndShouldGuide(): Boolean {
        val count = launchCount() + 1
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_APP_LAUNCH_COUNT, count.toString()))
        return KeepAliveGuide.shouldShowGuide(
            guideShown = guideShown(),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            notificationsEnabled = notificationsEnabled(),
            launchCount = count
        )
    }

    /** 一次性标记：用户看过/走完引导后调用，之后不再自动弹（设置页入口仍保留）。 */
    suspend fun markGuideShown() {
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN, "true"))
    }

    /** 设置页「重新引导」：清标记，下次启动会再弹一次。 */
    suspend fun resetGuide() {
        appStateDao.delete(AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN)
    }
}