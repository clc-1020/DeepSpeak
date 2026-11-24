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
import com.companion.chat.domain.memory.RecallTriggerDetector
import com.companion.chat.domain.emotion.EmotionKaomoji
import com.companion.chat.domain.UserMetaMarker
import com.companion.chat.domain.chat.ChatMode
import com.companion.chat.domain.chat.Citation
import com.companion.chat.domain.chat.CitationCodec
import com.companion.chat.domain.environment.WeatherSummary
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

    /**
     * 首屏默认会话是否已加载完成（2026-11-23）。
     * 供「从通知点进来要定位到指定会话」用：等它 true 后再切，避免被 [initConversation] 的默认会话覆盖。
     */
    private val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone.asStateFlow()

    /**
     * 输入栏工具状态（2026-11-23）：深度思考 + 智能搜索（联网）两颗胶囊 + 「正在搜索…」标记。
     *
     * 与消息状态 [_uiState] 分开：切会话 / 新建会话会整体替换消息状态，但这两个开关是**用户意图**，
     * 不应该被会话切换重置。
     * - [thinkingEnabled] 持久化（老用户的值直接复用，不重置）；变更即写回 ConfigRepository。
     * - [webSearchEnabled] **不持久化**：每次进 App 默认关，想联网手动点，防白烧 token。
     * - [thinkingAvailable]/[webSearchAvailable]：只有 DeepSeek 官方兼容端点 + deepseek-flash 才 true。
     * - [searching]：联网请求已发出、正文还没开始流时置 true，UI 显示「正在搜索…」。
     */
    data class ToolUiState(
        val thinkingEnabled: Boolean = false,
        val thinkingAvailable: Boolean = false,
        val webSearchEnabled: Boolean = false,
        val webSearchAvailable: Boolean = false,
        val searching: Boolean = false
    )

    private val _toolState = MutableStateFlow(ToolUiState())
    val toolState: StateFlow<ToolUiState> = _toolState.asStateFlow()

    private var conversationId: Long? = null

    /**
     * 当前对话模式（2026-11-22）。由 [syncChatMode] 从配置同步，UI 不直接改它。
     *
     * 正常模式下：不注入人设/能力/关系/记忆/情绪，不回写记忆/情绪/追问/提醒排钟，
     * 注记只发「日期,星期」两段。
     */
    private var chatMode: ChatMode = ChatMode.COMPANION

    init {
        prefetchWeather() // V2.1.8：进聊天页先后台拉一次天气，发消息时直接读缓存
        prefetchEmbedding() // 2026-11-21：预热本地向量模型，避免首轮记忆召回现加载（1~2s 卡首字）
        refreshToolAvailability() // 2026-11-23：同步输入栏胶囊的可用性与思考开关回显
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

    /** 重命名会话（会话列表 ✎ 用）。空标题忽略；DB 变化后 summaries 自动刷新。 */
    fun renameConversation(id: Long, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                conversationDao.updateTitle(id, t)
            }
        }
    }

    /** 把某会话的历史加载到 UI 状态（switchTo / deleteConversation 复用）。任务 1.8 */
    private suspend fun loadConversationIntoUi(id: Long, bindToMode: Boolean = true) {
        val history = messageDao.getByConversation(id)
        val lastAssistantUsage = history.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.tokPerSec != null
        }
        conversationId = id
        // 2026-11-22：顺手把「当前模式 ↔ 这个会话」记下来，下次切回该模式直接回到这里
        // 2026-11-23：从每日任务通知点进来查看时 bindToMode=false —— 别把该模式的主会话悄悄换成任务会话
        if (bindToMode) {
            runCatching { appStateDao.put(AppStateEntity(modeKey(chatMode), id.toString())) }
        }
        _uiState.value = ChatUiState(
            messages = history.map(::toUi),
            tokPerSec = lastAssistantUsage?.tokPerSec,
            cacheHitRatio = lastAssistantUsage?.cacheHit,
            contextUsage = lastAssistantUsage?.contextUsage,
            currentConversationId = id
        )
    }

    /**
     * 从通知点进来查看某个会话（每日任务 / 主动消息共用）。
     * **不绑定到当前模式**（bindToMode=false），否则会把「陪聊/正常」的主会话替换成这条通知的会话。
     */
    fun openConversationFromNotification(id: Long) {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (conversationDao.getById(id) != null) {
                    loadConversationIntoUi(id, bindToMode = false)
                }
            }
        }
    }

    /**
     * 从配置同步对话模式（2026-11-22）。设置页保存后 / 每次回前台调一次。
     *
     * 模式变了就把当前会话记到旧模式名下，再切到新模式自己的会话 —— 两种模式的历史
     * 必须物理隔离，否则正常模式会带着陪聊的语气答技术问题（历史是正文，关不掉）。
     */
    fun syncChatMode() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val target = runCatching { configRepository.getChatMode() }
                    .getOrDefault(ChatMode.COMPANION)
                if (target == chatMode) return@withContext
                conversationId?.let {
                    runCatching { appStateDao.put(AppStateEntity(modeKey(chatMode), it.toString())) }
                }
                chatMode = target
                loadConversationIntoUi(conversationFor(target))
            }
        }
    }

    /** 某个模式的会话绑定键（`app_state`）。 */
    private fun modeKey(mode: ChatMode): String = when (mode) {
        ChatMode.COMPANION -> AppStateEntity.KEY_COMPANION_CONVERSATION_ID
        ChatMode.NORMAL -> AppStateEntity.KEY_NORMAL_CONVERSATION_ID
    }

    /**
     * 取该模式该用的会话 id，没有就建一个。
     *
     * 陪聊模式的"没有"分支特意复用「最近一个有消息的、且不是正常模式那个」的会话：
     * 老用户升级后没有绑定键，不能凭空多出一个空对话让人以为历史丢了。
     */
    private suspend fun conversationFor(mode: ChatMode): Long {
        val key = modeKey(mode)
        val saved = appStateDao.get(key)?.toLongOrNull()
        if (saved != null && conversationDao.getById(saved) != null) return saved

        val id = if (mode == ChatMode.NORMAL) {
            conversationDao.insert(
                ConversationEntity(title = NORMAL_CONVERSATION_TITLE, createTime = now(), lastMessageTime = now())
            )
        } else {
            val normalId = appStateDao.get(AppStateEntity.KEY_NORMAL_CONVERSATION_ID)?.toLongOrNull()
            val candidates = conversationDao.getAll().filter { it.id != normalId }
            val existing = candidates.firstOrNull { messageDao.countByConversation(it.id) > 0 }
                ?: candidates.firstOrNull()
            existing?.id ?: conversationDao.insert(
                ConversationEntity(title = DEFAULT_TITLE, createTime = now(), lastMessageTime = now())
            )
        }
        runCatching { appStateDao.put(AppStateEntity(key, id.toString())) }
        return id
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

    // ---------- 输入栏工具：深度思考 / 智能搜索（2026-11-23） ----------

    /**
     * 同步工具可用性 + 深度思考回显。
     *
     * 在 ViewModel 初始化、以及从设置页返回 / 每次回前台（MainActivity）时调用：
     * - 只有「DeepSeek 官方兼容端点 + deepseek-flash」两颗胶囊才可点，否则一起置灰、且强制关掉。
     * - 深度思考从配置读（持久化）；不可用时显示为关。
     * - 联网不持久化：可用时保留当前会话的选择（默认 false），不可用时强制关。
     */
    fun refreshToolAvailability() {
        val cfg = runCatching { configRepository.load() }.getOrNull() ?: return
        val available = ProviderPresets.isDeepSeekCompatible(cfg.providerId, cfg.baseUrl) &&
            cfg.model == "deepseek-flash"
        _toolState.update { st ->
            st.copy(
                thinkingEnabled = if (available) cfg.thinkingEnabled else false,
                thinkingAvailable = available,
                webSearchAvailable = available,
                webSearchEnabled = if (available) st.webSearchEnabled else false
            )
        }
    }

    /** 深度思考开/关：持久化到配置（下次进 App 记住），并刷新 UI 状态。 */
    fun setThinkingEnabled(enabled: Boolean) {
        val cfg = runCatching { configRepository.load() }.getOrNull() ?: return
        runCatching { configRepository.save(cfg.copy(thinkingEnabled = enabled)) }
        _toolState.update { it.copy(thinkingEnabled = enabled) }
    }

    /**
     * 智能搜索（联网）开/关：**只改本次会话的内存状态，不落盘** —— 每次开 App 默认关。
     * 不可用时忽略（胶囊已置灰）。
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        _toolState.update {
            if (!it.webSearchAvailable) it else it.copy(webSearchEnabled = enabled)
        }
    }

    /**
     * 回前台时把「智能搜索」强制关掉（2026-11-23）。
     *
     * 用户要求「每次打开 App 默认关、想联网手动点」，并明确怕「忘了还开着白烧 token」。
     * 联网开关本就不落盘（进程死了自然没了），这里再在每次回到前台时重置一次，
     * 避免进程常驻后台很久、开关一直开着。**深度思考不受影响**（那是持久化的偏好）。
     */
    fun resetWebSearchOnForeground() {
        _toolState.update { if (it.webSearchEnabled) it.copy(webSearchEnabled = false) else it }
    }

    // ---------- 内部实现 ----------

    private suspend fun initConversation() {
        // 2026-11-22：先按当前模式挑会话。陪聊模式仍沿用「优先最近一个有消息的会话」，
        // 只是多了一条「排除正常模式那个会话」——否则从正常模式切回陪聊会停在问答会话里。
        try {
            chatMode = runCatching { configRepository.getChatMode() }.getOrDefault(ChatMode.COMPANION)
            loadConversationIntoUi(conversationFor(chatMode))
        } finally {
            _initialLoadDone.value = true
        }
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
            // 2026-11-22：正常模式只发「日期,星期」两段（地点/平台/语言/天气都是陪伴语义）
            if (chatMode == ChatMode.NORMAL) {
                UserMetaMarker.buildNormal()
            } else {
                UserMetaMarker.build(
                    location = displayCityName() ?: "",
                    weather = weatherLine()
                )
            }
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
        // 正常模式（2026-11-22）：不记账、不排钟。问答不该影响「她多久没被陪」的冷却闸门，
        // 也不该把「明天九点开会」这种技术性陈述排成提醒闹钟。
        if (chatMode == ChatMode.COMPANION) {
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
        }
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

    /**
     * 注记第 3 段（地点）该用的地名（2026-11-22）：**手填城市优先，其次自动定位反查结果**。
     *
     * 此前只取手填城市，自动定位模式下这段永远是空的 —— 模型能报天气，却答不出
     * 「你在哪座城市」。反查在「每天首次前台刷新定位」时后台做一次并落库，
     * 这里**只读缓存，绝不联网**（联网放发送路径 = 1.1.0 那个「消息半天不上屏」的坑）。
     */
    private suspend fun displayCityName(): String? =
        runCatching { appContainer.locationCache.displayCityName() }.getOrNull()

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
                // 2026-11-22：LocatedCity 现在直接给 point，不再写两边分支相同的 when
                appContainer.weatherCache.loadWithRetry(
                    appContainer.locationCache.current().point,
                    java.time.LocalDate.now(),
                )
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
            // 2026-11-22：模型就绪后顺手整理一次历史长期记忆（合并重复 + 给旧记录补向量）。
            // 幂等（没有重复就返回 0），失败静默，绝不影响聊天。修手动加记忆遗留的重复项。
            runCatching {
                val merged = appContainer.memoryDeduplicator.consolidatePlainLong()
                if (merged > 0) {
                    android.util.Log.w("MemoryDedup", "启动整理：合并删除 $merged 条重复长期记忆")
                }
            }
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

        // 联网搜索是否生效（2026-11-23）：带图轮不联网（图走视觉、联网走纯文本检索）；
        // 且必须是 DeepSeek 官方兼容 + flash（refreshToolAvailability 已保证，这里再兜一次）。
        val webSearchOn = images.isEmpty() &&
            _toolState.value.webSearchEnabled &&
            _toolState.value.webSearchAvailable

        // 取本轮 user（最后一条 user 消息）与历史（不含本轮）
        val history = messageDao.getByConversation(convId)
        val currentUser = history.lastOrNull { it.role == MessageRole.USER } ?: return
        val past = history.filter { it.id != currentUser.id }

        val companion = chatMode == ChatMode.COMPANION

        // 2026-11-22：正常模式这三块**全不做**，连记忆查询和情绪读取都不发。
        // 它们是陪伴语义；注入进去只会把问答拽回"陪聊"的调子，还白花 token。
        var memoryBlock: String? = null
        var emotionBlock: String? = null
        var relationshipNote: String? = null

        if (companion) {
            // 2026-11-21：主对话记忆注入。短期走向量相似度筛（阈值 0.45），长期/日记是稳定内容。
            // 预算取上下文窗口的 12%（≈ token），由 ContextTrimmer 之外的独立额度控制 ——
            // 记忆块自己先按预算裁好，避免把对话历史挤掉。没相关记忆时返回 null，一个字都不加。
            val memoryDiag = StringBuilder()
            // 2026-11-22 深度回忆：命中「记不记得 / 我上次 / 之前说的」等**过去回忆词**（且开关打开）时，
            // 让 App 放宽窗口/阈值多翻一遍记忆。未来/待办句（"明天要开会"）由检测器排除，不会误触发。
            val deepRecall = runCatching {
                RecallTriggerDetector.detect(currentUser.content).triggered &&
                    configRepository.getDeepRecallEnabled()
            }.getOrDefault(false)
            // 1.9.4.0：情绪回忆按需筛选（冷却 + 情绪加权 + boost tiebreaker），注入后逐条记账。
            val emotionalCandidates = runCatching {
                val st = appContainer.moodService.currentEmotionState()
                val dominant = st.hers.emotions.entries.maxByOrNull { it.value }?.key
                appContainer.emotionalMemoryService.injectable(dominant)
            }.getOrDefault(emptyList())
            val block = runCatching {
                appContainer.memoryContextProvider.build(
                    userText = currentUser.content,
                    // 深度回忆轮给更大记忆预算（18%），平时 12%
                    budgetChars = (effectiveConfig.contextWindow *
                        (if (deepRecall) MEMORY_CONTEXT_RATIO_RECALL else MEMORY_CONTEXT_RATIO)).toInt(),
                    emotional = emotionalCandidates.map { it.second },
                    recallMode = deepRecall,
                    // 2026-11-21：把每条候选的相似度分数打出来 —— 调阈值时只能靠这个，不能靠猜
                    onDiagnostics = { memoryDiag.append(it) }
                )
            }.getOrNull()
            memoryBlock = block
            // 1.9.4.0：只有候选正文真的进了本轮记忆块才算"被回忆"，逐条刷新注入时刻 + 每日一次 boost。
            if (!block.isNullOrBlank()) {
                runCatching {
                    emotionalCandidates.filter { block.contains(it.second) }.forEach {
                        appContainer.emotionalMemoryService.recall(it.first)
                    }
                }
            }
            // W 级（真机 D 级常被 ROM 吞）：用来核对「这轮到底注入了多少字记忆」+ 每条分数，
            // 0 = 没有相关记忆（正常，说明省 token 路径生效）
            android.util.Log.w(
                "MemoryCtx",
                "本轮注入记忆块 ${block?.length ?: 0} 字${if (deepRecall) "[深度回忆]" else ""}｜$memoryDiag"
            )
            // 2026-11-21 情绪系统 P1：尾部状态块（情绪 → 记忆）。它只改"她怎么说"，所以永远注入，
            // 不参与记忆块的 12% 预算；自身长度由 EmotionPhrase 控制在 ~70 字。
            emotionBlock = runCatching { appContainer.moodService.renderEmotionBlock() }.getOrNull()
            android.util.Log.w(
                "EmotionCtx",
                "本轮注入情绪块 ${emotionBlock?.length ?: 0} 字｜${emotionBlock?.replace("\n", " ／ ") ?: "-"}"
            )
            // P3：关系阶段（生疏/熟悉/亲近）—— 变化极慢，放稳定前缀
            relationshipNote = runCatching {
                com.companion.chat.domain.emotion.RelationshipStage.promptHint(
                    appContainer.moodService.currentRelationshipStage()
                )
            }.getOrNull()
        } else {
            android.util.Log.w("ChatMode", "正常模式：不注入人设 / 能力 / 记忆 / 情绪 / 关系")
        }

        val context = ContextBuilder.build(
            persona = if (companion) configRepository.getPersona() else "",
            longMemoryDigest = null, // 长期记忆由 memoryBlock 一起给出（不重复注入）
            history = past,
            currentUserText = currentUser.content,
            currentReminder = currentUser.reminder,
            contextWindow = effectiveConfig.contextWindow,
            capabilityNote = if (companion) ContextBuilder.CAPABILITIES else null,
            memoryBlock = memoryBlock,
            emotionBlock = emotionBlock,
            relationshipNote = relationshipNote,
            mode = chatMode
        )

        // 新建会话首条且标题仍是默认值 → 本次请求顺带生成标题（仅 DeepSeek 兼容端点支持 title）
        // 2026-11-23：联网轮次**不能**和 title 同传（服务端 400），所以联网时干脆不请求标题，靠前缀兜底。
        val needTitle = effectiveConfig.autoTitleEnabled &&
            !webSearchOn &&
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
        // 联网引用按 url 去重累积（同一条回复引用同一网页只留一条）
        val citationsAcc = LinkedHashMap<String, Citation>()
        // 联网时先亮「正在搜索…」，正文一开始流就熄灭（见下面的 Delta / Citations 分支）
        if (webSearchOn) _toolState.update { it.copy(searching = true) }
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
                    thinkingEnabled = effectiveConfig.thinkingEnabled,
                    webSearchEnabled = webSearchOn
                )
                apiClient.streamChat(effectiveConfig, context, images = images, opts = opts).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> {
                            if (_toolState.value.searching) _toolState.update { it.copy(searching = false) }
                            builder.append(ev.text)
                            updateAssistantText(tempId, builder.toString())
                        }
                        is StreamEvent.Citations -> {
                            ev.citations.forEach { c ->
                                if (c.url.isNotBlank() && !citationsAcc.containsKey(c.url)) {
                                    citationsAcc[c.url] = c
                                }
                            }
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

        // 无论成功失败，流结束都要熄灭「正在搜索…」
        if (_toolState.value.searching) _toolState.update { it.copy(searching = false) }

        if (success) {
            // 2026-11-21 情绪系统 P1：主导情绪 ≥0.5 时给回复加一个颜文字（代码追加，不劳模型）。
            // 存库前追加 → 历史回传也带 → 她会逐渐学会这个风格；末尾去重防"双份"。
            // 2026-11-22：正常模式不加 —— 问答回复后面挂个 (｡•́︿•̀｡) 很奇怪。
            val finalText = if (companion) {
                val emotionNow = runCatching { appContainer.moodService.currentEmotionState() }.getOrNull()
                EmotionKaomoji.append(builder.toString(), emotionNow?.hers?.emotions.orEmpty())
            } else {
                builder.toString()
            }
            val finalCitations = citationsAcc.values.toList()
            val assistant = MessageEntity(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = finalText,
                timestamp = now(),
                tokPerSec = usage?.tokPerSec,
                cacheHit = usage?.cacheHitRatio,
                contextUsage = usage?.contextUsage,
                // 联网引用随消息落库（灰色折叠来源可在此后任意时刻回看）
                citations = CitationCodec.encode(finalCitations)
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
                        if (it.id == tempId) {
                            MessageUiModel(
                                id = realId,
                                content = finalText,
                                isUser = false,
                                timestamp = assistant.timestamp,
                                citations = finalCitations
                            )
                        } else it
                    },
                    isStreaming = false,
                    tokPerSec = usage?.tokPerSec ?: st.tokPerSec,
                    cacheHitRatio = usage?.cacheHitRatio ?: st.cacheHitRatio,
                    contextUsage = usage?.contextUsage ?: st.contextUsage,
                    canRetry = false
                )
            }
            // 2026-11-22：正常模式**这四件事全不做** —— 问答内容不进记忆库、不参与情绪判定、
            // 不产生追问任务、也不给情感记忆记账。否则问一句「这段 Kotlin 为什么崩」
            // 会被当成聊天记进长期记忆，还会被判成「她今天情绪低落」。
            if (companion) {
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
            imagePaths = paths,
            citations = CitationCodec.decode(e.citations)
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
        /** 正常模式专属会话的初始标题（2026-11-22）。有自动标题时很快会被改成问的内容。 */
        private const val NORMAL_CONVERSATION_TITLE = "正常对话"
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

        /**
         * 深度回忆轮的记忆预算比例（2026-11-22）。
         * 放宽召回要多装几条记忆，所以这档给到 18%（普通轮 12%）；只作用于命中回忆词的轮次。
         */
        private const val MEMORY_CONTEXT_RATIO_RECALL = 0.18
    }
}
