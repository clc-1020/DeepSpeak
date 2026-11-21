package com.companion.chat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ===========================================================================
// 深色（用户逐条指定；已装真机确认）
// ===========================================================================

/**
 * 聊天区背景 / 全部 TopAppBar / AI 气泡底色 —— 同一个黑，顶栏与内容连成一片（用户不要分隔线）。
 *
 * 1.9.1：用户把底色从 #0F0F0F 改成**纯黑 #000000**。
 * ⚠️ 顶栏 surface 也跟这个值走 —— 只改背景不改顶栏的话，聊天区(#000) 和顶栏(#0F0F0F)
 * 之间会出一条能看见的接缝，跟"一片黑"的要求冲突，所以两者必须同值。
 */
val ChatBackground = Color(0xFF000000)

/** 黑底上的主文字色。中性灰，不带 M3 默认的紫色调。 */
val ChatOnBackground = Color(0xFFE6E6E6)

/** 用户提问气泡底。 */
val BubbleUser = Color(0xFF333333)

/** 用户气泡文字 —— 压在 #333333 上 10.5:1。 */
val BubbleUserText = Color(0xFFF0F0F0)

/** AI 回复气泡：与背景同色，视觉上"气泡消失"（方案 A）。结构/留白/280dp/操作行全保留。 */
val BubbleAi = ChatBackground

/** AI 气泡文字 —— 压在纯黑上 18.6:1。 */
val BubbleAiText = Color(0xFFE6E6E6)

/**
 * accent 的"暗铺底"（方案 B）= #7797EC 叠 20% 在背景黑上的等效不透明色。
 * 用在选中态容器：会话抽屉选中行、记忆页 FAB。
 * 用不透明值而不是 .copy(alpha)：底下还有 Divider 和 sheet 图层，半透明灰度不确定。
 */
val AccentSurface = Color(0xFF232A3B)

/**
 * 深色主色。
 * 1.9.1：主色由 #7797EC 换成**用户指定的 #416EFB**（发送键等图形填充）。
 * ⚠️ 白字压 #416EFB 上 3.91:1 —— 做图形/图标够（3:1），做小号正文勉强，
 *    所以 onPrimary 保持深色、文字用 accentText 的深蓝。
 */
val ChatAccent = Color(0xFF416EFB)

// ===========================================================================
// 浅色（1.9.0，用户 2026-11-21 给的三条映射 + 逐项补值）
//
// 映射：#0F0F0F→#FFFFFF，主色 #7797EC→#467CF6（图形）/ #2F63D8（文字）。
// ===========================================================================

val LightBackground = Color(0xFFFFFFFF)

/**
 * 浅色正文 / AI 气泡文字。
 *
 * 1.9.1：用户实测后把 #ECF2FE（压白底仅 1.06:1，几乎不可见）换成 **#191919**（13.8:1）。
 */
val LightOnBackground = Color(0xFF191919)

/** 浅色用户气泡底 —— 用户指定 #ECF2FE（压白底 1.12:1，很淡，靠对齐+深字辨识）。 */
val LightBubbleUser = Color(0xFFECF2FE)

/** 浅色用户气泡文字 —— 用户指定 #061012（压 #ECF2FE 17.1:1）。 */
val LightBubbleUserText = Color(0xFF061012)

/** 浅色 accent 铺底 = #467CF6 叠 12% 在白底上的等效值。 */
val LightAccentSurface = Color(0xFFE8EFFE)

/** 浅色 accent·**图形填充**（开关轨道、发送键底、光标、勿扰/喇叭图标）。白底 3.84:1，作图形够。 */
val LightAccentFill = Color(0xFF467CF6)

/** 浅色 accent·**文字**（保存、胶囊、Tab 选中文字）。白底 5.39:1，达标。M3 primary 走这个。 */
val LightAccentText = Color(0xFF2F63D8)

// ===========================================================================
// 聊天色板：一套「按深浅自动切换」的颜色
//
// 为什么要有它：气泡 / 选中态 / accent 这些颜色在 M3 配色表里没有对应槽位
// （primary 只有一个，但浅色需要「图形一个蓝、文字另一个蓝」）。写死在调用点
// 就得每处 if(深色)…else… —— 现在 5 处，将来每加一个色又要补判断，漏一处就
// 固定成深色值。改成在 Theme 选一次盘子，调用点只写 palette.xxx。
// ===========================================================================

@Immutable
data class ChatColors(
    /** 用户气泡底 */
    val bubbleUser: Color,
    /** 用户气泡文字 */
    val bubbleUserText: Color,
    /** AI 气泡底（与背景同色时视觉无框） */
    val bubbleAi: Color,
    /** AI 气泡文字 */
    val bubbleAiText: Color,
    /** 选中态铺底（抽屉选中行 / FAB 底） */
    val accentSurface: Color,
    /** accent 图形填充（开关轨道 / 发送键 / 光标 / 图标） */
    val accentFill: Color,
    /** accent 文字（抽屉选中标题） */
    val accentText: Color
)

val DarkChatColors = ChatColors(
    bubbleUser = BubbleUser,
    bubbleUserText = BubbleUserText,
    bubbleAi = BubbleAi,
    bubbleAiText = BubbleAiText,
    accentSurface = AccentSurface,
    accentFill = ChatAccent,
    accentText = ChatAccent
)

val LightChatColors = ChatColors(
    bubbleUser = LightBubbleUser,
    bubbleUserText = LightBubbleUserText,
    bubbleAi = LightBackground,
    bubbleAiText = LightOnBackground,
    accentSurface = LightAccentSurface,
    accentFill = LightAccentFill,
    accentText = LightAccentText
)

val LocalChatColors = staticCompositionLocalOf { DarkChatColors }