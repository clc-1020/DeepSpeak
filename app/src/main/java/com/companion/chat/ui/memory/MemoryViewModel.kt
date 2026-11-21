package com.companion.chat.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.companion.chat.CompanionApp
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记忆管理界面的数据接线（UI 层）。任务 2.7（界面部分）
 * 仅对现有 Room DAO 做读写；记忆提取 / 语义去重 / 召回等逻辑不在此处（归属逻辑部分）。
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as CompanionApp).database
    private val shortDao = db.memoryShortDao()
    private val longDao = db.memoryLongDao()
    private val dailyDao = db.memoryDailyDao()

    val shortMemories: StateFlow<List<MemoryShortEntity>> =
        shortDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val longMemories: StateFlow<List<MemoryLongEntity>> =
        longDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dailyLogs: StateFlow<List<MemoryDailyEntity>> =
        dailyDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 短期 ----
    fun addShort(content: String) = viewModelScope.launch {
        if (content.isNotBlank()) {
            shortDao.insert(MemoryShortEntity(content = content.trim(), timestamp = System.currentTimeMillis()))
        }
    }

    fun updateShort(item: MemoryShortEntity) = viewModelScope.launch { shortDao.update(item) }

    fun deleteShort(item: MemoryShortEntity) = viewModelScope.launch { shortDao.delete(item) }

    // ---- 长期 ----
    fun addLong(content: String, category: String) = viewModelScope.launch {
        if (content.isNotBlank()) {
            longDao.insert(
                MemoryLongEntity(
                    content = content.trim(),
                    category = category.ifBlank { "fact" },
                    createTime = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateLong(item: MemoryLongEntity) = viewModelScope.launch { longDao.update(item) }

    fun deleteLong(item: MemoryLongEntity) = viewModelScope.launch { longDao.delete(item) }

    // ---- 每日日志 ----
    fun addDaily(date: String, events: String) = viewModelScope.launch {
        if (date.isNotBlank()) dailyDao.insert(MemoryDailyEntity(date = date.trim(), events = events))
    }

    fun updateDaily(item: MemoryDailyEntity) = viewModelScope.launch { dailyDao.update(item) }

    fun deleteDaily(item: MemoryDailyEntity) = viewModelScope.launch { dailyDao.delete(item) }
}
