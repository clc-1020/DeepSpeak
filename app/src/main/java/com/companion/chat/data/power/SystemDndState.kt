package com.companion.chat.data.power

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.companion.chat.domain.power.RomFamily
import com.companion.chat.domain.settings.DndSettings

/**
 * 读取系统侧运行态（任务 6.1）：亮灭屏、系统勿扰、电池白名单，并组装 [DiagnosticSnapshot]。
 *
 * 只做「读」——判据本身在纯逻辑 [WakeLockPolicyEvaluator]，这样策略可 JVM 单测，
 * Android API 只留在这层薄壳里。
 */
class SystemDndState(
    private val context: Context,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {

    /** 屏幕是否亮着（用户在用）。 */
    fun isScreenOn(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(true)

    /** 系统勿扰（中断过滤器不是 ALL）是否开启。 */
    fun isSystemDndActive(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            false
        } else {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter
            filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 当前是否该继续持锁（把系统态 + 用户勿扰设置一起判）。 */
    fun decision(dndSettings: DndSettings, incomingCall: Boolean = false): WakeLockDecision =
        WakeLockPolicyEvaluator.evaluate(
            screenOn = isScreenOn(),
            incomingCall = incomingCall,
            systemDndActive = isSystemDndActive(),
            dndSettings = dndSettings,
            log = log
        )

    /** 诊断快照（设置页/日志用）。 */
    fun snapshot(dndSettings: DndSettings, incomingCall: Boolean = false): DiagnosticSnapshot {
        val screenOn = isScreenOn()
        val systemDnd = isSystemDndActive()
        val decision = WakeLockPolicyEvaluator.evaluate(
            screenOn = screenOn,
            incomingCall = incomingCall,
            systemDndActive = systemDnd,
            dndSettings = dndSettings,
            log = log
        )
        return DiagnosticSnapshot(
            rom = runCatching { RomFamily.detect(Build.MANUFACTURER, Build.BRAND) }
                .getOrDefault(RomFamily.GENERIC),
            screenOn = screenOn,
            incomingCall = incomingCall,
            systemDndActive = systemDnd,
            allowChatInDnd = dndSettings.allowChatInDnd,
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
            shouldKeepWakeLock = decision.shouldKeep,
            policyState = decision.state
        )
    }

    private companion object {
        const val TAG = "SystemDndState"
    }
}