package com.companion.chat.ui.speech

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语音输入浮层（V2.3.4）。
 *
 * 覆盖在消息区之上（由 `ChatScreen` 的 `Box` 提供位置），三态各一套视觉：
 * - [VoiceState.Recording]：呼吸扩散圆 + 18 柱实时波形 + 「松手发送，上划取消」；
 *   进入取消区时整体切到 error 色、图标变 ✕、文案变「松手取消」。
 * - [VoiceState.Transcribing]：小转圈 + 「识别中…」。
 * - [VoiceState.Idle]：什么都不画。
 *
 * [stateProvider] 用 lambda 而不是直接传值：波形每 60 ms 更新一次，若在 `ChatScreen` 正文里
 * 读 `controller.state`，整块消息列表会被拖着一秒重绘十几次；把读动作收进本函数，
 * 重组就只发生在浮层这个作用域内。
 */
@Composable
fun VoiceInputOverlay(
    stateProvider: () -> VoiceState,
    waveform: List<Float>,
    modifier: Modifier = Modifier
) {
    val state = stateProvider()
    when (state) {
        VoiceState.Idle -> Unit
        is VoiceState.Recording -> RecordingPanel(state = state, waveform = waveform, modifier = modifier)
        VoiceState.Transcribing -> TranscribingPanel(modifier = modifier)
    }
}

@Composable
private fun RecordingPanel(
    state: VoiceState.Recording,
    waveform: List<Float>,
    modifier: Modifier = Modifier
) {
    val cancel = state.cancelZone
    val container = if (cancel) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val onColor = if (cancel) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val barColor = if (cancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    // 呼吸扩散：圆形容器 0.92 ↔ 1.12 倍，1.2 秒一个来回
    val transition = rememberInfiniteTransition(label = "voice-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = container,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(onColor.copy(alpha = 0.10f))
                    )
                    Icon(
                        imageVector = if (cancel) Icons.Default.Close else Icons.Default.Mic,
                        contentDescription = if (cancel) "取消录音" else "正在录音",
                        tint = onColor,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                WaveformBars(waveform = waveform, color = barColor)

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (cancel) "松手取消" else "松手上屏，上划取消",
                    color = onColor,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/** 实时波形：固定 18 柱，高度取拉伸后的 0f..1f，静音也有 8% 高度（不贴底）。 */
@Composable
private fun WaveformBars(waveform: List<Float>, color: Color) {
    val bars = VoiceInputController.WAVEFORM_BARS
    val base = waveform.size - bars
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(42.dp)
    ) {
        repeat(bars) { index ->
            // 读 SnapshotStateList 元素 → 采样变化即可重组这一柱
            val level = waveform.getOrNull(base + index) ?: 0f
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((3f + 33f * level).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.35f + 0.65f * level))
            )
        }
    }
}

@Composable
private fun TranscribingPanel(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "识别中…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}