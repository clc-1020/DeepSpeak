package com.companion.chat.data.daily

import com.companion.chat.data.local.dao.ConversationDao
import com.companion.chat.data.local.dao.DailyTaskDao
import com.companion.chat.data.local.dao.MessageDao
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.ChatOptions
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.chat.ChatMode
import com.companion.chat.domain.daily.DailyTaskSchedule
import kotlinx.coroutines.withTimeout

/**
 * 每日任务执行（2026-11-23）：到点后「生成 → 写入专属会话 → 通知 → 可选语音」。
 *
 * 设计要点：
 * - **每个任务固定一个专属会话**（[com.companion.chat.data.local.entity.DailyTaskEntity.conversationId]），
 *   首次创建、之后复用；会话被用户删了下次自动重建。
 * - **今天覆盖昨天**：生成**成功后**先 `deleteByConversation` 清空旧消息，再写今天这一问一答
 *   → 上下文恒定、省 token，界面也只显示今天（用户已确认接受"翻不回更早"）。
 *   生成失败**不清**旧数据（不能因为一次没网把上次内容也毁掉）。
 * - **模式**：`companion`（陪聊）把用户人设当 system，只借她的语气回答；**不写记忆、不改情绪**。
 *   `normal`（对话）干净问答，不带人设。
 * - 到点照弹，不看 App 勿扰（时间用户自选）。
 *
 * **不占「每日主动消息」次数**：本类直接生成并写库、发通知，**不经过** ProactiveGate /
 * PendingMessageQueue / ProactiveSendCounter（那套的每日上限只管自发闲聊/环境/节日），所以
 * 每日任务永远不会消耗、也不会被消耗「今日已发自发消息」的额度。
 */
class DailyTaskRunner(
    private val apiClient: ChatApiClient,
    private val configProvider: () -> ApiConfig,
    private val personaProvider: () -> String,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dailyTaskDao: DailyTaskDao,
    private val notifier: DailyTaskNotifier,
    /** 语音输出（注入以便测试/解耦）：一般接 [com.companion.chat.data.tts.ReadAloudController.speak]。 */
    private val speak: suspend (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** @return true=已成功生成并送达；false=任务不存在/已禁用/未配 Key/生成失败（供 Worker 决定是否重试）。 */
    suspend fun run(taskId: Long): Boolean {
        val task = dailyTaskDao.getById(taskId) ?: return false
        if (!task.enabled) return false

        val cfg = configProvider()
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank() || cfg.model.isBlank()) return false

        val companion = ChatMode.fromStorage(task.mode) == ChatMode.COMPANION
        val system = if (companion) personaProvider().trim() else ""

        // 非流式一次性生成（后台任务不需要逐字）。thinking 关，快且省。
        val reply = apiClient.callJson(
            config = cfg,
            messages = listOf(ChatMessageDto("user", task.text)),
            schemaHint = system,
            opts = ChatOptions(jsonMode = false, thinkingEnabled = false)
        ).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return false

        val ts = now()
        val convId = ensureConversation(task, ts)

        // 覆盖昨天：只留今天这一轮（生成成功后才清，失败上面已 return 保留旧数据）
        messageDao.deleteByConversation(convId)
        messageDao.insert(
            MessageEntity(
                conversationId = convId,
                role = MessageRole.USER,
                content = task.text,
                timestamp = ts
            )
        )
        messageDao.insert(
            MessageEntity(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = reply,
                timestamp = ts + 1
            )
        )
        conversationDao.updateLastMessageTime(convId, ts)

        runCatching {
            notifier.show(
                title = task.text,
                body = reply,
                conversationId = convId,
                notificationId = DailyTaskNotifier.notificationIdOf(task.id)
            )
        }

        if (task.voiceEnabled) {
            runCatching { withTimeout(SPEAK_TIMEOUT_MS) { speak(reply) } }
        }
        return true
    }

    private suspend fun ensureConversation(task: com.companion.chat.data.local.entity.DailyTaskEntity, ts: Long): Long {
        task.conversationId?.let { cid ->
            if (conversationDao.getById(cid) != null) return cid
        }
        val id = conversationDao.insert(
            ConversationEntity(
                title = DailyTaskSchedule.titleFor(task.text),
                createTime = ts,
                lastMessageTime = ts
            )
        )
        runCatching { dailyTaskDao.setConversationId(task.id, id) }
        return id
    }

    private companion object {
        /** 语音朗读上限 5 分钟（外层 Worker 还持 5 分钟 WakeLock；异常即结束，不阻断已发出的通知）。 */
        const val SPEAK_TIMEOUT_MS = 5 * 60_000L
    }
}
