package com.companion.chat.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.companion.chat.CompanionApp
import com.companion.chat.data.image.ImageLoader
import com.companion.chat.data.image.ImageStore
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatOptions
import com.companion.chat.data.remote.ImageAttachment
import com.companion.chat.data.remote.ProviderPresets
import com.companion.chat.data.remote.StreamEvent
import com.companion.chat.data.remote.UsageInfo
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.ConversationDeletionPolicy
import com.companion.chat.domain.ContextBuilder
import com.companion.chat.domain.emotion.EmotionKaomoji
import com.companion.chat.domain.UserMetaMarker
import com.companion.chat.domain.environment.WeatherSummary
import com.companion.chat.data.environment.LocatedCity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/** 聊天页 UI 状态。任务 1.4 / 1.6 */
data class ChatUiState(
    val messages: List<MessageUiModel> = emptyList(),
    val tokPerSec: Double? = null,
    val cacheHitRatio: Double? = null,
    val contextUsage: Double? = null,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    /** 当前会话 id（会话列表高亮用）。任务 1.7 */
    val currentConversationId: Long? = null,
    /** 正在朗读的消息 id（任务 4.2；null=未朗读）。 */
    val speakingId: Long? = null,
    /** V2.3.6：通知栏语音识别结果，读到后回填输入框（只填不发送）。 */
    val voicePending: String? = null
)

/**
 * 聊天 ViewModel：状态管理 + 多轮上下文 + 流式输出 + 错误处理 + 图片输入。任务 1.4 / 1.6
 * 对接 1.2 ChatApiClient，数据落 Room。任务 1.2 增补：latest_reminder 注入、title 生成、关思考。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContainer = app as CompanionApp
    private val conversationDao = appContainer.database.conversationDao()
    private val messageDao = appContainer.database.messageDao()
    private val appStateDao = appContainer.database.appStateDao()
    private val configRepository = appContainer.configRepository
    private val apiClient = appContainer.chatApiClient
    private val imageLoader = ImageLoader(app)
    private val imageStore = ImageStore(app)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var conversationId: Long? = null

    init {
        prefetchWeather() // V2.1.8：进聊天页先后台拉一次天气，发消息时直接读缓存
        prefetchEmbedding() // 2026-11-21：预热本地向量模型，避免首轮记忆召回现加载（1~2s 卡首字）
        viewModelScope.launch { withContext(Dispatchers.IO) { initConversation() } }
    }

    // ---------- 对外操作 ----------

    /**
     * 发送消息。[imageUris] 为当前轮的图片（本地 Uri，来自相册，UI 负责选图）。
     * 图片只在当前轮发送，历史/重试不回传图片（省 token，且 assistant 不能带图）。
     */
    fun send(text: String, imageUris: List<Uri> = emptyList()) {
        val t = text.trim()
        if (t.isEmpty() && imageUris.isEmpty()) return
        if (_uiState.value.isStreaming) return
        viewModelScope.launch { withContext(Dispatchers.IO) { handleSend(t, imageUris) } }
    }

    /** 失败后重试：复用已保存的最后一条用户消息重新生成（图片消息不支持重试，按纯文本重试）。 */
    fun retry() {
        val convId = conversationId ?: return
        if (_uiState.value.isStreaming) return
        viewModelScope.launch { withContext(Dispatchers.IO) { generateAssistant(convId, emptyList()) } }
    }

    /**
     * 撤回：删除 [messageId] 这条消息**及其之后的全部消息**（含紧随其后的 AI 回答），不可恢复。
     *
     * 只在用户消息上提供（AI 回答给的是「重新生成」）。实现要点：
     * - 消息列表不是 Flow 驱动的（见 [loadConversationIntoUi]），删完必须手动回填一次；
     * - user 消息可能带图，DB 行删掉后要把私有目录里的图片文件一起清掉，否则是孤儿文件；
     * - 若删掉的区间里包含正在朗读的那条，先停止朗读，否则会继续念一段已经不存在的内容。
     * - **[不] 动记忆库**：记忆是全库共享、且只有"事件时间"、没有来源消息 id，
     *   无法把某段对话对应的记忆精确摘出来（详见项目规则），所以撤回只清对话。
     */
    fun truncateFrom(messageId: Long) {
        val convId = conversationId ?: return
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = messageDao.getByConversation(convId)
                val idx = all.indexOfFirst { it.id == messageId }
                if (idx < 0) return@withContext
                val victims = all.subList(idx, all.size).toList()

                // 正在朗读的正好在被删区间 → 先停
                val speaking = _uiState.value.speakingId
                if (speaking != null && victims.any { it.id == speaking }) {
                    runCatching { appContainer.readAloudController.stop() }
                    _uiState.update { it.copy(speakingId = null) }
                }

                // 图片路径先收集再删行（删行后 imagePaths 就拿不到了）
                val imageFiles = victims
                    .filter { it.role == MessageRole.USER }
                    .flatMap { it.imagePaths.orEmpty().split("\n") }
                    .filter { it.isNotBlank() }

                victims.forEach { runCatching { messageDao.delete(it) } }
                // ImageStore 只提供 save，没有 delete：直接删文件，失败无所谓
                imageFiles.forEach { p -> runCatching { File(p).delete() } }

                loadConversationIntoUi(convId)
            }
        }
    }

    /**
     * 重新生成：删掉**最后一条** assistant 回答，按它前面那条 user 消息重新问一次。
     *
     * 只对最后一条有意义 —— 中间的某条重生成会让它后面的对话失去上下文锚点（"孤儿回答"），
     * 所以 UI 只在最后一条 assistant 气泡上显示「重新生成」。
     * 复用 [generateAssistant]：它本来就取"最后一条 user 消息"+ 其余历史重建 prompt，
     * 所以删掉最后一条 assistant 之后调用它，等价于「把这句话重新说一遍」。
     */
    fun regenerateLast() {
        val convId = conversationId ?: return
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = messageDao.getByConversation(convId)
                val last = all.lastOrNull() ?: return@withContext
                if (last.role != MessageRole.ASSISTANT) return@withContext
                runCatching { messageDao.delete(last) }
                loadConversationIntoUi(convId)
                generateAssistant(convId, emptyList())
            }
        }
    }

    /**
     * 对话朗读（任务 4.2）：点消息喇叭 → 整段朗读；再次点同一条则停止。
     * 仅已完成的 assistant 消息提供（流式临时消息 id<0，UI 不显示喇叭）。
     * 朗读期间持 WakeLock，念完/失败自动释放（见 [ReadAloudController]）。
     */
    fun readAloud(messageId: Long, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (_uiState.value.speakingId == messageId) {
            appContainer.readAloudController.stop()
            _uiState.update { it.copy(speakingId = null) }
            return
        }
        // 切到另一条：先停当前朗读（避免旧音频继续、新音频在 Mutex 后排队），再播新的
        appContainer.readAloudController.stop()
        viewModelScope.launch {
            _uiState.update { it.copy(speakingId = messageId) }
            try {
                appContainer.readAloudController.speak(t)
            } finally {
                _uiState.update { st ->
                    if (st.speakingId == messageId) st.copy(speakingId = null) else st
                }
            }
        }
    }

    /**
     * 新建会话。若当前会话还是空的（一条消息都没发），直接复用、不再新建，避免连点产生一串空会话。
     * 旧会话不会被删除（仍在库里，可从会话列表切回）。任务 1.8
     */
    fun newChat() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = conversationId
                if (current != null && messageDao.countByConversation(current) == 0) {
                    _uiState.value = ChatUiState(currentConversationId = current)
                    return@withContext
                }
                val id = conversationDao.insert(
                    ConversationEntity(title = DEFAULT_TITLE, createTime = now(), lastMessageTime = now())
                )
                conversationId = id
                _uiState.value = ChatUiState(currentConversationId = id)
            }
        }
    }

    /** 切换到指定历史会话（会话列表点击用）：加载该会话历史到界面。任务 1.8 */
    fun switchTo(id: Long) {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (conversationDao.getById(id) == null) return@withContext
                loadConversationIntoUi(id)
            }
        }
    }

    /**
     * 删除会话。删除的是**当前会话**时自动切到最近的一个；一个都不剩则新建空会话。
     * 删除非当前会话时当前界面不动。任务 1.8
     */
    fun deleteConversation(id: Long) {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val current = conversationId
                conversationDao.deleteById(id)
                when (val action = ConversationDeletionPolicy.decide(
                    deletedId = id,
                    currentId = current,
                    remainingOrderedIds = conversationDao.getAll().map { it.id }
                )) {
                    is ConversationDeletionPolicy.Action.SwitchTo -> loadConversationIntoUi(action.id)
                    ConversationDeletionPolicy.Action.CreateNew -> {
                        val newId = conversationDao.insert(
                            ConversationEntity(title = DEFAULT_TITLE, createTime = now(), lastMessageTime = now())
                        )
                        loadConversationIntoUi(newId)
                    }
                    ConversationDeletionPolicy.Action.None -> Unit
                }
            }
        }
    }

    /** 把某会话的历史加载到 UI 状态（switchTo / deleteConversation 复用）。任务 1.8 */
    private suspend fun loadConversationIntoUi(id: Long) {
        val history = messageDao.getByConversation(id)
        val lastAssistantUsage = history.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.tokPerSec != null
        }
        conversationId = id
        _uiState.value = ChatUiState(
            messages = history.map(::toUi),
            tokPerSec = lastAssistantUsage?.tokPerSec,
            cacheHitRatio = lastAssistantUsage?.cacheHit,
            contextUsage = lastAssistantUsage?.contextUsage,
            currentConversationId = id
        )
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * V2.3.6：读一次通知栏语音结果（读到即删，避免重复回填）。
     * 只更新 [ChatUiState.voicePending]，由 ChatScreen 回填输入框，绝不自动发送。
     */
    fun consumeVoicePendingText() {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = appStateDao.get(AppStateEntity.KEY_VOICE_PENDING_TEXT)
            appStateDao.delete(AppStateEntity.KEY_VOICE_PENDING_TEXT)
            val text = raw?.trim()?.takeIf { it.isNotEmpty() }
            withContext(Dispatchers.Main) { _uiState.update { it.copy(voicePending = text) } }
        }
    }

    /** V2.3.6：回填完成后清空标记，避免下次 onResume 重复回填同一文本。 */
    fun voicePendingConsumed() {
        _uiState.update { it.copy(voicePending = null) }
    }

    // ---------- 内部实现 ----------

    private suspend fun initConversation() {
        val all = conversationDao.getAll()
        // 优先打开最近一个「有消息」的会话：避免启动时停在一个空白的「新对话」，
        // 让用户误以为历史消息没了（历史都在别的会话里）。
        val existing = all.firstOrNull { messageDao.countByConversation(it.id) > 0 } ?: all.firstOrNull()
        val id = existing?.id ?: conversationDao.insert(
            ConversationEntity(title = DEFAULT_TITLE, createTime = now(), lastMessageTime = now())
        )
        conversationId = id
        val history = messageDao.getByConversation(id)
        val lastAssistantUsage = history.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.tokPerSec != null
        }
        _uiState.value = ChatUiState(
            messages = history.map(::toUi),
            tokPerSec = lastAssistantUsage?.tokPerSec,
            cacheHitRatio = lastAssistantUsage?.cacheHit,
            contextUsage = lastAssistantUsage?.contextUsage,
            currentConversationId = id
        )
    }

    private suspend fun handleSend(text: String, imageUris: List<Uri>) {
        val convId = conversationId ?: return
        val cfg = configRepository.load()

        // 读取图片 -> base64 data URL
        val images = ArrayList<ImageAttachment>()
        val imagePaths = ArrayList<String>()
        for (uri in imageUris) {
            val result = imageLoader.load(uri)
            val err = result.exceptionOrNull()
            if (err != null) {
                _uiState.update { it.copy(errorMessage = "图片读取失败：${err.message ?: "未知错误"}") }
                return
            }
            images.add(result.getOrThrow())
            // 拷贝一份到 App 私有目录，供聊天气泡展示（相册 Uri 授权重启后失效）
            imageStore.save(uri)?.let { imagePaths.add(it) }
        }

        // 本轮 latest_reminder（与 user 消息成对持久化，历史回传时一起带上）
        // 仅 DeepSeek 兼容端点注入：别家 Chat Completions 收到未知 role 可能 400
        val reminder = if (cfg.latestReminderEnabled &&
            ProviderPresets.isDeepSeekCompatible(cfg.providerId, cfg.baseUrl)
        ) {
            UserMetaMarker.build(
                location = manualCityName() ?: "",
                weather = weatherLine()
            )
        } else {
            null
        }

        // 存库用原始文本（纯图片消息文本为空，展示层再补占位）
        val userEntity = MessageEntity(
            conversationId = convId,
            role = MessageRole.USER,
            content = text,
            timestamp = now(),
            reminder = reminder,
            imagePaths = imagePaths.takeIf { it.isNotEmpty() }?.joinToString("\n")
        )
        val userId = messageDao.insert(userEntity)
        conversationDao.updateLastMessageTime(convId, now())
        // 记录本次用户交互时间（任务 3.4 决策闸门「冷却 < 2h」用）
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_LAST_INTERACTION, now().toString()))
        // 任务 3.3：若用户这句是「提醒/叫醒」指令且含时间 → 设精确闹钟（到点投队列，3.5 送达）。
        // 已在本 IO 协程内，失败静默不影响主对话。任务 3.7：记录排钟结果，便于真机排查。
        runCatching { appContainer.reminderService.scheduleFromText(text) }
            .onSuccess { r ->
                if (r != null) android.util.Log.d(
                    "Reminder", "已排钟：${r.taskDesc} @ ${r.triggerAtMillis} (req=${r.requestCode})"
                )
            }
            .onFailure { android.util.Log.w("Reminder", "排钟失败", it) }
        _uiState.update {
            it.copy(
                messages = it.messages + MessageUiModel(
                    id = userId,
                    content = text,
                    isUser = true,
                    timestamp = userEntity.timestamp,
                    imagePaths = imagePaths
                ),
                errorMessage = null
            )
        }
        generateAssistant(convId, images)
    }

    /**
     * 取当天天气短语（V2.1.8，并入 latest_reminder 第 3 段）。
     *
     * **只用 [com.companion.chat.data.environment.WeatherCache.peek]，绝不联网**：
     * 曾经这里调 `load()`（缓存缺失时会现场发 HTTP，readTimeout 20s），真机上表现为
     * **点发送后消息迟迟不上屏**。发送路径不能有任何网络等待。
     *
     * 缓存由 [prefetchWeather]（进聊天页时）/ 整点环境检查在后台填充；没有新鲜缓存
     * 就返回 null → 注记里不带天气，模型按 CAPABILITIES 约定说"这会儿拿不到"。
     */
    private suspend fun weatherLine(): String? {
        return runCatching {
            WeatherSummary.line(appContainer.weatherCache.peek(java.time.LocalDate.now()))
        }.getOrNull()
    }

    /** 手动城市名（设置页搜到的）；自动定位模式没有城市名 → null。 */
    private suspend fun manualCityName(): String? =
        runCatching { appContainer.locationCache.manualCityName() }.getOrNull()

    /**
     * 后台预热天气缓存（V2.1.8）：进聊天页时异步拉一次，之后发消息就能秒读缓存。
     *
     * 全程 `runCatching` + IO：没有定位权限、没有网络、解析失败都不影响任何功能。
     * 1.9.3.1：改走 [WeatherCache.loadWithRetry]——Open-Meteo 在本机网络下会间歇性超时，
     * 只拉一次时那一次若撞上超时，缓存整天都是空的，发送路径读不到天气。
     */
    private fun prefetchWeather() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val point = when (val city = appContainer.locationCache.current()) {
                    is LocatedCity.Manual -> city.point
                    is LocatedCity.Auto -> city.point
                }
                appContainer.weatherCache.loadWithRetry(point, java.time.LocalDate.now())
            }
        }
    }

    /**
     * 后台预热本地 embedding 模型（2026-11-21）。
     *
     * 不预热的话，**首次记忆召回**会在发送路径上现场加载模型（1~2 秒），表现为「点了发送没反应」。
     * 全程 runCatching：模型缺失/加载失败 → 之后短期记忆不注入（长期/日记照常），不影响对话。
     */
    private fun prefetchEmbedding() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { appContainer.ensureEmbeddingService() }
        }
    }

    private suspend fun generateAssistant(convId: Long, images: List<ImageAttachment>) {
        val baseConfig = configRepository.load()

        // 带图 → 用识图模型；deepseek-flash 原生视觉，识图模型留空则复用主模型
        val effectiveConfig: ApiConfig = if (images.isNotEmpty()) {
            baseConfig.copy(model = baseConfig.visionModel.ifBlank { baseConfig.model })
        } else {
            baseConfig
        }

        // 取本轮 user（最后一条 user 消息）与历史（不含本轮）
        val history = messageDao.getByConversation(convId)
        val currentUser = history.lastOrNull { it.role == MessageRole.USER } ?: return
        val past = history.filter { it.id != currentUser.id }

        // 2026-11-21：主对话记忆注入。短期走向量相似度筛（阈值 0.45），长期/日记是稳定内容。
        // 预算取上下文窗口的 12%（≈ token），由 ContextTrimmer 之外的独立额度控制 ——
        // 记忆块自己先按预算裁好，避免把对话历史挤掉。没相关记忆时返回 null，一个字都不加。
        val memoryDiag = StringBuilder()
        // 1.9.4.0：情绪回忆按需筛选（冷却 + 情绪加权 + boost tiebreaker），注入后逐条记账。
        val emotionalCandidates = runCatching {
            val st = appContainer.moodService.currentEmotionState()
            val dominant = st.hers.emotions.entries.maxByOrNull { it.value }?.key
            appContainer.emotionalMemoryService.injectable(dominant)
        }.getOrDefault(emptyList())
        val memoryBlock = runCatching {
            appContainer.memoryContextProvider.build(
                userText = currentUser.content,
                budgetChars = (effectiveConfig.contextWindow * MEMORY_CONTEXT_RATIO).toInt(),
                emotional = emotionalCandidates.map { it.second },
                // 2026-11-21：把每条候选的相似度分数打出来 —— 调阈值时只能靠这个，不能靠猜
                onDiagnostics = { memoryDiag.append(it) }
            )
        }.getOrNull()
        // 1.9.4.0：只有候选正文真的进了本轮记忆块才算"被回忆"，逐条刷新注入时刻 + 每日一次 boost。
        if (!memoryBlock.isNullOrBlank()) {
            runCatching {
                emotionalCandidates.filter { memoryBlock.contains(it.second) }.forEach {
                    appContainer.emotionalMemoryService.recall(it.first)
                }
            }
        }
        // W 级（真机 D 级常被 ROM 吞）：用来核对「这轮到底注入了多少字记忆」+ 每条分数，
        // 0 = 没有相关记忆（正常，说明省 token 路径生效）
        android.util.Log.w("MemoryCtx", "本轮注入记忆块 ${memoryBlock?.length ?: 0} 字｜$memoryDiag")
        // 2026-11-21 情绪系统 P1：尾部状态块（情绪 → 记忆）。它只改"她怎么说"，所以永远注入，
        // 不参与记忆块的 12% 预算；自身长度由 EmotionPhrase 控制在 ~70 字。
        val emotionBlock = runCatching { appContainer.moodService.renderEmotionBlock() }.getOrNull()
        android.util.Log.w(
            "EmotionCtx",
            "本轮注入情绪块 ${emotionBlock?.length ?: 0} 字｜${emotionBlock?.replace("\n", " ／ ") ?: "-"}"
        )

        val context = ContextBuilder.build(
            persona = configRepository.getPersona(),
            longMemoryDigest = null, // 长期记忆由 memoryBlock 一起给出（不重复注入）
            history = past,
            currentUserText = currentUser.content,
            currentReminder = currentUser.reminder,
            contextWindow = effectiveConfig.contextWindow,
            capabilityNote = ContextBuilder.CAPABILITIES,
            memoryBlock = memoryBlock,
            emotionBlock = emotionBlock,
            // P3：关系阶段（生疏/熟悉/亲近）—— 变化极慢，放稳定前缀
            relationshipNote = runCatching {
                com.companion.chat.domain.emotion.RelationshipStage.promptHint(
                    appContainer.moodService.currentRelationshipStage()
                )
            }.getOrNull()
        )

        // 新建会话首条且标题仍是默认值 → 本次请求顺带生成标题（仅 DeepSeek 兼容端点支持 title）
        val needTitle = effectiveConfig.autoTitleEnabled &&
            ProviderPresets.isDeepSeekCompatible(effectiveConfig.providerId, effectiveConfig.baseUrl) &&
            needsTitle(convId)

        val tempId = -System.currentTimeMillis()
        _uiState.update {
            it.copy(
                messages = it.messages + MessageUiModel(tempId, "", false, now()),
                isStreaming = true,
                errorMessage = null,
                canRetry = false
            )
        }

        val builder = StringBuilder()
        var usage: UsageInfo? = null
        var lastError: Throwable? = null
        var success = false
        var attempt = 0
        var titleText: String? = null
        // title 为 chat/completions 顶层参数，个别情况可能被拒(400)：命中则去掉 title 重试一次
        var titleEnabledNow = needTitle
        val maxAttempts = 2

        while (attempt < maxAttempts && !success) {
            attempt++
            builder.setLength(0)
            lastError = null
            updateAssistantText(tempId, "")
            try {
                val opts = ChatOptions(
                    titleEnabled = titleEnabledNow,
                    thinkingEnabled = effectiveConfig.thinkingEnabled
                )
                apiClient.streamChat(effectiveConfig, context, images = images, opts = opts).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> {
                            builder.append(ev.text)
                            updateAssistantText(tempId, builder.toString())
                        }
                        is StreamEvent.Title -> {
                            titleText = ev.text
                            conversationDao.updateTitle(convId, ev.text)
                        }
                        is StreamEvent.Completed -> {
                            usage = ev.usage
                            success = true
                        }
                        is StreamEvent.Failed -> {
                            lastError = ev.error
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
            if (!success) {
                val msg = lastError?.message ?: ""
                val title400 = titleEnabledNow && msg.contains("HTTP 400")
                if (title400) titleEnabledNow = false // 下一轮不带 title
                val canRetry = attempt < maxAttempts && (isRetryable(lastError) || title400)
                if (canRetry) {
                    delay(RETRY_DELAY_MS)
                } else {
                    break
                }
            }
        }

        if (success) {
            // 2026-11-21 情绪系统 P1：主导情绪 ≥0.5 时给回复加一个颜文字（代码追加，不劳模型）。
            // 存库前追加 → 历史回传也带 → 她会逐渐学会这个风格；末尾去重防"双份"。
            val emotionNow = runCatching { appContainer.moodService.currentEmotionState() }.getOrNull()
            val finalText = EmotionKaomoji.append(builder.toString(), emotionNow?.hers?.emotions.orEmpty())
            val assistant = MessageEntity(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = finalText,
                timestamp = now(),
                tokPerSec = usage?.tokPerSec,
                cacheHit = usage?.cacheHitRatio,
                contextUsage = usage?.contextUsage
            )
            val realId = messageDao.insert(assistant)
            conversationDao.updateLastMessageTime(convId, now())
            // 标题兜底：请求了 title 但没拿到（拒答/错误）→ 用本轮 user 前缀
            if (needTitle && titleText.isNullOrBlank()) {
                conversationDao.updateTitle(convId, fallbackTitle(currentUser.content))
            }
            _uiState.update { st ->
                st.copy(
                    messages = st.messages.map {
                        if (it.id == tempId) MessageUiModel(realId, finalText, false, assistant.timestamp) else it
                    },
                    isStreaming = false,
                    tokPerSec = usage?.tokPerSec ?: st.tokPerSec,
                    cacheHitRatio = usage?.cacheHitRatio ?: st.cacheHitRatio,
                    contextUsage = usage?.contextUsage ?: st.contextUsage,
                    canRetry = false
                )
            }
            // 任务 2.2/2.3：主对话成功后，后台异步提取 + 语义去重落库（独立调用、失败静默，不影响主对话）
            viewModelScope.launch(Dispatchers.IO) {
                // 1.9.4.0：用户明确嫌烦（"别提/烦不烦"…）→ 最近被想起的情绪记忆扣分 + 冷却。
                runCatching { appContainer.emotionalMemoryService.onDislike(currentUser.content) }
                runCatching {
                    val recent = messageDao.getRecent(convId, MEMORY_RECENT_MESSAGES)
                    val ex = appContainer.memoryExtractor.extract(effectiveConfig, recent).getOrNull()
                        ?: return@runCatching
                    if (!ex.isEmpty) {
                        // 语义去重需要向量：懒加载本地模型（首次较慢，之后复用；失败自动退回精确去重）
                        appContainer.ensureEmbeddingService()
                        appContainer.memoryExtractor.store(ex)
                        // 2026-11-20：写入后立刻做容量淘汰（攒满 50 清 20，保护新增/活跃任务）。
                        // 放这里而不是定时扫：用户要的是「攒满即触发」，且半夜删记忆无法解释。
                        runCatching { appContainer.memoryCapacityEnforcer.enforceOnce() }
                    }
                }
                // 任务 5.1：每 5 轮更新一次 mood（独立调用，失败保持原 mood，绝不影响主对话）
                runCatching {
                    val recentForMood = messageDao.getRecent(convId, MOOD_RECENT_MESSAGES)
                    val attempted = appContainer.moodService.onTurnCompleted(effectiveConfig, recentForMood)
                    if (attempted) {
                        val mood = appContainer.moodService.currentMood()
                        val reason = appContainer.moodService.currentReason()
                        android.util.Log.d(
                            "Mood",
                            "第${appContainer.moodService.turnCount()}轮 触发mood更新 -> mood=$mood reason=$reason"
                        )
                    }
                }
                // V2.2.3 追问任务创建：**追问链唯一的入口**（2026-11-20 接入）。
                // 之前这个类写好了、单测 10 条全绿、CompanionApp 也 lazy 出来了，但全项目零调用
                // → reminders 表永远建不出 followup 行 → V2.2.2/2.2.3/2.2.4/2.2.5 四个任务的功能
                // 在真机上一次都不会发生（不会追问、通知上的「已完成/再等等」永不出现）。
                //
                // 放在主对话成功之后、fire-and-forget：内部自带三道闸门
                // （① 意图闸门零成本 → ② anyPendingFollowup 预检 → ③ 实例 Mutex 权威复查），
                // 普通闲聊连模型都不会调，失败也不影响主对话。
                runCatching {
                    val recentForFollowup = messageDao.getRecent(convId, FOLLOWUP_RECENT_MESSAGES)
                    appContainer.followupTaskExtractor.extractAndSchedule(effectiveConfig, recentForFollowup)
                }.onFailure {
                    android.util.Log.w("Followup", "追问任务提取失败（不影响主对话）", it)
                }
            }
        } else {
            _uiState.update { st ->
                st.copy(
                    messages = st.messages.filterNot { it.id == tempId },
                    isStreaming = false,
                    errorMessage = friendlyError(lastError),
                    canRetry = true
                )
            }
        }
    }

    private suspend fun needsTitle(convId: Long): Boolean {
        val conv = conversationDao.getById(convId) ?: return false
        return conv.title.isBlank() || conv.title == DEFAULT_TITLE
    }

    private fun fallbackTitle(text: String): String {
        val t = text.trim().ifBlank { "图片对话" }
        return if (t.length <= TITLE_MAX_LEN) t else t.substring(0, TITLE_MAX_LEN)
    }

    private fun updateAssistantText(tempId: Long, text: String) {
        _uiState.update { st ->
            st.copy(messages = st.messages.map {
                if (it.id == tempId) it.copy(content = text) else it
            })
        }
    }

    private fun toUi(e: MessageEntity): MessageUiModel {
        val paths = e.imagePaths?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        return MessageUiModel(
            id = e.id,
            content = if (e.content.isBlank() && e.role == MessageRole.USER && paths.isEmpty()) "[图片]" else e.content,
            isUser = e.role == MessageRole.USER,
            timestamp = e.timestamp,
            imagePaths = paths
        )
    }

    private fun isRetryable(e: Throwable?): Boolean {
        if (e !is IOException) return false
        val msg = e.message ?: ""
        return !msg.contains("HTTP 4")
    }

    private fun friendlyError(e: Throwable?): String {
        val msg = e?.message ?: ""
        return when {
            e == null -> "发送失败，请重试"
            msg.contains("HTTP 401") -> "API Key 无效或未授权（401）"
            msg.contains("HTTP 403") -> "无权限访问该模型（403）"
            msg.contains("HTTP 429") -> "请求太频繁，被限流（429），稍后再试"
            msg.contains("HTTP 400") -> "请求被拒绝（400），可能是模型不支持图片或参数错误"
            msg.contains("HTTP 4") -> "请求被拒绝：$msg"
            msg.contains("HTTP 5") -> "服务器错误，稍后重试"
            e is UnknownHostException -> "网络不可用，检查网络后重试"
            else -> "发送失败：${msg.ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val RETRY_DELAY_MS = 1500L
        private const val DEFAULT_TITLE = "新对话"
        private const val TITLE_MAX_LEN = 12

        /** 记忆提取喂给模型的最近消息条数（3 轮 ≈ 3 user + 3 assistant）。 */
        private const val MEMORY_RECENT_MESSAGES = 6
        /** mood 判定用最近消息数（≈4 轮，任务 5.1）。 */
        private const val MOOD_RECENT_MESSAGES = 8
        /** 追问任务提取喂给模型的最近消息条数（V2.2.3）。
         *
         * 与 mood 同量级即可：意图闸门只看**最后一条 user**，转录给 8 条足够让模型判断
         * 「用户是在让我监督他」还是普通闲聊；条数越多越贵，这条链路每轮都可能触发一次。
         */
        private const val FOLLOWUP_RECENT_MESSAGES = 8

        /**
         * 本轮记忆块（短期/长期/日记）允许占用的上下文比例（2026-11-21）。
         *
         * 12%：按默认窗口 8192 算约 980 字，够「4 条短期 + 12 条长期 + 3 天日记」；
         * 调高会把对话历史挤掉，调低则长期记忆经常被砍到空 —— 两个方向都写进过事故。
         */
        private const val MEMORY_CONTEXT_RATIO = 0.12
    }
}
