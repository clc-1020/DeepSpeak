package com.companion.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.companion.chat.data.local.dao.ConversationSummary
import com.companion.chat.ui.theme.LocalChatColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 把会话按最后消息时间归到「今天 / 昨天 / 7 天内 / 更早」。 */
private fun groupLabel(ts: Long): String {
    val today = LocalDate.now()
    val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(d, today)
    return when {
        days <= 0L -> "今天"
        days == 1L -> "昨天"
        days <= 7L -> "7 天内"
        else -> "更早"
    }
}

/**
 * 左侧历史会话抽屉（2026-11-23 按 DeepSeek 官方样式重做）。
 *
 * - 顶部：「会话」标题 + 新建；无搜索框（用户要求）。
 * - 按 今天/昨天/7 天内/更早 分组，每条**纯文字标题**（不显示预览/时间/分割线）。
 * - 当前主会话：圆角边框 + 淡蓝底 + 蓝字（像 DeepSeek 选中的那条）。
 * - 每条右侧：选中 / 长按该行时出现 ✎ 修改、🗑 删除。
 * - 抽屉为不透明填充面板（把聊天区压暗/挡住），底部一行左「日历」右「记忆」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    summaries: List<ConversationSummary>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit,
    onRename: (Long, String) -> Unit,
    onOpenDailyTasks: () -> Unit,
    onMemoryClick: () -> Unit
) {
    // 长按展开某行的操作图标（不改变当前会话）
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("会话", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "新建对话")
                }
            }
            Divider()

            if (summaries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val groupOrder = listOf("今天", "昨天", "7 天内", "更早")
                val grouped = summaries.groupBy { groupLabel(it.lastMessageTime) }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    groupOrder.forEach { g ->
                        val list = grouped[g] ?: return@forEach
                        item(key = "hdr_$g") {
                            Text(
                                text = g,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(list, key = { it.id }) { s ->
                            val selected = s.id == currentId
                            ConversationRow(
                                title = s.title.ifBlank { "新对话" },
                                selected = selected,
                                showActions = selected || expandedId == s.id,
                                onSelect = {
                                    expandedId = null
                                    onSelect(s.id)
                                },
                                onLongPress = { expandedId = if (expandedId == s.id) null else s.id },
                                onEditClick = { renameTarget = s },
                                onDeleteClick = {
                                    expandedId = null
                                    onDelete(s.id)
                                }
                            )
                        }
                    }
                }
            }

            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DrawerBottomAction(Icons.Outlined.CalendarMonth, "每日任务", onOpenDailyTasks)
                DrawerBottomAction(Icons.Filled.AutoStories, "记忆", onMemoryClick)
            }
        }
    }

    // 重命名弹窗
    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.id, text)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String,
    selected: Boolean,
    showActions: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val palette = LocalChatColors.current
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) palette.accentSurface else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, palette.accentText.copy(alpha = 0.45f), shape)
                else Modifier
            )
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.accentText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showActions) {
            IconButton(onClick = onEditClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名",
                    tint = palette.accentText,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除会话",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerBottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
