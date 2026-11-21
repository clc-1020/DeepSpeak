package com.companion.chat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 1.9.0：动态取色（Material You / 壁纸取色）**整体退役**。
 *
 * 之前深浅两边都走 dynamic*ColorScheme，导致「刷黑背景」「钉死主色」根本留不住，
 * 换张壁纸整套配色就漂。现在深浅各一套固定值，颜色只由本文件的配色表决定。
 */
private val DarkColorScheme = darkColorScheme(
    // ⚠️ 本色偏亮 —— 白字压上去只有 2.83:1，所以 onPrimary 必须给深色。
    primary = ChatAccent,
    onPrimary = ChatBackground,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    // background：MainActivity 的 Surface + 各 Scaffold 默认底色
    // surface：所有 TopAppBar / ModalDrawerSheet / AlertDialog 底色
    // 两者同一个黑，顶栏与聊天区才会连成一片（用户不要分隔线）。
    background = ChatBackground,
    onBackground = ChatOnBackground,
    surface = ChatBackground,
    onSurface = ChatOnBackground
)

private val LightColorScheme = lightColorScheme(
    // primary 走「文字那个蓝」(#2F63D8)：M3 里 primary 同时喂 TextButton / 胶囊 /
    // Tab 选中文字 / 指示条，白底 5.39:1 才达标。图形填充另走 ChatColors.accentFill
    // (#467CF6)，见各调用点。
    primary = LightAccentText,
    onPrimary = Color.White,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏跟着底色走：深色黑底、浅色白底。
            window.statusBarColor = (if (darkTheme) ChatBackground else LightBackground).toArgb()
            // 黑底配亮色图标（false）；白底必须配深色图标（true），否则白底白图标看不见。
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // 聊天色板：深浅各一块，在唯一的地方选一次；调用点只写 LocalChatColors.current.xxx
    CompositionLocalProvider(
        LocalChatColors provides if (darkTheme) DarkChatColors else LightChatColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}