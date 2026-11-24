package com.companion.chat.data.proactive

import com.companion.chat.data.local.dao.ConversationDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.domain.proactive.ProactiveFallbackTopics
import com.companion.chat.data.local.dao.MessageDao
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.ChatOptions
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.environment.EnvironmentHooks
import com.companion.chat.domain.mood.MoodTone
import com.companion.chat.domain.emotion.EmotionKaomoji
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveMessageGenerator
import com.companion.chat.domain.proactive.ProactivePromptBuilder

/**
 * 用大模型生成主动消息文本。任务 3.5
 *
 * 复用 [ChatApiClient.callJson]（`jsonMode=false` → 返回纯文本，不强制 JSON）。
 * 钩子内容：记忆类 → 取记忆 content；纯文本类 → 原文。最近 3 条对话取最近活跃会话。
 * 未配 Key / 调用失败 → null（保留待发重试）。
 */
class LlmProactiveMessageGenerator(
    private val apiClient: ChatApiClient,
    private val memoryShortDao: MemoryShortDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val configProvider: () -> ApiConfig,
    private val personaProvider: () -> String,
    private val moodProvider: suspend () -> String = { "normal" },
    private val recentLimit: Int = 3,
    /** 情绪系统 P1：主动消息也要带颜文字，所以这里拿「她此刻的情绪」。默认空 = 不加。 */
    private val emotionProvider: suspend () -> Map<String, Double> = { emptyMap() },
    /** 1.9.4.0：长期记忆兜底话题需要读正文；null 时长记忆兜底不发（旧装配零改动）。 */
    private val memoryLongDao: MemoryLongDao? = null,
) : ProactiveMessageGenerator {

    override suspend fun generate(pending: PendingMessageEntity): String? {
        val cfg = configProvider()
        if (cfg.apiKey.isBlank()) return null
        val content = PendingMessageContent.decode(pending.content)
        val hook = resolveHookText(pending) ?: return null
        val recent = recentMessagesFor(content)
        val persona = personaProvider()
        // 任务 5.2：当前 mood 写进 system（语气调整），未知/异常一律回落 normal
        val mood = runCatching { moodProvider() }.getOrNull()
        val normalizedMood = MoodTone.normalize(mood)
        android.util.Log.d(
            "Mood",
            "主动消息生成 mood=$normalizedMood label=${MoodTone.label(normalizedMood)}"
        )
        // P2：情绪既改语气也改长度，所以先取（含「想你」的累积）
        val emotion = runCatching { emotionProvider() }.getOrDefault(emptyMap())
        val system = ProactivePromptBuilder.system(persona, normalizedMood, emotion)
        val user = ProactivePromptBuilder.build(hook, recent)
        return apiClient.callJson(
            config = cfg,
            messages = listOf(ChatMessageDto("user", user)),
            schemaHint = system,
            opts = ChatOptions(jsonMode = false, thinkingEnabled = false)
        ).getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            EmotionKaomoji.append(text, emotion)
        }
    }

    /** 解析钩子文本：记忆 → content；纯文本 → 原文；环境钩子 → 泛化短语；解析不到 → null。 */
    private suspend fun resolveHookText(pending: PendingMessageEntity): String? =
        when (val c = PendingMessageContent.decode(pending.content)) {
            is PendingMessageContent.PlainText -> c.text
            is PendingMessageContent.Memory -> memoryShortDao.getById(c.memoryId)?.content
            // 1.9.4.0：长期记忆兜底，读正文包成 hook（同 longMemoryHook 口径，搭不上就随便聊别硬提）
            is PendingMessageContent.LongMemory ->
                memoryLongDao?.getById(c.memoryId)?.content?.let { ProactiveFallbackTopics.longMemoryHook(it) }
            is PendingMessageContent.Instruction -> c.task
            // 环境钩子只带 stableId，正文走泛化措辞（计划书 §3.4：不拼数值、不读天气缓存）
            is PendingMessageContent.Environment -> EnvironmentHooks.genericText(c.stableId)
            // 节日同理只带名字：正文交给模型现写，不在这里拼死板祝福语（V2.1.7）
            is PendingMessageContent.Festival -> "今天是${c.name}，想一句自然又有温度的祝福"
            // 追问链（V2.2.4）：只给任务原文 + 语气要求。
            // 审查 S2：**不要**把 attempt 写进提示——(a) 「这是第 n 次」与「不要提第几次」自相矛盾，
            // 模型容易照抄序号；(b) attempt 是入队时的序号，这条若在勿扰区被 reschedule 顺延，
            // 送达时链可能已推进到第 4、5 轮，用户会看到对不上的次数。
            is PendingMessageContent.Followup ->
                "任务：${c.task}。用户可能还没开始，用一句话轻轻问一句进度，" +
                    "不要重复原话、不要说教、不要提「第几次」也不要提「又在催」"
        }

    private suspend fun recentMessagesFor(content: PendingMessageContent) = try {
        // 修复（1.13.2）「定时提醒念成另一段对话的内容」：
        // 指令提醒 / 追问是**点状**提醒，自身已带明确话题（hook），不能再叠加「最近活跃会话」的历史。
        // 之前无条件取 conversationDao.getAll().firstOrNull()（按 lastMessageTime 排序的最近会话），
        // 当用户刚在另一段对话 A 里聊过、A 成为最近活跃会话时，提醒会被模型写成 A 的延续 ——
        // 实测表现为「到点播报/通知出来的不是提醒本身，而是 A 对话的内容」。
        if (!shouldIncludeRecentContext(content)) {
            emptyList()
        } else {
            val convId = conversationDao.getAll().firstOrNull()?.id
            if (convId == null) emptyList() else messageDao.getRecent(convId, recentLimit)
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /**
         * 是否给该类型消息叠加「最近对话」上下文（纯函数，可 JVM 单测）。
         *
         * 指令提醒 / 追问自带明确话题、且是独立事件，**不叠加**，避免被无关的最近会话带跑。
         * 其余（记忆话题 / 长期记忆 / 纯文本闲聊 / 环境 / 节日）保留上下文，用于自然衔接与去重。
         */
        internal fun shouldIncludeRecentContext(content: PendingMessageContent): Boolean =
            content !is PendingMessageContent.Instruction &&
                content !is PendingMessageContent.Followup
    }
}