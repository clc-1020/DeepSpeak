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

    private val appRef = app as CompanionApp
    private val db = appRef.database
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
    // 2026-11-22：手动添加也走语义去重（算向量 + 近似合并），不再裸插——否则手动项无向量、无法召回/去重。
    fun addShort(content: String) = viewModelScope.launch {
        if (content.isNotBlank()) {
            appRef.memoryDeduplicator.upsertShort(content.trim())
        }
    }

    fun updateShort(item: MemoryShortEntity) = viewModelScope.launch { shortDao.update(item) }

    fun deleteShort(item: MemoryShortEntity) = viewModelScope.launch { shortDao.delete(item) }

    // ---- 长期 ----
    // 2026-11-22：手动添加走语义去重（跨类别比较 + 算向量），修「手动加了 fact，之后 preference 同义却不合并」。
    fun addLong(content: String, category: String) = viewModelScope.launch {
        if (content.isNotBlank()) {
            appRef.memoryDeduplicator.upsertLong(content.trim(), category.ifBlank { "fact" })
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
