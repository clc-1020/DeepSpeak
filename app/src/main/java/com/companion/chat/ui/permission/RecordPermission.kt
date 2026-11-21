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
 * 录音权限（V2.3.4 语音输入 UI）。
 *
 * 结构与 [LocationPermission] 完全同构：**只申请 `RECORD_AUDIO`**（manifest 已在 V2.3.2 加好），
 * 「问过没有」标记同样落 ui 自己的 SharedPreferences（`ui_permission`），不碰数据层的
 * `ConfigRepository`，也不塞 Room `app_state`（那是环境层 `EnvironmentKeys` 的跨版本键契约）。
 *
 * 与定位权限唯一的语义差别：**不自动弹**。定位是后台天气需要，进主界面先要一次是合理的；
 * 录音是用户主动动作（长按麦克风），后台绝不能偷偷开麦，所以只在长按按下时才 `request()`。
 */
object RecordPermission {

    const val PERMISSION = Manifest.permission.RECORD_AUDIO

    private const val PREFS_NAME = "ui_permission"
    private const val KEY_ASKED = "record_permission_asked"

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun askedBefore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * 系统还会不会再弹权限框。
     *
     * Android 11+ 用户选过「不再询问」后 `launcher.launch()` 会**静默失败**：不弹框也不回调，
     * 长按麦克风看起来像没反应。这种情况只能跳「应用详情 → 权限」手动开，所以先判出来。
     */
    fun canPrompt(context: Context): Boolean {
        if (isGranted(context)) return true
        if (!askedBefore(context)) return true
        val activity = context.findHostActivity() ?: return true
        return activity.shouldShowRequestPermissionRationale(PERMISSION)
    }

    /** 跳「应用详情」页，让用户手动开录音权限。 */
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
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** 录音权限状态 + 申请动作。`needsAppSettings=true` 表示系统已不再弹框，`request()` 会跳设置页。 */
class RecordPermissionState(
    val granted: Boolean,
    val needsAppSettings: Boolean,
    val request: () -> Unit
)

/**
 * 取一个「录音权限状态 + 申请入口」。
 *
 * - **不自动弹**：调用方在长按按下时主动调 `request()`；
 * - `ON_RESUME` 重读一次：用户去系统设置开完权限回来，状态必须立刻变 true，
 *   否则要等下次重组（比如发消息）才恢复。
 */
@Composable
fun rememberRecordPermission(): RecordPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(RecordPermission.isGranted(context)) }
    var needsAppSettings by remember { mutableStateOf(!RecordPermission.canPrompt(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        RecordPermission.markAsked(context)
        needsAppSettings = !result && !RecordPermission.canPrompt(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = RecordPermission.isGranted(context)
                needsAppSettings = !RecordPermission.canPrompt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val request: () -> Unit = remember(granted) {
        {
            when {
                RecordPermission.isGranted(context) -> granted = true
                !RecordPermission.canPrompt(context) -> RecordPermission.openAppSettings(context)
                else -> {
                    RecordPermission.markAsked(context)
                    launcher.launch(RecordPermission.PERMISSION)
                }
            }
        }
    }

    return remember(granted, needsAppSettings, request) {
        RecordPermissionState(granted, needsAppSettings, request)
    }
}