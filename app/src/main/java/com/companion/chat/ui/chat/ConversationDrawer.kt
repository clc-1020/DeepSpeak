package com.companion.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.companion.chat.data.local.dao.ConversationSummary
import com.companion.chat.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val drawerTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun fmtDrawerTime(ts: Long): String = drawerTimeFmt.format(Date(ts))

/**
 * 左侧会话抽屉内容。任务 1.8（会话列表 UI）
 * 数据来自 ConversationListViewModel.summaries；点选 → ChatViewModel.switchTo；
 * 删除 → ChatViewModel.deleteConversation；新建 → ChatViewModel.newChat。
 */
@Composable
fun ConversationDrawerContent(
    summaries: List<ConversationSummary>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit
) {
    ModalDrawerSheet {
        val palette = LocalChatColors.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(summaries, key = { it.id }) { s ->
                    val selected = s.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 1.8.2：选中行原来是 secondaryContainer（M3 紫灰块），
                            // 改成 accent 的暗铺底（方案 B）——黑底上是一块暗蓝灰，
                            // 看得出"选中"，但不像霓虹灯。标题同步用 accent 色 + 加粗。
                            .background(if (selected) palette.accentSurface else Color.Transparent)
                            .clickable { onSelect(s.id) }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = s.title.ifBlank { "新对话" },
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                // 1.8.2：选中行标题用 accent，未选中仍是正文色
                                color = if (selected) palette.accentText else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = s.lastMessagePreview.ifBlank { "（空）" },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = fmtDrawerTime(s.lastMessageTime),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDelete(s.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除会话",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Divider()
                }
            }
        }
    }
}
