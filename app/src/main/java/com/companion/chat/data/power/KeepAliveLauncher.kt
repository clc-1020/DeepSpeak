package com.companion.chat.data.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.companion.chat.domain.power.IntentSpec
import com.companion.chat.domain.power.KeepAliveIntents
import com.companion.chat.domain.power.KeepAliveTarget
import com.companion.chat.domain.power.RomFamily

/**
 * 保活跳转执行器（任务 6.2）：把 [KeepAliveIntents] 的候选链变成真正的 `startActivity`。
 *
 * 逐条尝试：指定组件的厂商页 → 隐式 action → 最后一定回落到应用详情页。
 * 每条都 try/catch（厂商页可能未导出 / 改名 / 被系统拦截），失败就试下一条，绝不抛给调用方。
 *
 * 属 Android 运行时（编译验证）；候选表与识别逻辑在 `domain/power` 用 JVM 单测覆盖。
 */
class KeepAliveLauncher(
    private val context: Context,
    rom: RomFamily? = null,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {

    private val packageName: String = context.packageName

    /** 识别（或注入）的 ROM 家族。 */
    val rom: RomFamily = rom ?: runCatching {
        RomFamily.detect(Build.MANUFACTURER, Build.BRAND)
    }.getOrDefault(RomFamily.GENERIC)

    /** 本应用当前是否已在电池优化白名单里。 */
    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /** 该目标的候选链（纯数据，便于 UI 展示「手动路径」）。 */
    fun specs(target: KeepAliveTarget): List<IntentSpec> =
        KeepAliveIntents.specsFor(target, rom, packageName)

    /**
     * 跳到该目标对应的系统设置页。
     * @return 实际成功跳转的那条 spec；全部失败返回 null（理论上不会，因为最后有应用详情页）。
     */
    fun open(target: KeepAliveTarget): IntentSpec? =
        KeepAliveIntents.openLoop(
            specs = specs(target),
            attempt = { spec -> tryStart(spec) },
            log = { line -> log("[$target] $line") }
        )

    /** 单条候选：**独立的** try/catch，失败返回 false 让上层继续下一条。 */
    private fun tryStart(spec: IntentSpec): Boolean = try {
        val intent = toIntent(spec)
        // 隐式 action 先确认有接收者（显式组件直接试，兼容 resolveActivity 返回 null 的厂商页）
        if (!spec.isExplicit && intent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (e: Exception) {
        log("startActivity 失败（${e.javaClass.simpleName}: ${e.message}）：${describe(spec)}")
        false
    }

    private fun toIntent(spec: IntentSpec): Intent {
        val intent = if (spec.isExplicit) {
            Intent().setClassName(spec.packageName!!, spec.className!!)
        } else {
            Intent(spec.action)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        spec.data?.let { intent.data = Uri.parse(it) }
        return intent
    }

    private fun describe(spec: IntentSpec): String =
        if (spec.isExplicit) "${spec.packageName}/${spec.className}" else spec.action + (spec.data?.let { " ($it)" } ?: "")

    private companion object {
        const val TAG = "KeepAlive"
    }
}