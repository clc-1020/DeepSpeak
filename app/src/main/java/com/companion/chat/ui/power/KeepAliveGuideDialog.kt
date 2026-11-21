package com.companion.chat.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.companion.chat.domain.power.KeepAliveGuide
import com.companion.chat.domain.power.KeepAliveStatus
import com.companion.chat.domain.power.KeepAliveTarget

/**
 * 保活引导弹窗（任务 6.2 的 UI 落地 + 任务 7.1「开机自启引导」入口）。
 *
 * 首次启动（`KeepAliveService.shouldShowGuide()`）弹一次：白名单 / 自启动 / 后台限制三步，
 * 每步一个「去设置」按钮（`KeepAliveLauncher.open(target)`，跳不到就落应用详情页）。
 * 「我知道了」→ `markGuideShown()`，之后不再自动弹；设置页仍保留入口。
 */
@Composable
fun KeepAliveGuideDialog(
    status: KeepAliveStatus?,
    onOpenTarget: (KeepAliveTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val rom = status?.rom
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("让陪聊在后台稳定运行") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "国产系统锁屏后会冻结后台应用，主动消息可能发不出来。下面三步都设置一下最稳：",
                    style = MaterialTheme.typography.bodyMedium
                )
                KeepAliveTarget.GUIDE_ORDER.forEach { target ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(KeepAliveGuide.title(target), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (rom == null) "" else KeepAliveGuide.description(target, rom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val alreadyOk = target == KeepAliveTarget.BATTERY_WHITELIST &&
                            status?.ignoringBatteryOptimizations == true
                        if (alreadyOk) {
                            // 已在白名单时系统「申请白名单」Intent 在 MIUI 上无任何反应，直接给结论文字
                            Text(
                                "✓ 已加入白名单，无需再设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            OutlinedButton(
                                onClick = { onOpenTarget(target) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("去设置")
                            }
                        }
                    }
                }
                if (status != null && !status.rootAutoHandleSupported) {
                    Text(
                        "root 自动处理暂未提供，请手动设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
        dismissButton = {
            Row { TextButton(onClick = onDismiss) { Text("稍后") } }
        }
    )
}