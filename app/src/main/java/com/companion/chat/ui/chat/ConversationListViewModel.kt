package com.companion.chat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.companion.chat.CompanionApp
import com.companion.chat.data.local.dao.ConversationSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 会话列表逻辑层（数据）。任务 1.8
 *
 * 只负责逻辑，不含任何 Compose 界面：
 * - [summaries]：所有会话（标题 + 最后一条消息摘要 + 时间），按最近活跃排序，Room 变化自动刷新。
 *
 * UI 线做法：`viewModel<ConversationListViewModel>()` 消费 [summaries]；
 * 点击某项 → ChatViewModel.switchTo(id)；删除 → ChatViewModel.deleteConversation(id)。
 * （删除放 ChatViewModel，是因为要处理「删的是当前会话 → 自动切换/新建」的边界，见 ConversationDeletionPolicy。）
 */
class ConversationListViewModel(app: Application) : AndroidViewModel(app) {

    private val conversationDao = (app as CompanionApp).database.conversationDao()

    val summaries: StateFlow<List<ConversationSummary>> =
        conversationDao.observeSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
