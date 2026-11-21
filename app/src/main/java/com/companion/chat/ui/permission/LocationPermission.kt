package com.companion.chat.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 粗定位权限（V2.1.1 验收项，UI 侧补的那一步）。
 *
 * **只申请 [Manifest.permission.ACCESS_COARSE_LOCATION]**：天气只要城市级精度（几公里），
 * 粗定位走基站/WiFi，室内也有、耗电几乎为零；FINE 只会多一档系统弹窗、更容易被拒。
 *
 * `AndroidLocationPort` 已有「无权限 → `lastKnown()` 返回 null、绝不抛」的兜底，所以这里
 * 只负责「问」「记住问过没有」「系统不再弹时把人送到设置页」，不负责定位本身。
 *
 * 「问过了」标记放 ui 自己的 SharedPreferences（`ui_permission`）：不塞 `ConfigRepository`
 * （数据层文件），也不塞 Room `app_state`（那是环境层 `EnvironmentKeys` 的跨版本键契约）。
 */
object LocationPermission {

    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    private const val PREFS_NAME = "ui_permission"
    private const val KEY_ASKED = "location_permission_asked"

    /** 当前是否已授予粗定位。 */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 是否已经自动问过一次（用户点「授权定位」不算，那是主动发起的）。 */
    fun askedBefore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * 系统还会不会再弹权限框。
     *
     * Android 11+ 用户选过「不再询问」（或连拒两次）后，`launcher.launch()` 会**静默失败**：
     * 不弹框、也不回调，按钮看起来像没反应，用户在本 App 里永远授不了权。这种情况只能
     * 引导去「应用详情 → 权限」手动开，所以要先判出来。
     *
     * 判据：已授权 → 能（不用弹）；从没问过 → 能；问过之后看
     * `shouldShowRequestPermissionRationale`：true 表示还能弹，false 表示已被彻底拒。
     */
    fun canPrompt(context: Context): Boolean {
        if (isGranted(context)) return true
        if (!askedBefore(context)) return true
        val activity = context.findActivity() ?: return true // 拿不到 Activity 就别拦，交给系统
        return activity.shouldShowRequestPermissionRationale(PERMISSION)
    }

    /** 跳「应用详情」页，让用户手动开权限。 */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** 从 Compose 的 Context（可能是 ContextThemeWrapper）一路摸到 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 权限状态 + 申请动作（供 Compose 侧组合使用）。 */
class LocationPermissionState(
    val granted: Boolean,
    /** true = 系统已不会再弹框，此时点按钮应跳应用设置页（按钮文案据此切换）。 */
    val needsAppSettings: Boolean,
    val request: () -> Unit
)

/**
 * 取一个「粗定位权限状态 + 申请入口」。
 *
 * @param autoRequestOnce true 时：首帧若**未授权且从未问过**，自动弹一次系统授权框。
 *   主界面（`ChatScreen`）用 true；设置页用 false（那里只放按钮）。
 *
 * 两个必须处理的现实情形：
 * 1. **拒绝后不再自动弹**（标记 `location_permission_asked`），否则每次进主界面都弹一次；
 * 2. **从系统设置改完权限回来要刷新**：`ON_RESUME` 时重读一次，否则设置页会一直停在
 *    「未授权」的旧快照上（用户明明已经在系统里开过了）。
 *
 * 注：`LifecycleEventEffect` 在 `lifecycle-runtime-compose:2.6.2` 里**不存在**（2.7.0 才有），
 * 所以这里用等价的 `LifecycleEventObserver`；语义相同，且不需要动 build.gradle。
 */
@Composable
fun rememberLocationPermission(autoRequestOnce: Boolean = false): LocationPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(LocationPermission.isGranted(context)) }
    var needsAppSettings by remember { mutableStateOf(!LocationPermission.canPrompt(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        LocationPermission.markAsked(context)
        needsAppSettings = !result && !LocationPermission.canPrompt(context)
    }

    // 缺陷 A 修复：用户去系统设置里授权、回到前台时刷新状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = LocationPermission.isGranted(context)
                needsAppSettings = !LocationPermission.canPrompt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val request: () -> Unit = remember(granted) {
        {
            when {
                LocationPermission.isGranted(context) -> granted = true
                // 缺陷 B 修复：系统不会再弹框时直接跳应用设置页，否则按钮点了没反应
                !LocationPermission.canPrompt(context) -> LocationPermission.openAppSettings(context)
                else -> {
                    LocationPermission.markAsked(context)
                    launcher.launch(LocationPermission.PERMISSION)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoRequestOnce && !granted && !LocationPermission.askedBefore(context)) {
            request()
        }
    }

    return remember(granted, needsAppSettings, request) {
        LocationPermissionState(granted, needsAppSettings, request)
    }
}