package com.companion.chat.ui.daily

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.companion.chat.data.local.entity.DailyTaskEntity
import com.companion.chat.domain.chat.ChatMode

/** 模式选择文案（用户口径：陪聊模式 / 对话模式）。 */
private fun modeLabel(mode: String): String =
    if (ChatMode.fromStorage(mode) == ChatMode.COMPANION) "陪聊模式" else "对话模式"

/** 时间展示 hh:mm。 */
private fun timeText(hour: Int, minute: Int): String =
    (if (hour < 10) "0$hour" else "$hour") + ":" + (if (minute < 10) "0$minute" else "$minute")

/**
 * 每日任务管理页（2026-11-23）。入口：聊天页顶栏【菜单】【设置】【日历】。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyTaskScreen(
    onBack: () -> Unit,
    vm: DailyTaskViewModel = viewModel()
) {
    val tasks by vm.tasks.collectAsState()
    var editorVisible by remember { mutableStateOf(false) }
    var editorTask by remember { mutableStateOf<DailyTaskEntity?>(null) }
    val alarmOk = remember { vm.exactAlarmAllowed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每日任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editorTask = null
                        editorVisible = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "新增任务")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!alarmOk) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "未授予「精确闹钟」权限，每日任务可能不准点。请到系统设置允许「闹钟和提醒」。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("还没有每日任务", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点右上角 + 添加：写一段话、选个时间、选陪聊或对话模式，\n到点 App 会自动让 AI 回答你并弹通知。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tasks) { task ->
                        DailyTaskRow(
                            task = task,
                            onToggle = { vm.setEnabled(task, it) },
                            onEdit = {
                                editorTask = task
                                editorVisible = true
                            },
                            onDelete = { vm.delete(task) }
                        )
                    }
                }
            }
        }
    }

    if (editorVisible) {
        DailyTaskEditor(
            initial = editorTask,
            onDismiss = { editorVisible = false },
            onSave = { text, hour, minute, mode, voice, enabled ->
                vm.save(editorTask, text, hour, minute, mode, voice, enabled)
                editorVisible = false
            }
        )
    }
}

@Composable
private fun DailyTaskRow(
    task: DailyTaskEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeText(task.hour, task.minute),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.text,
                    fontSize = 14.sp,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = modeLabel(task.mode),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.voiceEnabled) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "语音输出",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyTaskEditor(
    initial: DailyTaskEntity?,
    onDismiss: () -> Unit,
    onSave: (text: String, hour: Int, minute: Int, mode: ChatMode, voice: Boolean, enabled: Boolean) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var mode by remember { mutableStateOf(ChatMode.fromStorage(initial?.mode)) }
    var voice by remember { mutableStateOf(initial?.voiceEnabled ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增每日任务" else "编辑每日任务") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("要发给 AI 的内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m ->
                            hour = h
                            minute = m
                        },
                        hour,
                        minute,
                        true
                    ).show()
                }) {
                    Text("时间：${timeText(hour, minute)}")
                }
                Spacer(Modifier.height(4.dp))
                Text("模式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ChatMode.COMPANION,
                        onClick = { mode = ChatMode.COMPANION },
                        label = { Text("陪聊模式") }
                    )
                    FilterChip(
                        selected = mode == ChatMode.NORMAL,
                        onClick = { mode = ChatMode.NORMAL },
                        label = { Text("对话模式") }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语音输出", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = voice, onCheckedChange = { voice = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text, hour, minute, mode, voice, enabled) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
