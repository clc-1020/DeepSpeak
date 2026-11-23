package com.companion.chat.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.companion.chat.CompanionApp
import com.companion.chat.data.power.SystemPermissionStatus
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.power.KeepAliveStatus
import com.companion.chat.domain.power.KeepAliveTarget
import com.companion.chat.domain.settings.EnvironmentSettings
import com.companion.chat.domain.settings.QuickDnd
import com.companion.chat.domain.emotion.EmotionPhrase
import com.companion.chat.service.QuickAccessEntrance
import com.companion.chat.ui.chat.ChatScreen
import com.companion.chat.ui.chat.ChatViewModel
import com.companion.chat.ui.chat.ConversationDrawerContent
import com.companion.chat.ui.chat.ConversationListViewModel
import com.companion.chat.ui.daily.DailyTaskScreen
import com.companion.chat.ui.memory.MemoryScreen
import com.companion.chat.ui.power.KeepAliveGuideDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.companion.chat.ui.settings.SettingsForm
import com.companion.chat.ui.settings.SettingsScreen
import com.companion.chat.ui.theme.CompanionTheme

class MainActivity : ComponentActivity() {
    // V2.3.6：vm 是 setContent 内 MainScreen 的默认参数局部变量，onResume 拿不到；用 tick 通知页面读结果
    private var resumeTick by mutableIntStateOf(0)

    // 2026-11-23：从通知点进来要定位到指定会话（每日任务 / 主动消息），tap intent 携带会话 id
    private var openConversationId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openConversationId = intent?.getLongExtra(EXTRA_CONVERSATION_ID, -1L)?.takeIf { it > 0L }
        setContent {
            CompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        resumeTick = resumeTick,
                        openConversationId = openConversationId,
                        onConversationHandled = { openConversationId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        if (id > 0L) openConversationId = id
    }

    override fun onResume() {
        super.onResume()
        // V2.3.6：常驻入口只在「前台界面已显示」时启动（验收第 3 条：Application 里不许启动 FGS）
        QuickAccessEntrance.sync(this)
        resumeTick++
    }

    companion object {
        /** 与 DailyTaskNotifier / AndroidProactiveNotifier 用同一个键。 */
        private const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }
}

@Composable
fun MainScreen(
    vm: ChatViewModel = viewModel(),
    resumeTick: Int = 0,
    openConversationId: Long? = null,
    onConversationHandled: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as CompanionApp

    // 2026-11-23：通知点进来 → 打开指定会话（不绑定当前模式，见 ChatViewModel.openConversationFromNotification）
    // 等首屏默认会话加载完成后再切，避免被 initConversation 覆盖（冷启动点通知的场景）。
    val initialLoadDone by vm.initialLoadDone.collectAsState()
    LaunchedEffect(openConversationId, initialLoadDone) {
        val id = openConversationId ?: return@LaunchedEffect
        if (!initialLoadDone) return@LaunchedEffect
        vm.openConversationFromNotification(id)
        onConversationHandled()
    }

    // V2.3.6：onResume 触发一次读库（vm 在此作用域可见），结果进 state.voicePending 回填输入框
    LaunchedEffect(resumeTick) {
        vm.consumeVoicePendingText()
        // 2026-11-23：回前台时同步输入栏工具可用性（换模型/服务商后胶囊该置灰就置灰）
        vm.refreshToolAvailability()
        // 2026-11-23：联网（智能搜索）每次回到前台默认关，防忘了还开着白烧 token
        vm.resetWebSearchOnForeground()
    }

    // 2026-11-22：每天首次回到前台时**主动**定位一次（仅自动定位模式；手填城市直接短路，一次都不调）。
    // 前台请求只要「仅在使用时允许」权限，不引入后台定位权限（后台请求在 Android 10+ 要「始终允许」，
    // MIUI 还会反复降级）。刷新到新坐标后立刻强制重拉天气——否则 24h 缓存里还是旧城市的天气。
    // 全程 runCatching：没权限 / 超时 / 网络失败都不影响界面。
    LaunchedEffect(resumeTick) {
        withContext(Dispatchers.IO) {
            runCatching {
                val today = LocalDate.now()
                val refresh = app.locationCache.refreshTodayOnce(today.toEpochDay())
                val point = refresh.point
                if (refresh.attempted && point != null) {
                    app.weatherCache.load(point, today, force = true)
                }
            }
        }
    }

    var screen by remember { mutableStateOf("chat") }

    // 2026-11-22：系统返回键（手势/实体）在子页面（设置 / 记忆）时，先回到对话页，
    // 而不是直接 finish Activity 退到桌面。子页面是同一 Activity 内的 state 切换，
    // 不接管的话系统返回默认会退出到桌面（用户实测反馈）。
    BackHandler(enabled = screen != "chat") { screen = "chat" }

    // 任务 6.2 / 7.1：首次启动弹一次「保活引导」（白名单 / 自启动 / 后台限制）
    var keepAliveGuide by remember { mutableStateOf<KeepAliveStatus?>(null) }
    var showKeepAliveGuide by remember { mutableStateOf(false) }
    val guideScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // 6.2 审查改进：启动计数 + 通知权限已授予 + 非首次启动才弹，避免和系统弹窗抢注意力
        if (runCatching { app.keepAliveService.recordLaunchAndShouldGuide() }.getOrDefault(false)) {
            keepAliveGuide = runCatching { app.keepAliveService.status() }.getOrNull()
            showKeepAliveGuide = true
        }
    }
    if (showKeepAliveGuide) {
        KeepAliveGuideDialog(
            status = keepAliveGuide,
            onOpenTarget = { target -> app.keepAliveLauncher.open(target) },
            onDismiss = {
                showKeepAliveGuide = false
                guideScope.launch { app.keepAliveService.markGuideShown() }
            }
        )
    }

    // 错误提示（断网 / API 错误等）
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    when (screen) {
        "daily_tasks" -> {
            DailyTaskScreen(onBack = { screen = "chat" })
        }

        "memory" -> {
            MemoryScreen(onBack = { screen = "chat" })
        }

        "settings" -> {
            val initial = remember {
                val c = app.configRepository.load()
                val env = app.configRepository.getEnvironmentSettings()
                SettingsForm(
                    providerId = c.providerId,
                    baseUrl = c.baseUrl,
                    apiKey = c.apiKey,
                    model = c.model,
                    visionModel = c.visionModel,
                    persona = app.configRepository.getPersona(),
                    contextWindow = c.contextWindow,
                    // DeepSeek 新 API 相关开关（必须与 ConfigRepository/ApiConfig 双向映射，否则保存会被默认值覆盖）
                    thinkingEnabled = c.thinkingEnabled,
                    deepSeekTtsEnabled = c.deepSeekTtsEnabled,
                    ttsVoice = c.ttsVoice,
                    ttsEnabled = app.configRepository.getTtsEnabled(),
                    ttsSpeechRate = app.configRepository.getTtsSpeechRate(),
                    ttsSystemEngine = app.configRepository.getTtsSystemEngine(),
                    // V2.3.5：语音输入（STT）开关；initial 漏了它 → 重进设置页会跳回默认 true
                    sttEnabled = app.configRepository.getSttEnabled(),
                    // V2.3.6：通知栏常驻快捷入口开关回显
                    quickAccessEnabled = app.configRepository.getQuickAccessEnabled(),
                    proactiveVoiceEnabled = app.configRepository.getProactiveVoiceEnabled(),
                    dndEnabled = app.configRepository.getDndEnabled(),
                    proactiveEnabled = app.configRepository.getProactiveEnabled(),
                    proactiveDailyLimit = app.configRepository.getProactiveDailyLimit(),
                    dndStartMinute = app.configRepository.getDndStartMinute(),
                    dndEndMinute = app.configRepository.getDndEndMinute(),
                    dndSuppressNotification = app.configRepository.getDndSuppressNotification(),
                    dndSuppressInstruction = app.configRepository.getDndSuppressInstruction(),
                    latestReminderEnabled = c.latestReminderEnabled,
                    autoTitleEnabled = c.autoTitleEnabled,
                    jsonModeDefault = c.jsonModeDefault,
                    deepRecallEnabled = app.configRepository.getDeepRecallEnabled(),
                    // 2026-11-22：对话模式（陪聊 / 正常），默认陪聊
                    chatMode = app.configRepository.getChatMode(),
                    environmentEnabled = env.environmentEnabled,
                    lateNightEnabled = env.lateNightEnabled,
                    lateNightHour = env.lateNightHour,
                    rainThreshold = env.rainThreshold,
                    tempDropThreshold = env.tempDropThreshold
                )
            }
            val settingsScope = rememberCoroutineScope()
            // 任务 7.1：权限状态（点「刷新状态」或从系统设置返回后再读一次）
            var permTick by remember { mutableStateOf(0) }
            val permissionStatus = remember(permTick) { app.systemPermissions.status() }
            var keepAliveStatus by remember { mutableStateOf<KeepAliveStatus?>(null) }
            var currentMood by remember { mutableStateOf("normal") }
            // 模型给的判定理由（app_state[mood_reason]）。以前只写库不显示，用户看不出「为什么是这个心情」。
            var currentMoodReason by remember { mutableStateOf("") }
            // 情绪系统 P1（2026-11-21）：她的情绪摘要（情绪+强度+诱因+多久前），给用户看数字
            var emotionSummary by remember { mutableStateOf("平静") }
            // P3（2026-11-21）：关系阶段（生疏/熟悉/亲近）
            var relationshipText by remember { mutableStateOf("生疏") }
            // P3.1（2026-11-21）：热络度（会随时间变淡）
            var warmthText by remember { mutableStateOf("0.5（还行）") }
            LaunchedEffect(permTick) {
                keepAliveStatus = runCatching { app.keepAliveService.status() }.getOrNull()
                currentMood = runCatching { app.moodService.currentMood() }.getOrDefault("normal")
                currentMoodReason = runCatching { app.moodService.currentReason() }.getOrDefault("")
                relationshipText = runCatching { app.moodService.relationshipSummary() }.getOrDefault("生疏")
                warmthText = runCatching { app.moodService.warmthSummary() }.getOrDefault("0.5（还行）")
                emotionSummary = runCatching {
                    val st = app.moodService.currentEmotionState()
                    val parts = st.hers.emotions.entries.sortedByDescending { it.value }.take(2)
                        .joinToString(" / ") {
                            "${com.companion.chat.domain.emotion.EmotionPhrase.label(it.key)} " +
                                com.companion.chat.domain.emotion.EmotionPhrase.fmt(it.value)
                        }
                    if (parts.isBlank()) "平静（没有明显情绪）"
                    else buildString {
                        append(parts)
                        if (st.hers.trigger.isNotBlank()) append("｜因为：").append(st.hers.trigger)
                        if (st.hers.since > 0L) {
                            append("｜").append(agoText(System.currentTimeMillis() - st.hers.since))
                        }
                    }
                }.getOrDefault("平静")
            }
            val persist: (SettingsForm) -> Unit = { form ->
                app.configRepository.save(
                    ApiConfig(
                        providerId = form.providerId,
                        baseUrl = form.baseUrl,
                        apiKey = form.apiKey,
                        model = form.model,
                        contextWindow = form.contextWindow,
                        visionModel = form.visionModel,
                        thinkingEnabled = app.configRepository.getThinkingEnabled(),
                        deepSeekTtsEnabled = form.deepSeekTtsEnabled,
                        ttsVoice = form.ttsVoice,
                        latestReminderEnabled = form.latestReminderEnabled,
                        autoTitleEnabled = form.autoTitleEnabled,
                        jsonModeDefault = form.jsonModeDefault
                    )
                )
                app.configRepository.savePersona(form.persona)
                app.configRepository.setTtsEnabled(form.ttsEnabled)
                app.configRepository.setTtsSpeechRate(form.ttsSpeechRate)
                app.configRepository.setTtsSystemEngine(form.ttsSystemEngine)
                // V2.3.5：语音输入开关；persist 漏了它 → 点了不生效（重启又变回开）
                app.configRepository.setSttEnabled(form.sttEnabled)
                // V2.3.6：保存后当场同步常驻入口，关掉即撤通知，不等下次进前台
                app.configRepository.setQuickAccessEnabled(form.quickAccessEnabled)
                QuickAccessEntrance.sync(context)
                // 2026-11-22：深度回忆开关（命中回忆词才多翻记忆）
                app.configRepository.setDeepRecallEnabled(form.deepRecallEnabled)
                app.configRepository.setProactiveVoiceEnabled(form.proactiveVoiceEnabled)
                app.configRepository.setDndEnabled(form.dndEnabled)
                // 任务 7.1/7.2：主动消息总开关、每日上限、勿扰区间与「区间内不发通知」
                app.configRepository.setProactiveEnabled(form.proactiveEnabled)
                app.configRepository.setProactiveDailyLimit(form.proactiveDailyLimit)
                app.configRepository.setDndStartMinute(form.dndStartMinute)
                // 用户自己在设置页定了作息 → 铃铛留下的「临时起点」快照作废，否则下次点关闭会把它覆盖回去
                app.configRepository.clearDndSavedStart()
                app.configRepository.setDndEndMinute(form.dndEndMinute)
                app.configRepository.setDndSuppressNotification(form.dndSuppressNotification)
                app.configRepository.setDndSuppressInstruction(form.dndSuppressInstruction)
                // 任务 V2.1.6：环境感知（4 个开关/阈值走 prefs）
                app.configRepository.saveEnvironmentSettings(
                    EnvironmentSettings(
                        environmentEnabled = form.environmentEnabled,
                        lateNightEnabled = form.lateNightEnabled,
                        lateNightHour = form.lateNightHour,
                        rainThreshold = form.rainThreshold,
                        tempDropThreshold = form.tempDropThreshold
                    )
                )
                // V2.1.6 容器接线：手动城市由 CityGeocoder.resolve() 直接落库（Hit 时写、输入清空时删），
                // 设置页保存时不需要再写一次。
                // 2026-11-22：对话模式。保存后立刻让聊天页切到该模式自己的会话——
                // 两种模式的历史必须物理隔离，不能靠"下次启动才生效"。
                app.configRepository.setChatMode(form.chatMode)
                vm.syncChatMode()
                // 2026-11-23：换模型/服务商后立刻重算胶囊可用性（置灰规则），不必等重启
                vm.refreshToolAvailability()
            }
            SettingsScreen(
                initial = initial,
                onPersist = persist,
                permissionStatus = permissionStatus,
                keepAliveStatus = keepAliveStatus,
                currentMood = currentMood,
                currentMoodReason = currentMoodReason,
                emotionSummary = emotionSummary,
                relationshipText = relationshipText,
                warmthText = warmthText,
                cityGeocoder = app.cityGeocoder,
                locationCache = app.locationCache,
                onRefreshPermissions = { permTick++ },
                onOpenNotificationSettings = { app.systemPermissions.openNotificationSettings() },
                onOpenExactAlarmSettings = { app.systemPermissions.openExactAlarmSettings() },
                onOpenBatteryOptimization = {
                    app.keepAliveLauncher.open(KeepAliveTarget.BATTERY_WHITELIST)
                },
                onOpenAutostart = { app.keepAliveLauncher.open(KeepAliveTarget.AUTOSTART) },
                onOpenBackgroundSettings = {
                    app.keepAliveLauncher.open(KeepAliveTarget.BACKGROUND_SETTINGS)
                },
                onResetKeepAliveGuide = {
                    // 手动查看不再受「是否已在白名单」的自动弹窗门槛限制：重置标记后**立即**打开引导
                    settingsScope.launch {
                        app.keepAliveService.resetGuide()
                        keepAliveGuide = runCatching { app.keepAliveService.status() }.getOrNull()
                        showKeepAliveGuide = true
                    }
                },
                onSave = { form ->
                    persist(form)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    screen = "chat"
                },
                onBack = { screen = "chat" },
                onTestProactive = {
                    // 任务 3.6：绕闸门跑完整链路（决策→召回→生成→通知→语音）
                    settingsScope.launch {
                        val ok = app.proactiveTestTrigger.triggerNow()
                        Toast.makeText(
                            context,
                            if (ok) "已触发，请留意通知栏" else "触发失败：检查 API Key / 通知权限",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        else -> {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            // 2026-11-22：历史会话抽屉（"历史会话页面"）打开时，系统返回先关抽屉，而不是退到桌面。
            BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
            val convListVm: ConversationListViewModel = viewModel()
            val summaries by convListVm.summaries.collectAsState()
            // 2026-11-23：输入栏工具状态（深度思考 / 智能搜索 / 正在搜索）
            val tool by vm.toolState.collectAsState()
            // V2.3.5：STT 开关。这里没有 SettingsForm 作用域（form 只在 "settings" 分支里），
            // 所以现读一次 pref；从设置页保存回来时本分支会整块重进组合，remember 自然重读。
            val chatSttEnabled = remember { app.configRepository.getSttEnabled() }
            // 顶栏铃铛（一键勿扰）：必须是可变 state —— 点一下图标要立刻变，
            // 原来 onDndClick 是个空 lambda（TODO），点了毫无反应（2026-11-20 用户实测反馈）。
            var chatDndEnabled by remember { mutableStateOf(app.configRepository.getDndEnabled()) }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ConversationDrawerContent(
                        summaries = summaries,
                        currentId = state.currentConversationId,
                        onSelect = { id ->
                            vm.switchTo(id)
                            scope.launch { drawerState.close() }
                        },
                        onDelete = { id -> vm.deleteConversation(id) },
                        onNewChat = {
                            vm.newChat()
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            ) {
                ChatScreen(
                    messages = state.messages,
                    onSendMessage = { text, uris -> vm.send(text, uris) },
                    onSettingsClick = { screen = "settings" },
                    onDndClick = {
                        // 铃铛 = 立刻静音到现在这段区间结束为止（语义见 QuickDnd 注释）。
                        // 决策全在纯函数里，这里只负责落库 + 反馈 + 让图标变色。
                        val repo = app.configRepository
                        val plan = QuickDnd.plan(
                            currentEnabled = chatDndEnabled,
                            nowMillis = System.currentTimeMillis(),
                            startMinute = repo.getDndStartMinute(),
                            endMinute = repo.getDndEndMinute(),
                            savedStartMinute = repo.getDndSavedStartMinute()
                        )
                        repo.setDndEnabled(plan.enabled)
                        plan.startMinute?.let { repo.setDndStartMinute(it) }
                        if (plan.clearSavedStart) repo.clearDndSavedStart()
                        plan.savedStartMinute?.let { repo.setDndSavedStartMinute(it) }
                        chatDndEnabled = plan.enabled
                        // MainScreen 是顶层 Composable，没有 MainActivity 接收者；用已取到的 context
                        Toast.makeText(context, plan.toast, Toast.LENGTH_SHORT).show()
                    },
                    onNewChatClick = { vm.newChat() },
                    onMemoryClick = { screen = "memory" },
                    onDailyTasksClick = { screen = "daily_tasks" },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    // V2.3.4：语音输入在 ChatScreen 内部（长按录音 → 松手识别），
                    // onVoiceClick 仅作为 sttManager 缺失时的兜底入口，这里不再放逻辑
                    onVoiceClick = { },
                    sttManager = app.sttManager,
                    sttEnabled = chatSttEnabled,
                    dndEnabled = chatDndEnabled,
                    tokPerSec = state.tokPerSec,
                    cacheHitRatio = state.cacheHitRatio,
                    contextUsage = state.contextUsage,
                    canRetry = state.canRetry,
                    onRetry = { vm.retry() },
                    onReadAloud = { id, text -> vm.readAloud(id, text) },
                    speakingId = state.speakingId,
                    // V2.3.6：通知栏语音结果回填输入框（只填不发送）
                    voicePending = state.voicePending,
                    onVoicePendingConsumed = { vm.voicePendingConsumed() },
                    // 消息操作：流式中禁用；撤回=截断到这条；重新生成=重问最后一条
                    isStreaming = state.isStreaming,
                    onTruncateFrom = { id -> vm.truncateFrom(id) },
                    onRegenerate = { vm.regenerateLast() },
                    // 2026-11-23：输入栏工具胶囊 + 「正在搜索…」
                    thinkingEnabled = tool.thinkingEnabled,
                    thinkingAvailable = tool.thinkingAvailable,
                    onThinkingToggle = { vm.setThinkingEnabled(!tool.thinkingEnabled) },
                    webSearchEnabled = tool.webSearchEnabled,
                    webSearchAvailable = tool.webSearchAvailable,
                    onWebSearchToggle = { vm.setWebSearchEnabled(!tool.webSearchEnabled) },
                    searching = tool.searching
                )
            }
        }
    }
}

/** 情绪摘要里的「多久前」：给人看的时间，不是精确值。 */
private fun agoText(millis: Long): String {
    if (millis < 60_000L) return "刚刚"
    val minutes = millis / 60_000L
    if (minutes < 60L) return "${minutes} 分钟前"
    val hours = minutes / 60L
    if (hours < 24L) return "${hours} 小时前"
    return "${hours / 24L} 天前"
}