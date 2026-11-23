package com.companion.chat.ui.daily

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.companion.chat.CompanionApp
import com.companion.chat.data.local.entity.DailyTaskEntity
import com.companion.chat.domain.chat.ChatMode
import com.companion.chat.worker.DailyTaskWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 每日任务管理页 ViewModel（2026-11-23）。
 *
 * 负责增删改 + 即时排/取闹钟（改完立即生效，不等下次启动）。
 * 时间计算与闹钟编排全在 [com.companion.chat.data.daily.DailyTaskScheduler] / [com.companion.chat.domain.daily.DailyTaskSchedule]。
 */
class DailyTaskViewModel(app: Application) : AndroidViewModel(app) {

    private val appContainer = app as CompanionApp
    private val dao = appContainer.database.dailyTaskDao()
    private val scheduler = appContainer.dailyTaskScheduler

    val tasks: StateFlow<List<DailyTaskEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Android 12+ 是否已授予「精确闹钟」权限（没有就提示用户去开，否则任务可能不准点）。 */
    fun exactAlarmAllowed(): Boolean = appContainer.alarmScheduler.canScheduleExact()

    /**
     * 新增或更新一条任务。[existing] 为 null 表示新增。
     * 保存后立即按最新内容重排闹钟（enabled=false 会自动取消）。
     */
    fun save(
        existing: DailyTaskEntity?,
        text: String,
        hour: Int,
        minute: Int,
        mode: ChatMode,
        voiceEnabled: Boolean,
        enabled: Boolean
    ) {
        val base = existing ?: DailyTaskEntity(
            text = text,
            hour = hour,
            minute = minute,
            mode = mode.storageValue,
            voiceEnabled = voiceEnabled,
            enabled = enabled,
            createdAt = System.currentTimeMillis()
        )
        val entity = base.copy(
            text = text.trim(),
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            mode = mode.storageValue,
            voiceEnabled = voiceEnabled,
            enabled = enabled
        )
        viewModelScope.launch {
            val id = dao.insert(entity)
            val saved = if (entity.id == 0L) entity.copy(id = id) else entity
            scheduler.scheduleTask(saved)
        }
    }

    fun setEnabled(task: DailyTaskEntity, enabled: Boolean) {
        viewModelScope.launch {
            dao.setEnabled(task.id, enabled)
            scheduler.scheduleTask(task.copy(enabled = enabled))
            // 禁用时顺手撤掉「已排好但还没跑」的后台作业，避免刚禁用又弹一条
            if (!enabled) DailyTaskWorker.cancel(appContainer, task.id)
        }
    }

    /**
     * 删除任务（2026-11-23）：
     * 1) 取消该任务的定点闹钟；2) 撤掉已排好但没跑的后台生成作业（"排好的消息"）；
     * 3) 删任务行。**保留**它已生成的专属会话（聊天记录不因删任务而丢；要连会话一起删另行说）。
     */
    fun delete(task: DailyTaskEntity) {
        viewModelScope.launch {
            scheduler.cancelTask(task.id)
            DailyTaskWorker.cancel(appContainer, task.id)
            dao.deleteById(task.id)
        }
    }
}
