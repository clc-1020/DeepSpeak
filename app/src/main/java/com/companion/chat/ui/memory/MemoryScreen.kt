package com.companion.chat.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private fun fmtDateTime(ts: Long): String = dateTimeFmt.format(Date(ts))
private fun today(): String = dateFmt.format(Date())

/**
 * 记忆正文清理：日记/部分记忆把内容存成了 JSON 数组字符串（形如 `["...","..."]`），
 * 直接显示会露出 `[" ]` 原始括号很丑。这里把数组拆成多行纯文本；非数组原样返回。
 */
private fun prettyMemory(raw: String): String {
    val t = raw.trim()
    if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) {
        return runCatching {
            val arr = org.json.JSONArray(t)
            if (arr.length() == 0) t
            else (0 until arr.length()).joinToString("\n") { arr.optString(it) }
        }.getOrDefault(t)
    }
    return t
}

/** 记忆管理界面（短期 / 长期 / 日记）。任务 2.7（UI）；2026-11-23 改成行式列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    vm: MemoryViewModel = viewModel()
) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("短期", "长期", "日记")

    val shortList by vm.shortMemories.collectAsState()
    val longList by vm.longMemories.collectAsState()
    val dailyList by vm.dailyLogs.collectAsState()

    // 弹窗状态
    var shortDialog by remember { mutableStateOf<MemoryShortEntity?>(null) }
    var showShortAdd by remember { mutableStateOf(false) }
    var longDialog by remember { mutableStateOf<MemoryLongEntity?>(null) }
    var showLongAdd by remember { mutableStateOf(false) }
    var dailyDialog by remember { mutableStateOf<MemoryDailyEntity?>(null) }
    var showDailyAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            val palette = LocalChatColors.current
            FloatingActionButton(
                onClick = {
                    when (tab) {
                        0 -> showShortAdd = true
                        1 -> showLongAdd = true
                        else -> showDailyAdd = true
                    }
                },
                containerColor = palette.accentSurface,
                contentColor = palette.accentText
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { idx, t ->
                    Tab(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        text = { Text(t) }
                    )
                }
            }
            when (tab) {
                0 -> ShortList(
                    items = shortList,
                    onEdit = { shortDialog = it },
                    onDelete = { vm.deleteShort(it) }
                )
                1 -> LongList(
                    items = longList,
                    onEdit = { longDialog = it },
                    onDelete = { vm.deleteLong(it) }
                )
                else -> DailyList(
                    items = dailyList,
                    onEdit = { dailyDialog = it },
                    onDelete = { vm.deleteDaily(it) }
                )
            }
        }
    }

    // ---- 弹窗 ----
    if (showShortAdd || shortDialog != null) {
        val editing = shortDialog
        ShortDialog(
            initial = editing,
            onDismiss = { showShortAdd = false; shortDialog = null },
            onConfirm = { content ->
                if (editing != null) vm.updateShort(editing.copy(content = content))
                else vm.addShort(content)
                showShortAdd = false; shortDialog = null
            }
        )
    }
    if (showLongAdd || longDialog != null) {
        val editing = longDialog
        LongDialog(
            initial = editing,
            onDismiss = { showLongAdd = false; longDialog = null },
            onConfirm = { content, category ->
                if (editing != null) vm.updateLong(editing.copy(content = content, category = category))
                else vm.addLong(content, category)
                showLongAdd = false; longDialog = null
            }
        )
    }
    if (showDailyAdd || dailyDialog != null) {
        val editing = dailyDialog
        DailyDialog(
            initial = editing,
            onDismiss = { showDailyAdd = false; dailyDialog = null },
            onConfirm = { date, events ->
                if (editing != null) vm.updateDaily(editing.copy(date = date, events = events))
                else vm.addDaily(date, events)
                showDailyAdd = false; dailyDialog = null
            }
        )
    }
}

@Composable
private fun ShortList(
    items: List<MemoryShortEntity>,
    onEdit: (MemoryShortEntity) -> Unit,
    onDelete: (MemoryShortEntity) -> Unit
) {
    if (items.isEmpty()) { EmptyHint("还没有短期记忆"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            MemoryRow(
                title = prettyMemory(item.content),
                subtitle = "${fmtDateTime(item.timestamp)} · 权重 %.1f · 淡忘 %.1f".format(item.weight, item.fadeScore),
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
private fun LongList(
    items: List<MemoryLongEntity>,
    onEdit: (MemoryLongEntity) -> Unit,
    onDelete: (MemoryLongEntity) -> Unit
) {
    if (items.isEmpty()) { EmptyHint("还没有长期记忆"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            MemoryRow(
                title = prettyMemory(item.content),
                subtitle = "[${item.category}] ${fmtDateTime(item.createTime)}",
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
private fun DailyList(
    items: List<MemoryDailyEntity>,
    onEdit: (MemoryDailyEntity) -> Unit,
    onDelete: (MemoryDailyEntity) -> Unit
) {
    if (items.isEmpty()) { EmptyHint("还没有日记"); return }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            MemoryRow(
                title = prettyMemory(item.events).ifBlank { "（空）" },
                subtitle = item.date,
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

/**
 * 记忆行（2026-11-23 按 DeepSeek 行式重做）：
 * 去掉厚重灰卡片，改成扁平行——正文 2 行 + 小灰字副信息 + 右侧两个小号操作图标。
 * 点一下整行：淡蓝胶囊高亮 + 细边框，图标转为 accent 色（提示"这行被选中了"）。
 */
@Composable
private fun MemoryRow(
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalChatColors.current
    var selected by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) palette.accentSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val iconTint = if (selected) palette.accentText else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, palette.accentText.copy(alpha = 0.45f), shape)
                else Modifier
            )
            .clickable { selected = !selected }
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = iconTint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShortDialog(
    initial: MemoryShortEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增短期记忆" else "编辑短期记忆") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(content) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LongDialog(
    initial: MemoryLongEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "fact") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增长期记忆" else "编辑长期记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容（如「对花生过敏」）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类（fact / preference / allergy…）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(content, category) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DailyDialog(
    initial: MemoryDailyEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var date by remember { mutableStateOf(initial?.date ?: today()) }
    var events by remember { mutableStateOf(initial?.events ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增日记" else "编辑日记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期（yyyy-MM-dd）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = events,
                    onValueChange = { events = it },
                    label = { Text("事件") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(date, events) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
