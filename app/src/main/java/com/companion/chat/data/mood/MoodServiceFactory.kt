package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.ChatOptions
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.reminder.FollowupPort

/**
 * MoodService 工厂，负责桥接 ChatApiClient.callJson 到 MoodService 需要的签名。
 * 任务 5.1 —— 修正 B：MoodService callModel 签名不匹配 callJson 的解决方案。
 */
object MoodServiceFactory {

    /**
     * 创建生产 MoodService 实例，注入 ChatApiClient.callJson 的适配包装。
     * [followup] 由 CompanionApp 传真实 FollowupPortImpl；默认 null 仅供旧测试零改动。
     */
    fun create(
        appStateDao: AppStateDao,
        chatClient: ChatApiClient,
        followup: FollowupPort? = null,
        /** P3：关系阶段统计（生产传 MessageDao 的计数）；null = 按"生疏"处理。 */
        relationshipProvider: (suspend () -> com.companion.chat.domain.emotion.RelationshipStage.Stats?)? = null,
        /** P3：情绪事件写回长期记忆；null = 不写。 */
        memoryLongDao: com.companion.chat.data.local.dao.MemoryLongDao? = null,
        /** 1.9.4.0：拟人化情绪记忆写入服务；非 null 时优先于 memoryLongDao 的旧逻辑。 */
        emotionalMemory: EmotionalMemoryService? = null,
    ): MoodService {
        // 适配器：把 List<MessageEntity> 转为 callJson 需要的 List<ChatMessageDto>
        val adapter: suspend (ApiConfig, List<MessageEntity>) -> Result<String> = { config, messages ->
            val dtos = messages.map { ChatMessageDto(it.role, it.content) }
            chatClient.callJson(
                config = config,
                messages = dtos,
                opts = ChatOptions(jsonMode = true) // response_format=json_object
            )
        }
        return MoodService(
            appStateDao = appStateDao,
            callJson = adapter,
            followup = followup,
            relationshipProvider = relationshipProvider,
            memoryLongDao = memoryLongDao,
            emotionalMemory = emotionalMemory,
        )
    }
}
