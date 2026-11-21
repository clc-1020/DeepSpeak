package com.companion.chat

import android.app.Application
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.companion.chat.data.embedding.EmbeddingService
import com.companion.chat.data.embedding.EmbeddingServiceFactory
import com.companion.chat.data.local.AppDatabase
import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.AlarmScheduler
import com.companion.chat.data.alarm.BootRestorePlanner
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.alarm.ReminderService
import com.companion.chat.data.calendar.FestivalProvider
import com.companion.chat.data.environment.AndroidLocationPort
import com.companion.chat.data.environment.CityGeocoder
import com.companion.chat.data.environment.LocationCache
import com.companion.chat.data.environment.OkHttpHttpText
import com.companion.chat.data.environment.RoomKeyValueStore
import com.companion.chat.data.environment.SharedPrefsKeyValueStore
import com.companion.chat.data.environment.WeatherCache
import com.companion.chat.data.memory.MemoryDecayService
import com.companion.chat.data.memory.MemoryCapacityEnforcer
import com.companion.chat.data.memory.MemoryContextProvider
import com.companion.chat.data.memory.MemoryExtractor
import com.companion.chat.data.memory.MemoryFeedbackService
import com.companion.chat.data.memory.MemoryRecallService
import com.companion.chat.data.memory.MemoryTopicSelector
import com.companion.chat.data.mood.MoodService
import com.companion.chat.data.mood.MoodServiceFactory
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.data.reminder.FollowupChainRunner
import com.companion.chat.data.reminder.FollowupPortImpl
import com.companion.chat.data.reminder.FollowupTaskExtractor
import com.companion.chat.data.reminder.ReminderActionHandler
import com.companion.chat.data.prefs.ConfigRepository
import com.companion.chat.data.proactive.AndroidProactiveNotifier
import com.companion.chat.data.proactive.EnvironmentHookService
import com.companion.chat.data.proactive.LlmProactiveMessageGenerator
import com.companion.chat.data.proactive.ProactiveCheckRunner
import com.companion.chat.data.proactive.ProactiveDeliveryRunner
import com.companion.chat.data.proactive.ProactiveSettingsMapping
import com.companion.chat.data.proactive.ProactiveSendCounter
import com.companion.chat.data.proactive.ProactiveTestTrigger
import com.companion.chat.data.proactive.RoomConversationWriter
import com.companion.chat.data.proactive.SpecialDateHookService
import com.companion.chat.data.proactive.TtsProactiveSpeaker
import com.companion.chat.data.proactive.DefaultProactiveGate
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.ChatOptions
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.data.speech.DeepSeekAsrClient
import com.companion.chat.data.speech.SttManager
import com.companion.chat.data.tts.DeepSeekTtsEngine
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.reminder.BootFollowupRestorer
import com.companion.chat.data.power.KeepAliveLauncher
import com.companion.chat.data.power.SystemPermissions
import com.companion.chat.data.power.KeepAliveService
import com.companion.chat.data.power.PowerWakeLock
import com.companion.chat.data.power.WakeLockPolicy
import com.companion.chat.data.tts.ReadAloudController
import com.companion.chat.data.tts.SystemTtsEngine
import com.companion.chat.data.tts.TtsManager
import com.companion.chat.data.tts.TtsSettings
import com.companion.chat.domain.environment.EnvironmentHooks
import com.companion.chat.domain.environment.EnvironmentRuleEngine
import com.companion.chat.domain.work.WorkScheduleHealth
import com.companion.chat.domain.reminder.FollowupPort
import com.companion.chat.domain.proactive.FestivalDedupe
import com.companion.chat.domain.proactive.EmotionProactivePolicy
import com.companion.chat.domain.proactive.PendingMessageContent
import com.companion.chat.domain.proactive.ProactiveFallbackTopics
import com.companion.chat.domain.proactive.ProactiveDebugFlags
import com.companion.chat.domain.proactive.ProactiveGate
import java.time.LocalDate
import java.time.ZoneId
import com.companion.chat.domain.settings.DndPolicy
import com.companion.chat.domain.proactive.ProactiveVoicePolicy
import com.companion.chat.domain.proactive.ProactiveVoiceRules
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.worker.EnvironmentCheckScheduler
import com.companion.chat.worker.MemoryDecayWorker
import com.companion.chat.worker.ProactiveCheckWorker
import com.companion.chat.worker.ProactiveDeliveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CompanionApp : Application() {

    /** 全局单例数据库（任务 1.1） */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** API 配置存储（任务 1.2） */
    val configRepository: ConfigRepository by lazy { ConfigRepository(this) }

    // ---- V2.1.6 环境感知接线（UI 线需要的容器出口） ----
    /** `app_state` 表实现（手动城市等长期状态）。 */
    val envStore: RoomKeyValueStore by lazy { RoomKeyValueStore(database.appStateDao()) }
    /** 定位缓存：手动城市优先，其次系统定位（V2.1.1）。 */
    val locationCache: LocationCache by lazy {
        LocationCache(envStore, AndroidLocationPort(this))
    }
    /** 城市名 → 经纬度（V2.1.1）。 */
    val cityGeocoder: CityGeocoder by lazy {
        CityGeocoder(envStore, OkHttpHttpText())
    }

    /**
     * 天气缓存（V2.1.1）：SharedPreferences 存 24h 预报。
     * 环境钩子服务（V2.1.4）依赖它拿今天/昨天天气。
     */
    val weatherCache: WeatherCache by lazy {
        WeatherCache(
            store = SharedPrefsKeyValueStore(getSharedPreferences("env_weather", MODE_PRIVATE)),
            http = OkHttpHttpText(),
        )
    }

    /** 流式聊天客户端（任务 1.2） */
    val chatApiClient: ChatApiClient by lazy { ChatApiClient() }

    // ---- V2.3.3 DeepSeek 云端 ASR（短超时专用 client，不复用聊天 120s 流式 client） ----
    /** 上传 8 MiB 上限约 1 分钟内该出结果；超时由客户端短超时 + 服务端 408/504 双重兜底。 */
    private val asrHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** ASR 复用聊天 key **与 Base URL**；每次调用现取，设置页改完立即生效。 */
    val deepSeekAsrClient: DeepSeekAsrClient by lazy {
        DeepSeekAsrClient(
            client = asrHttpClient,
            apiKeyProvider = { configRepository.getApiKey() },
            baseUrlProvider = { configRepository.load().baseUrl }
        )
    }

    /**
     * STT 门面：开关每轮现读（必须是 lambda，不能缓存成 val/Boolean），
     * 关闭时连请求都不构造。
     */
    val sttManager: SttManager by lazy {
        SttManager(deepSeekAsrClient, { configRepository.getSttEnabled() })
    }

    /** TTS 管理器（任务 4.1）：系统兜底 + DeepSeek 官方可选 */
    val ttsManager: TtsManager by lazy {
        val system = SystemTtsEngine(
            context = this,
            speechRateProvider = { configRepository.getTtsSpeechRate() },
            engineNameProvider = { configRepository.getTtsSystemEngine() }
        )
        val deepSeek = DeepSeekTtsEngine(
            apiKeyProvider = { configRepository.getApiKey() },
            baseUrlProvider = { configRepository.load().baseUrl },
            voiceProvider = { configRepository.load().ttsVoice },
            cacheDirProvider = { cacheDir }
        )
        TtsManager(
            settingsProvider = {
                val c = configRepository.load()
                TtsSettings(
                    enabled = configRepository.getTtsEnabled(),
                    deepSeekEnabled = c.deepSeekTtsEnabled,
                    voiceId = c.ttsVoice,
                    speechRate = configRepository.getTtsSpeechRate(),
                    systemEngine = configRepository.getTtsSystemEngine()
                )
            },
            systemEngine = system,
            deepSeekEngine = deepSeek
        )
    }

    /**
     * 对话朗读控制器（任务 4.2）：点击消息喇叭 → 整段朗读，期间持 WakeLock，念完自动释放。
     */
    val readAloudController: ReadAloudController by lazy {
        ReadAloudController(
            ttsManager = ttsManager,
            wakeLock = PowerWakeLock(this, WakeLockPolicy.TAG_TTS)
        )
    }

    /**
     * 记忆提取与存储（任务 2.2）：每轮对话成功后独立调用，失败静默、不影响主对话。
     * embedding 服务就绪时给新记忆附向量（任务 2.1），未就绪存 null。
     */
    val memoryExtractor: MemoryExtractor by lazy {
        MemoryExtractor(
            apiClient = chatApiClient,
            memoryShortDao = database.memoryShortDao(),
            memoryLongDao = database.memoryLongDao(),
            memoryDailyDao = database.memoryDailyDao(),
            embeddingServiceProvider = { EmbeddingServiceFactory.ensure(this@CompanionApp) }
        )
    }

    /** 记忆召回（任务 2.6）：TOP3 规则算分 → 大模型挑一个话题；供主动消息框架（阶段 3）调用。 */
    private val memoryTopicSelector: MemoryTopicSelector by lazy { MemoryTopicSelector(chatApiClient) }
    val memoryRecallService: MemoryRecallService by lazy {
        MemoryRecallService(
            shortDao = database.memoryShortDao(),
            selector = { cfg, cands -> memoryTopicSelector.choose(cfg, cands) }
        )
    }

    /** 记忆打分三态（任务 2.8）：主动消息通知的「回复/忽略/别提这个」三按钮回调到这里。 */
    val memoryFeedbackService: MemoryFeedbackService by lazy {
        MemoryFeedbackService(shortDao = database.memoryShortDao())
    }

    /**
     * 追问闹钟薄封装（V2.2.2）：只包 AlarmScheduling，不需要 Context。
     * requestCode 一律走 AlarmCodes，公式见那里。
     */
    val followupAlarms: FollowupAlarms by lazy { FollowupAlarms(alarmScheduler) }

    /**
     * 追问端口真实实现（V2.2.2）。**不许留 null**：留 null 时单测照样全绿（测试自己塞假 port），
     * 但真机上 MoodService 里 followup?.active() 恒 null → 追问链永不启动。
     * 全是 by lazy，声明顺序不影响对 alarmScheduler / database 的依赖。
     */
    val followupPort: FollowupPort by lazy {
        // queue 必须传：complete()/abandon() 要用它清掉**已入队**的残留追问，
        // 否则用户在对话里说「我做完了」仍会收到 15 分钟前排队的那一条（V2.2.2 遗留）。
        FollowupPortImpl(
            dao = database.reminderDao(),
            alarms = followupAlarms,
            queue = pendingMessageQueue,
            memoryShortDao = database.memoryShortDao()
        )
    }

    /**
     * 通知按钮回执处理（V2.2.5）。抽出分支的动机见 ReminderActionHandler 注释：
     * receiver 是 Android 类，JVM 测不了。
     */
    val reminderActionHandler: ReminderActionHandler by lazy {
        ReminderActionHandler(
            followupPort = followupPort,
            queue = pendingMessageQueue,
            deleteMemory = { id -> database.memoryShortDao().deleteById(id) }
        )
    }

    /**
     * 主对话记忆注入（2026-11-21）：短期（向量相似度筛）+ 长期 + 近 3 天日记，
     * 拼成一段按预算截断的文本交给 `ContextBuilder.memoryBlock`。
     */
    val memoryContextProvider: MemoryContextProvider by lazy {
        MemoryContextProvider(
            shortDao = database.memoryShortDao(),
            longDao = database.memoryLongDao(),
            dailyDao = database.memoryDailyDao(),
            // peek：不在这里触发模型加载（主线程懒加载会卡首字），预热由 ChatViewModel.init 负责
            embedding = { embeddingServiceOrNoop() }
        )
    }

    /**
     * 短期记忆容量淘汰（2026-11-20）：攒满 50 条清掉淡忘分最高的 20 条，
     * 保护 24 小时内新写的 + 正在提醒/监督中的。策略见 `ShortMemoryCapacity`。
     */
    val memoryCapacityEnforcer: MemoryCapacityEnforcer by lazy {
        MemoryCapacityEnforcer(
            shortDao = database.memoryShortDao(),
            reminderDao = database.reminderDao()
        )
    }

    /**
     * 追问任务创建（V2.2.3）：UI 线在 ChatViewModel.handleSend 主对话成功后调
     * `followupTaskExtractor.extractAndSchedule(config, recent)`（fire-and-forget）。
     */
    val followupTaskExtractor: FollowupTaskExtractor by lazy {
        FollowupTaskExtractor(
            apiClient = chatApiClient,
            dao = database.reminderDao(),
            alarms = followupAlarms
        )
    }

    /**
     * 追问链 tick（V2.2.4）。全部依赖可注入，纯 JVM 可测。
     *
     * 与 [followupTaskExtractor] 的分工：那边负责「建行 + 排首钟」，这里负责「首钟之后的循环」。
     * 两个都必须是**单个实例**（全 by lazy）：重复 new 会让两个 runner 各持一套 dao/queue 引用，
     * 排查时看不出问题，但审计日志会分叉。
     */
    val followupChainRunner: FollowupChainRunner by lazy {
        FollowupChainRunner(
            dao = database.reminderDao(),
            alarms = followupAlarms,
            queue = pendingMessageQueue,
            port = followupPort,
        )
    }

    /**
     * 重启恢复时对过期追问行的处置（V2.2.4 审查 R1）：不重放追问，补 TIMEOUT 钟让链继续。
     * 抽成独立类是因为「重启后 timeout(id) 闹钟必须存在」这条副作用 JVM 测不到 Application。
     */
    val followupBootRestorer: BootFollowupRestorer by lazy {
        BootFollowupRestorer(alarms = followupAlarms)
    }

    /**
     * mood 更新（任务 5.1 / V2.2.2）：每 5 轮对话独立调用模型判断用户心情，写 app_state；
     * 有活跃追问链时每轮都调，并按 followup_status 推进追问。失败保持原 mood。
     * 独立无状态请求（不复用主会话 ID）。
     */
    val moodService: MoodService by lazy {
        MoodServiceFactory.create(
            appStateDao = database.appStateDao(),
            chatClient = chatApiClient,
            followup = followupPort,         // ← 不许省略、不许传 null
            // P3（2026-11-21）：关系阶段 = f(全库消息数, 认识天数)
            relationshipProvider = {
                val count = database.messageDao().countAll()
                val first = database.messageDao().firstTimestamp()
                val days = if (first == null) 1
                    else (((System.currentTimeMillis() - first) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
                com.companion.chat.domain.emotion.RelationshipStage.Stats(count, days)
            },
            // P3：情绪事件写回长期记忆（只在亲近阶段、强度 ≥0.6、每天最多一条）
            memoryLongDao = database.memoryLongDao(),
        )
    }

    /** 统一待发队列（任务 3.1）：所有触发源投这里，发送层每次只取优先级最高的一条。 */
    val pendingMessageQueue: PendingMessageQueue by lazy {
        PendingMessageQueue(dao = database.pendingMessageDao())
    }

    /** AlarmManager 精确闹钟（任务 3.3）。 */
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(this) }

    /**
     * 环境钩子服务（V2.1.4）：整点检查（[AlarmKind.ENV_CHECK]）触发 recall → enqueue。
     * 依赖 [weatherCache] / [locationCache]（V2.1.1），装配见计划书 §3.11。
     */
    val environmentHookService: EnvironmentHookService by lazy {
        EnvironmentHookService(
            ruleEngine = EnvironmentRuleEngine(),
            appStateDao = database.appStateDao(),
            weatherCache = weatherCache,
            locationCache = locationCache,
            queue = pendingMessageQueue,
            // 环境总开关：关掉后 recall 直接返回 null（连天气/定位都不做）。
            // 每次调用现读，用户在设置页开关后立即生效（不必等进程重启）。
            enabled = { configRepository.getEnvironmentSettings().environmentEnabled }
        )
    }

    /**
     * 特殊日期钩子（V2.1.3 / V2.1.7）：用户长期记忆里的生日/纪念日 + 内置节日。
     * 构造签名以 [SpecialDateHookService] 实际声明为准：`(MemoryLongDao, FestivalProvider, matcher?)`。
     * 没有 DatabaseDateSource 这一层——日期源就是 `memoryLongDao`。
     */
    val specialDateHookService: SpecialDateHookService by lazy {
        SpecialDateHookService(
            memoryDao = database.memoryLongDao(),
            festivals = FestivalProvider(this),
        )
    }

    /** 提醒闹钟编排（任务 3.3）：用户话 → 提醒意图判断 → 解析时间 → setAlarmClock。 */
    /**
     * 提醒编排（任务 3.3 / B9）。**必须注入落库**：原实现从不写 `reminders` 表，
     * 于是提醒只活到进程结束、`BootReceiver` 的重启恢复恒无行可恢复。
     */
    val reminderService: ReminderService by lazy {
        ReminderService(
            scheduler = alarmScheduler,
            saveReminder = { type, taskDesc, triggerAtMillis ->
                database.reminderDao().insert(
                    ReminderEntity(
                        type = type,
                        taskDesc = taskDesc,
                        triggerTime = triggerAtMillis
                    )
                )
            }
        )
    }

    /** 今日已发自发消息计数（任务 3.5）：送达成功后 +1，供 3.4 每日上限使用。 */
    val proactiveSendCounter: ProactiveSendCounter by lazy {
        ProactiveSendCounter(appStateDao = database.appStateDao())
    }

    /**
     * 决策闸门（任务 3.4）：勿扰/冷却/每日上限/回复率/无钩子。
     * 每日已发条数接真实计数（[proactiveSendCounter]）；回复率待 5.x。
     */
    val proactiveGate: ProactiveGate by lazy {
        DefaultProactiveGate(
            // 任务 7.1/7.2：总开关、每日上限、勿扰全部来自用户设置；
            // 映射规则集中在 ProactiveSettingsMapping（纯逻辑、可单测）
            configProvider = {
                ProactiveSettingsMapping.gateConfig(
                    proactiveEnabled = configRepository.getProactiveEnabled(),
                    // P2：情绪微调每日上限（兴奋/想你 +1，低落/烦躁 -1），缓存见 EmotionProactivePolicy
                    dailyLimit = (configRepository.getProactiveDailyLimit() +
                        EmotionProactivePolicy.currentBonus()).coerceIn(0, 10),
                    dnd = configRepository.getDndSettings()
                )
            },
            lastInteractionProvider = {
                database.appStateDao().get(AppStateEntity.KEY_LAST_INTERACTION)?.toLongOrNull()
            },
            spontaneousSentTodayProvider = { proactiveSendCounter.countToday() },
            /**
             * TODO(V2.3 回复率统计)：**当前未接线，`LOW_REPLY_RATE` 规则不会触发。**
             *
             * 要真正启用需要「某条 SENT 的主动消息在 N 小时内有没有用户回复」的配对统计，
             * 结果写 `app_state[KEY_REPLY_STATS]`（key 已预留，JSON）后在这里读出来。
             *
             * 刻意**不**接一个假值：如果接成恒 0，规则会判定「回复率过低」把自发消息压到
             * 一天一次，比现在不触发更糟。数据不足仍返回 null 是安全侧。
             */
            replyRateProvider = { null },
            // 临时调试开关（测完把 ProactiveDebugFlags 改回 false）
            debugBypass = ProactiveDebugFlags.ENABLED && ProactiveDebugFlags.BYPASS_GATE
            // 勿扰不在这里拦（任务 4.3：勿扰只静音语音、通知照发）；是否连通知一起静音由 7.2 决定
        )
    }

    /**
     * 主动消息定时检查编排（任务 3.2）：过闸门 → 召回记忆钩子 → 投队列。
     * 召回走 [memoryRecallService]，未配 Key/异常打日志后跳过。
     */
    val proactiveCheckRunner: ProactiveCheckRunner by lazy {
        ProactiveCheckRunner(
            gate = proactiveGate,
            // V2.1.7：节日召回覆盖 SpecialDateHit 的两个子类——
            // User(用户生日/纪念日) 与 Festival(内置节日) 的 name 都提到基类上了。
            recallFestival = {
                try {
                    specialDateHookService.specialDatesOn(LocalDate.now()).firstOrNull()?.name
                } catch (e: Exception) {
                    android.util.Log.w("ProactiveCheck", "节日召回失败: ${e.message}", e)
                    null
                }
            },
            recallHook = {
                val cfg = configRepository.load()
                if (cfg.apiKey.isBlank()) {
                    android.util.Log.w("ProactiveCheck", "未配置 API Key，跳过主动检查")
                    null
                } else {
                    try {
                        memoryRecallService.recall(cfg)
                    } catch (e: Exception) {
                        android.util.Log.w("ProactiveCheck", "召回记忆失败: ${e.message}", e)
                        null
                    }
                }
            },
            queue = pendingMessageQueue,
            recallFallback = {
                // 2026-11-21：短期记忆为空时的兜底话题（长期记忆 → 纯关心）。
                // 真机事故：短期记忆被清空后检查一直 NO_HOOK，用户以为「随机关心坏了」。
                val dao = database.appStateDao()
                val nowMs = System.currentTimeMillis()

                // ① 长期记忆（每条 7 天内不重复）
                val forFallback = runCatching { database.memoryLongDao().getAll() }.getOrDefault(emptyList())
                    .sortedByDescending { it.createTime }
                    .map { ProactiveFallbackTopics.LongPick(it.id, it.content) }
                val usedMap = parseLongUsed(dao.get(AppStateEntity.KEY_PROACTIVE_LONG_USED))
                val pick = ProactiveFallbackTopics.pickLongMemory(forFallback, usedMap, nowMs)
                if (pick != null) {
                    val merged = pruneLongUsed(usedMap, nowMs) + (pick.id to nowMs)
                    dao.put(
                        AppStateEntity(
                            AppStateEntity.KEY_PROACTIVE_LONG_USED,
                            merged.entries.joinToString(",") { "${it.key}:${it.value}" }
                        )
                    )
                    android.util.Log.w("ProactiveCheck", "兜底话题=长期记忆 id=${pick.id}")
                    ProactiveFallbackTopics.longMemoryHook(pick.text)
                } else {
                    // ② 纯关心（每天最多 1 次 + 距上条主动消息 ≥3h）
                    val today = java.time.LocalDate.now().toString()
                    val lastKind = dao.get(AppStateEntity.KEY_PROACTIVE_KINDNESS_DATE)
                    val lastDelivered = dao.get(AppStateEntity.KEY_PROACTIVE_LAST_DELIVERED)?.toLongOrNull()
                    if (ProactiveFallbackTopics.kindnessAllowed(lastKind, today, lastDelivered, nowMs)) {
                        dao.put(AppStateEntity(AppStateEntity.KEY_PROACTIVE_KINDNESS_DATE, today))
                        android.util.Log.w("ProactiveCheck", "兜底话题=纯关心（今日首次）")
                        ProactiveFallbackTopics.KINDNESS_HOOK
                    } else {
                        null
                    }
                }
            },
            // V2.1.7：runner 传进来的是完整键 fes_sent_<name>_<dateKey>；
            // 查键与下面的记账共用 FestivalDedupe，避免两边算法分叉
            checkSent = { key -> database.appStateDao().get(key) != null }
        )
    }

    /**
     * 生成与送达编排（任务 3.5）：取队列消息 →（自发再跑闸门）→ 模型生成 → 入库 → 通知 →（语音）。
     * 语音默认关闭（[ProactiveVoicePolicy] 恒 false，待 4.3 接语音总开关 + 7.2 勿扰）。
     */
    val proactiveDeliveryRunner: ProactiveDeliveryRunner by lazy {
        ProactiveDeliveryRunner(
            queue = pendingMessageQueue,
            // P2：通知标题按她的情绪换措辞（兴奋 / 想你 / 低落…）
            emotionTitleProvider = { moodService.currentEmotionState().hers.emotions },
            gate = proactiveGate,
            generator = LlmProactiveMessageGenerator(
                apiClient = chatApiClient,
                memoryShortDao = database.memoryShortDao(),
                messageDao = database.messageDao(),
                conversationDao = database.conversationDao(),
                configProvider = { configRepository.load() },
                personaProvider = { configRepository.getPersona() },
                // 任务 5.2：当前 mood 由 MoodService 统一读取（5.1 写入的那条）
                moodProvider = { moodService.currentMood() },
                // 情绪系统 P1（2026-11-21）：主动消息也按「她此刻的情绪」追加颜文字
                // P2：「想你」只在主动消息路径积累（离开越久越强，封顶 0.7）
                emotionProvider = { moodService.emotionsWithAwayBoost() }
            ),
            writer = RoomConversationWriter(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao()
            ),
            notifier = AndroidProactiveNotifier(this),
            speaker = TtsProactiveSpeaker(readAloudController),
            // 任务 4.3：总开关 + 勿扰区间不念（通知仍发）
            voicePolicy = ProactiveVoicePolicy { now ->
                val enabled = configRepository.getProactiveVoiceEnabled()
                val dnd = configRepository.getDndEnabled()
                val speak = ProactiveVoiceRules.shouldSpeak(
                    nowMillis = now,
                    voiceEnabled = enabled,
                    dndEnabled = dnd,
                    dndStartMinute = configRepository.getDndStartMinute(),
                    dndEndMinute = configRepository.getDndEndMinute()
                )
                android.util.Log.d(
                    "ProactiveVoice",
                    "voiceEnabled=$enabled dnd=$dnd quietWindow=${configRepository.getDndStartMinute()}-${configRepository.getDndEndMinute()} -> speak=$speak"
                )
                speak
            },
                        onDelivered = {
                proactiveSendCounter.increment()
                // 2026-11-21：记下「上一条主动消息送达时刻」，纯关心的 ≥3h 间隔靠它
                database.appStateDao().put(
                    AppStateEntity(AppStateEntity.KEY_PROACTIVE_LAST_DELIVERED, System.currentTimeMillis().toString())
                )
            },
            /**
             * 环境钩子同日去重记账（V2.1.4）：**送达成功才写** `env_sent_<stableId>_<dateKey>`。
             *
             * `msg.type` 是 String，比较要 `.code`；去重键用 [EnvironmentHooks.dateKeyOf] 纯函数算，
             * 不能在回调里自己拼字符串（否则「查键」与「记账」一旦分叉，熬夜会连发两条）。
             * decode 失败/非 Environment → 不记账（防御：坏数据不该造成永久去重）。
             */
            onDeliveredMessage = { msg ->
                if (msg.type == PendingMessageType.ENVIRONMENT.code ||
                    msg.type == PendingMessageType.LATE_NIGHT.code
                ) {
                    val env = PendingMessageContent.decode(msg.content) as? PendingMessageContent.Environment
                    if (env != null) {
                        val day = EnvironmentHooks.dateKeyOf(env.stableId, System.currentTimeMillis(), ZoneId.systemDefault())
                        database.appStateDao().put(AppStateEntity("env_sent_${env.stableId}_$day", "1"))
                    }
                }
                // V2.1.7 节日记账（**只在送达成功后写**）：dateKey 必须用 msg.triggerTime（入队那天），
                // 不能用送达时刻——节日被勿扰延到次日送达时，用送达时刻会把键写到次日，
                // 次日检查查不到 → 同一个祝福连发两天。查键侧在 proactiveCheckRunner 里。
                if (msg.type == PendingMessageType.SPECIAL_DATE.code) {
                    val fest = PendingMessageContent.decode(msg.content) as? PendingMessageContent.Festival
                    if (fest != null) {
                        val key = FestivalDedupe.key(fest.name, msg.triggerTime, ZoneId.systemDefault())
                        database.appStateDao().put(AppStateEntity(key, "1"))
                    }
                }
            },
            // 任务 7.2：勿扰 + 区间内不发通知 → 把这条（含指令提醒）顺延到区间结束
            dndBlocksDelivery = {
                val dnd = configRepository.getDndSettings()
                DndPolicy.blocksNotification(dnd, System.currentTimeMillis())
            },
            dndEndMillisProvider = {
                val dnd = configRepository.getDndSettings()
                DndPolicy.quietHoursEndMillis(dnd, System.currentTimeMillis())
            },
            // BUG-1：指令提醒默认不被勿扰压掉，只有用户明确开「压掉我的提醒」才顺延
            dndBlocksInstruction = {
                val dnd = configRepository.getDndSettings()
                DndPolicy.blocksInstruction(dnd, System.currentTimeMillis())
            },
            scheduleDeferredDelivery = { delayMs -> ProactiveDeliveryWorker.enqueueDelayed(this, delayMs) }
        )
    }

    /**
     * 测试模式触发（任务 3.6）：设置页「立即触发一次主动消息」→ 绕闸门跑完整链路。
     * 召回钩子走 [memoryRecallService]（未配 Key 时按当前时间兜底生成）。
     */
    val proactiveTestTrigger: ProactiveTestTrigger by lazy {
        ProactiveTestTrigger(
            queue = pendingMessageQueue,
            deliverById = { id -> proactiveDeliveryRunner.deliverById(id, force = true) },
            recallHook = {
                val cfg = configRepository.load()
                if (cfg.apiKey.isBlank()) null
                else runCatching { memoryRecallService.recall(cfg) }.getOrNull()
            }
        )
    }

    /**
     * 保活跳转（任务 6.2）：识别 ROM → 依次尝试厂商自启动/耗电管理页 → 回落应用详情。
     * 只做跳转，不判断该不该弹（那是 [keepAliveService] 的事）。
     */
    val keepAliveLauncher: KeepAliveLauncher by lazy { KeepAliveLauncher(this) }

    /**
     * 保活引导状态（任务 6.2）：首次启动是否弹、设置页入口状态、标记已引导。
     * 依赖 [keepAliveLauncher] 提供 ROM 与白名单实况。
     */
    val keepAliveService: KeepAliveService by lazy {
        KeepAliveService(
            appStateDao = database.appStateDao(),
            rom = keepAliveLauncher.rom,
            ignoringBatteryOptimizations = { keepAliveLauncher.isIgnoringBatteryOptimizations() },
            notificationsEnabled = { systemPermissions.notificationsEnabled() }
        )
    }

    /** 系统权限状态（任务 7.1）：通知权限 / 精确闹钟 / 电池优化白名单。 */
    val systemPermissions: SystemPermissions by lazy { SystemPermissions(this) }

    /** 短期记忆淡忘衰减（任务 2.5）。每天一次 + 启动补跑；内部按天数差幂等。 */
    val memoryDecayService: MemoryDecayService by lazy {
        MemoryDecayService(
            shortDao = database.memoryShortDao(),
            appStateDao = database.appStateDao(),
            runInTransaction = { block -> database.withTransaction { block() } }
        )
    }

    /**
     * 本地 embedding 服务（任务 2.1）。首次 [ensureEmbeddingService] 时在 IO 线程
     * 落盘 86MB 模型并加载 ONNX session（重活）；模型缺失自动降级 Noop，不崩。
     */
    suspend fun ensureEmbeddingService(): EmbeddingService =
        EmbeddingServiceFactory.ensure(this)

    /** 同步窥视：已初始化返回实例，否则 Noop（供不想挂起的调用方）。 */
    fun embeddingServiceOrNoop(): EmbeddingService = EmbeddingServiceFactory.peek()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 2026-11-21：改为「自愈式」排程（系统时间被改过会把 WorkManager 的 next 推到几个月后）
        appScope.launch { ensureWorkSchedulesHealthy() }
        appScope.launch { migrateProactiveCountOnce() }
        appScope.launch { syncEnvCheckSchedule() }
        // 未来提醒的启动期轻量重排（B9 补充）：覆盖「闹钟被清但收不到 BOOT_COMPLETED」的
        // force-stop / ROM 清后台场景。只排 triggerTime > now，不会重放历史。
        appScope.launch { restorePendingRemindersLight() }
        // 环境设置变更 → 立即重新同步整点闹钟。挂在 ConfigRepository 的保存回调上，
        // 设置页任何一个保存入口都会覆盖到（不必依赖 UI 线额外接线）。
        configRepository.onEnvironmentSettingsChanged = {
            appScope.launch { syncEnvCheckSchedule() }
        }
    }

    /** 应用级后台协程（仅用于启动期的轻量一次性任务，不持有 UI）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 一次性修正「今日已发自发消息计数」的语义（1.0.3）。
     *
     * 老版本把**手动测试**（force 送达）也计入 `proactive_sent_count`，实测连着点几次
     * 按钮就把当日计数顶到 11，而每日上限只有 3 → 之后每 2 小时的定时检查全部被
     * `DAILY_LIMIT` 拦死，用户看到的现象就是「主动消息再也不发」。
     *
     * 新版不再让测试计数，因此存量里虚高的那个数必须清掉，否则升级当天仍然发不出去。
     * 写标记保证只执行一次。
     */
    private suspend fun migrateProactiveCountOnce() {
        runCatching {
            val dao = database.appStateDao()
            if (dao.get(AppStateEntity.KEY_PROACTIVE_COUNT_MIGRATION) == "true") return@runCatching
            dao.delete(AppStateEntity.KEY_PROACTIVE_SENT)
            dao.put(AppStateEntity(AppStateEntity.KEY_PROACTIVE_COUNT_MIGRATION, "true"))
            android.util.Log.w("ProactiveCounter", "已重置被手动测试污染的今日计数（一次性迁移）")
        }.onFailure {
            android.util.Log.w("ProactiveCounter", "计数迁移失败: ${it.message}")
        }
    }

    /**
     * 同步环境整点检查的排程状态（V2.1.5）。
     *
     * **四个分支都要处理**，只判「首次排」会漏掉开关联动的两个方向（Claude 审查项）：
     * | 开关 | 标记 | 动作 |
     * |---|---|---|
     * | 开 | 无 | 排一次 + 写标记（首次启动） |
     * | 开 | 有 | 不动（Receiver 会自己续排） |
     * | 关 | 有 | **cancel 闹钟 + 删标记**（否则已排的继续每小时 recall） |
     * | 关 | 无 | 不动 |
     *
     * 调用点（三处，全部在逻辑线内闭环，**不依赖 UI 线额外接线**）：
     * 1. [onCreate]：进程启动时同步一次
     * 2. [com.companion.chat.data.prefs.ConfigRepository.onEnvironmentSettingsChanged]：
     *    设置页保存 → 立即生效（打开开关立刻补排、关掉立刻取消）
     * 3. [com.companion.chat.worker.ProactiveCheckWorker] 每轮兜底同步（防止前两处因异常漏掉）
     *
     * 全程 runCatching：排/取消失败只该少一次环境检查，不该影响启动。
     *
     * @return 本次**新排**的下一次整点时间；未排（开关关闭 / 已在跑 / 失败）返回 null。
     *   只用于启动时打日志，调用方可忽略。
     */
    suspend fun syncEnvCheckSchedule(): Long? {
        return runCatching {
            val dao = database.appStateDao()
            val scheduled = dao.get(KEY_ENV_CHECK_SCHEDULED) != null
            val enabled = configRepository.getEnvironmentSettings().environmentEnabled

            if (!enabled) {
                if (scheduled) {
                    alarmScheduler.cancel(AlarmCodes.ENV_CHECK)
                    dao.delete(KEY_ENV_CHECK_SCHEDULED)
                    android.util.Log.d("EnvCheck", "环境感知已关闭，取消整点检查并清标记")
                }
                return@runCatching null
            }

            if (scheduled) return@runCatching null // 已在跑，Receiver 自会续排

            val next = EnvironmentCheckScheduler.nextWholeHour(System.currentTimeMillis())
            alarmScheduler.schedule(
                requestCode = AlarmCodes.ENV_CHECK,
                triggerAtMillis = next,
                taskDesc = "环境检查",
                reminderId = null,
                kind = AlarmKind.ENV_CHECK
            )
            dao.put(AppStateEntity(KEY_ENV_CHECK_SCHEDULED, "1"))
            android.util.Log.d("EnvCheck", "已排环境检查 @ $next")
            next
        }.onFailure {
            android.util.Log.w("EnvCheck", "同步环境排钟失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * 开机 / 应用升级后的闹钟恢复（B9）。由 [com.companion.chat.worker.BootReceiver] 调用。
     *
     * **背景**：设备重启（以及 App 升级）后 AlarmManager 里已排的闹钟会被系统清空，但
     * 持久化状态（`env_check_scheduled` 标记、`reminders` 表的 pending 行）都还在。
     * 不主动重排就是「状态还在、闹钟没了」——环境检查因标记假阳性而**永久停摆**，
     * 用户提醒则彻底不响。本方法做三件事：
     *
     * a) 环境检查**强制重排**：先删标记 + `cancel`（cancel 幂等，顺手清僵尸闹钟），
     *    再走正常同步逻辑。**不能只删标记不 cancel**，否则旧 PendingIntent 残留会造成
     *    同一 requestCode 上排两个闹钟。开关关着时不排（走 sync 的「关」分支）。
     * b) 用户提醒重排：`getPending()` 逐条按 `first(id)` 重排，带全
     *    `taskDesc / reminderId / kind`（EXTRA 由 [AlarmScheduler] 内部封装）。
     *    - `triggerTime > now` → 按原时间排；
     *    - `triggerTime <= now`（关机期间错过）→ **不丢、不改 done**，改成 `now + 5s`
     *      立即补发；到点后由 [com.companion.chat.worker.ReminderAlarmReceiver] 自己
     *      建短期记忆 + 投队列 + 触发送达，这里**不重复做业务动作**。
     * c) 调试链接续：调试模式下 WorkManager 链是「跑完自排下一轮」，重启会断；
     *    这里补一次 `enqueueFastCheck(0)`（仅 [ProactiveDebugFlags.ENABLED] 时）。
     *
     * WorkManager 的周期任务（主动检查 / 淡忘衰减）自带持久化，重启后系统会自己跑，**不在此重排**。
     *
     * 全程 runCatching 且逐条隔离：单条失败只影响那一条，返回的报告只用于日志核对。
     */
    suspend fun restoreAlarmsAfterBoot(): BootRestoreReport {
        val now = System.currentTimeMillis()

        // a) 环境检查：标记与闹钟都清掉，再走正常同步（开关关闭时不会重排）
        runCatching {
            database.appStateDao().delete(KEY_ENV_CHECK_SCHEDULED)
            alarmScheduler.cancel(AlarmCodes.ENV_CHECK)
        }.onFailure { android.util.Log.w("BootRestore", "清理环境检查标记失败: ${it.message}") }
        val envAt = syncEnvCheckSchedule()

        // b) 用户提醒：逐条重排；关机期间错过的改成立即补发（不丢也不改状态）。
        //    单纯的「时间怎么算」交给 BootRestorePlanner（纯函数、有 6 条 JVM 单测），
        //    这里只做副作用。plan 与 pending 同序，用下标一一对应，不用反查。
        val allPending = runCatching { database.reminderDao().getPending() }
            .onFailure { android.util.Log.w("BootRestore", "读取待发提醒失败: ${it.message}") }
            .getOrDefault(emptyList())
        // 审查 R1：**过期追问行不重放追问，但必须补 TIMEOUT 钟**。
        // 一条 followup 行发过追问后 `triggerTime` 就已经是过去（此刻在等 TIMEOUT），恢复路径
        // 会把它当「关机期间错过」→ 改成 now+5s 再发一条**重复追问**并让 attemptCount 再 +1。
        // 但**直接丢掉更糟**：重启后 AlarmManager 全空，「等 TIMEOUT 自己推进」并不存在，
        // 过滤掉就等于这行永久 pending、没有任何闹钟指向它 → `anyPendingFollowup()` 恒真
        // → 之后所有「监督我…」被 V2.2.3 静默挡住（正是本轮要拆的雷换个形态）。
        // 所以：拆出来 → 等回应的行各补一个 TIMEOUT 钟（恢复到「刚发过、在等回复」）；
//       首钟就错过（attemptCount==0）的行留在 pending 里，由 planner 按 kind=FOLLOWUP
        //       重放成 now+5s 的第 1 条追问。逻辑在 [BootFollowupRestorer]（纯 JVM 可测），这里只装配。
        val followupRestore = followupBootRestorer.splitAndRestoreTimeouts(allPending, at = now)
        val pending = followupRestore.pending
        val plan = BootRestorePlanner.plan(
            pending = pending.map { Triple(it.id, it.triggerTime, it.type) },
            now = now,
            delayMs = OVERDUE_REPLAY_DELAY_MS
        )
        var restored = 0
        var overdue = 0
        for (i in plan.indices) {
            val reminder = pending[i]
            val target = plan[i]
            runCatching {
                alarmScheduler.schedule(
                    requestCode = target.requestCode,
                    triggerAtMillis = target.atMillis,
                    taskDesc = reminder.taskDesc,
                    reminderId = reminder.id,
                    kind = target.kind
                )
                restored++
                if (target.overdue) overdue++
            }.onFailure {
                android.util.Log.w(
                    "BootRestore",
                    "重排提醒失败 id=${reminder.id}（其余继续）: ${it.message}"
                )
            }
        }

        // c) 调试链接续（仅调试模式；正式版走 2h 周期任务，自带持久化）
        if (ProactiveDebugFlags.ENABLED) {
            runCatching { enqueueFastCheck(0L) }
                .onFailure { android.util.Log.w("BootRestore", "接续调试快速检查失败: ${it.message}") }
        }

        return BootRestoreReport(
            envScheduledAt = envAt,
            remindersPending = pending.size,
            remindersRestored = restored,
            remindersOverdue = overdue,
            followupTimeouts = followupRestore.followupTimeouts
        )
    }

    /**
     * 轻量重排（B9 补充）：App 每次启动把**未来**的待发提醒重新排上。
     *
     * 覆盖另一类「闹钟没了但拿不到 `BOOT_COMPLETED`」的失效：用户手动「强行停止」、
     * 或 ROM 清后台把 App 置为 stopped —— 闹钟被系统清掉，但停止态的应用收不到开机广播。
     *
     * **只排 `triggerTime > now` 的普通行**：过期的非追问行交给 [restoreAlarmsAfterBoot]
     * （真重启路径），否则每次打开 App 都会把历史 pending 重放一遍。
     * 这个前提由 `ReminderAlarmReceiver.handleInstruction` 投队成功后标 `done` 保证
     * （不发过的行不会一直挂 pending）。
     *
     * 追问行是例外（两处都要补钟，否则链永久 pending、`anyPendingFollowup()` 恒真）：
     *  - 「已发过追问、等回复」→ [BootFollowupRestorer.splitAndRestoreTimeouts] 补 TIMEOUT 钟；
     *  - 仍留在 pending 里的过期追问（首钟错过 / 补 TIMEOUT 失败退回）
     *    → [BootFollowupRestorer.scheduleOverdueFollowups] 补一次 FOLLOWUP 钟。
     *
     * @return **只统计未来行**排上的条数；补 TIMEOUT / 补 FOLLOWUP 的条数请看
     *   `BootRestore` 日志（本方法里一并打出来），不要拿这个返回值相加。
     */
    suspend fun restorePendingRemindersLight(): Int = runCatching {
        val now = System.currentTimeMillis()
        val all = database.reminderDao().getPending()
        // R1 第二轮：**这条路径同样会丢闹钟** —— force-stop / ROM 清后台都会清 `AlarmManager`，
        // 却不会发 `BOOT_COMPLETED`，所以 boot 路径的修复覆盖不到它。
        val restoredFollowups = followupBootRestorer.splitAndRestoreTimeouts(all, at = now)
        // R1 第三轮（P1）：补 TIMEOUT 成功的行已被移出 pending；**仍留在 pending 里的过期追问**
        // （首钟错过 / 补 TIMEOUT 失败退回）必须在这里补一次 FOLLOWUP 钟 —— 下一步
        // `filter { triggerTime > now }` 会把它们全部滤掉，不补就是零闹钟、链永久停摆。
        val firstFollowups = followupBootRestorer.scheduleOverdueFollowups(
            rows = restoredFollowups.pending,
            at = now,
            replayDelayMs = OVERDUE_REPLAY_DELAY_MS
        )
        val future = restoredFollowups.pending.filter { it.triggerTime > now }
        // V2.2.3 连带修复（坑 B）：恢复也按 type 分配 code/kind，followup 行不能再被
        // 当成首钟 INSTRUCTION 恢复，否则到点 handleInstruction 会把它标 done、吞掉整条链。
        val plan = BootRestorePlanner.plan(
            pending = future.map { Triple(it.id, it.triggerTime, it.type) },
            now = now
        )
        var restored = 0
        for (i in plan.indices) {
            val reminder = future[i]
            val target = plan[i]
            val ok = runCatching {
                alarmScheduler.schedule(
                    requestCode = target.requestCode,
                    triggerAtMillis = target.atMillis,
                    taskDesc = reminder.taskDesc,
                    reminderId = reminder.id,
                    kind = target.kind
                )
            }.onFailure {
                android.util.Log.w("BootRestore", "轻量重排提醒失败 id=${reminder.id}: ${it.message}")
            }.isSuccess
            if (ok) restored++
        }
        // P3：返回值只代表「未来行」；补钟条数在这里留痕，方便真机核对「0 条重排但库里有追问行」。
        android.util.Log.d(
            "BootRestore",
            "轻量重排：未来行 $restored 条｜补 TIMEOUT 钟 ${restoredFollowups.followupTimeouts} 条｜" +
                "补过期追问 FOLLOWUP 钟 $firstFollowups 条"
        )
        restored
    }.getOrDefault(0)

    /**
     * 周期任务排程自愈（2026-11-21）。
     *
     * 事故：用户把系统时间改到 2027-02-14 时排了 2h 周期任务（KEEP 不重置），
     * 时间改回 2026-11-20 后 WorkManager 算出的下次运行在 2027-02-15 —— 主动消息静默停摆 3 个月。
     *
     * 处理：读「上次排程时刻」，若从未记录 / 时钟回拨 / 超过 3 个周期没重排，
     * 就用 REPLACE 重排一次（周期从此刻重新计时，PeriodicWork 首次立即执行）。
     * 正常重启（距上次 < 3 周期）走 KEEP，不重置周期。
     */
    private suspend fun ensureWorkSchedulesHealthy() {
        runCatching {
            val dao = database.appStateDao()
            val now = System.currentTimeMillis()
            val proactivePeriod = ProactiveCheckWorker.PERIOD_HOURS * 60 * 60 * 1000L
            val decayPeriod = 24L * 60 * 60 * 1000L

            val lastProactive = dao.get(AppStateEntity.KEY_PROACTIVE_SCHEDULED_AT_V2)?.toLongOrNull()
            if (WorkScheduleHealth.needsReschedule(lastProactive, now, proactivePeriod)) {
                android.util.Log.w("ProactiveCheck", "重排周期任务：上次排程=${lastProactive ?: "无"}，现在=$now")
                scheduleProactiveCheck(replace = true)
            }
            dao.put(AppStateEntity(AppStateEntity.KEY_PROACTIVE_SCHEDULED_AT_V2, now.toString()))

            val lastDecay = dao.get(AppStateEntity.KEY_DECAY_SCHEDULED_AT)?.toLongOrNull()
            val decayReplace = WorkScheduleHealth.needsReschedule(lastDecay, now, decayPeriod)
            if (decayReplace) {
                android.util.Log.w("MemoryDecay", "重排周期任务：上次排程=${lastDecay ?: "无"}，现在=$now")
            }
            scheduleMemoryDecay(replace = decayReplace)
            dao.put(AppStateEntity(AppStateEntity.KEY_DECAY_SCHEDULED_AT, now.toString()))
        }.onFailure {
            android.util.Log.w("ProactiveCheck", "排程自愈检查失败（沿用当前排程）：${it.message}")
        }
    }

    /** 调度每日淡忘衰减：周期 24h（KEEP）+ 启动补跑一次（幂等）。 */
    private fun scheduleMemoryDecay(replace: Boolean = false) {
        val wm = WorkManager.getInstance(this)
        val daily = PeriodicWorkRequestBuilder<MemoryDecayWorker>(1, TimeUnit.DAYS).build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork("memory_decay_daily", policy, daily)
        val catchUp = OneTimeWorkRequestBuilder<MemoryDecayWorker>().build()
        // 一次性补跑任务用 REPLACE：KEEP 会让「排过但没跑成」的残留一直挂着（真机见过 01:57 挂到中午）
        val oneShot = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork("memory_decay_catchup", oneShot, catchUp)
    }

    /**
     * 调度主动消息定时检查（任务 3.2）：每 2h 周期 + 需联网（[NetworkType.CONNECTED]）。
     * 无网时 WorkManager 不执行，恢复网络后自动执行；息屏/Doze 由 WorkManager 兜底。
     * KEEP 避免重复入队重置周期。
     */
    private fun scheduleProactiveCheck(replace: Boolean = false) {
        val wm = WorkManager.getInstance(this)
        if (ProactiveDebugFlags.ENABLED) {
            // 调试模式：WorkManager 周期任务下限 15 分钟，做不到 1 分钟，
            // 所以这里取消 2h 周期任务，改走「一次性任务跑完自排队」的链式调度。
            wm.cancelUniqueWork(ProactiveCheckWorker.WORK_NAME)
            enqueueFastCheck(0L)
            android.util.Log.w(
                "ProactiveCheck",
                "调试快速检查已启用：每 ${ProactiveDebugFlags.INTERVAL_SECONDS}s 一轮，" +
                    "绕过闸门=${ProactiveDebugFlags.BYPASS_GATE}"
            )
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        // 清掉历史排程名字：KEEP 不会替换旧名字的任务，留着会让「旧周期 + 新周期」同时跑
        ProactiveCheckWorker.LEGACY_WORK_NAMES
            .filter { it != ProactiveCheckWorker.WORK_NAME }
            .forEach { legacy ->
                wm.cancelUniqueWork(legacy)
                android.util.Log.w("ProactiveCheck", "清理历史排程 $legacy")
            }
        // 调试链残留（调试期间用的是一次性任务链，恢复后不该再跑）
        wm.cancelUniqueWork(ProactiveCheckWorker.FAST_WORK_NAME)
        val request = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(
            ProactiveCheckWorker.PERIOD_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        if (replace) {
            // 真机实测：`ExistingPeriodicWorkPolicy.REPLACE` 在这里**静默不生效** —— 旧 WorkSpec 原样留着
            // （last_enqueue_time 仍是 2027-02-14），下次运行还是几个月后。改成「同步取消 + KEEP 入队」，
            // 先确认旧任务真的删掉了，再排新的（周期从此刻重新计时，PeriodicWork 首次立即执行）。
            runCatching {
                wm.cancelUniqueWork(ProactiveCheckWorker.WORK_NAME)
                    .result.get(10, TimeUnit.SECONDS)
                android.util.Log.w("ProactiveCheck", "已取消旧周期任务（等待完成）")
            }.onFailure {
                android.util.Log.w("ProactiveCheck", "取消旧周期任务失败：${it.message}")
            }
        }
        wm.enqueueUniquePeriodicWork(
            ProactiveCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 解析 `id:ts,id:ts` → Map（兜底话题的长期记忆冷却记录）。坏段直接跳过。 */
    private fun parseLongUsed(raw: String?): Map<Long, Long> =
        raw.orEmpty().split(',').mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i <= 0) null else {
                val id = seg.substring(0, i).toLongOrNull()
                val ts = seg.substring(i + 1).toLongOrNull()
                if (id != null && ts != null) id to ts else null
            }
        }.toMap()

    /** 只保留冷却期内的记录（更早的没用了，避免这个 CSV 无限增长）。 */
    private fun pruneLongUsed(used: Map<Long, Long>, now: Long): Map<Long, Long> =
        used.filterValues { now - it < ProactiveFallbackTopics.LONG_COOLDOWN_MS }

    /** 调试用：排一次快速检查（[delaySeconds] 秒后执行）。 */
    private fun enqueueFastCheck(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<ProactiveCheckWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            ProactiveCheckWorker.FAST_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        lateinit var instance: CompanionApp
            private set

        /** 首次环境整点检查是否已排（V2.1.5，防每次启动重排）。 */
        private const val KEY_ENV_CHECK_SCHEDULED = "env_check_scheduled"

        /**
         * 重启恢复时「关机期间错过」的提醒延迟多久补发（B9）。
         *
         * 取 5s 而不是 0：开机瞬间系统在恢复大量服务，立刻响闹钟容易和开机动画/首屏抢资源被吞；
         * 5s 后触发既够快（用户感知是「刚开机就提醒」），又给接收器留出被调起的时间。
         */
        private const val OVERDUE_REPLAY_DELAY_MS = 5_000L

        /**
         * 调试链自排队：快速检查跑完一轮后，排下一轮（[ProactiveDebugFlags.INTERVAL_SECONDS] 秒后）。
         * 只在 [ProactiveDebugFlags.ENABLED] 时由 [com.companion.chat.worker.ProactiveCheckWorker] 调用。
         */
        fun scheduleNextFastCheck(context: android.content.Context) {
            val request = OneTimeWorkRequestBuilder<ProactiveCheckWorker>()
                .setInitialDelay(ProactiveDebugFlags.INTERVAL_SECONDS, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ProactiveCheckWorker.FAST_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

/**
 * 重启恢复的结果（B9）。只用于 [com.companion.chat.worker.BootReceiver] 打日志核对，
 * 不参与任何业务判断——恢复失败也只是「这次没排上」，下次启动/巡检会再补。
 *
 * @param envScheduledAt 本次新排的环境检查整点时间；null = 未排（开关关闭 / 失败）
 * @param remindersPending 重启时 `state='pending'` 的提醒总数
 * @param remindersRestored 真正重排成功的条数（可能小于总数：单条失败已记日志）
 * @param remindersOverdue 其中「关机期间已错过」、改为 now+5s 立即补发的条数
 */
data class BootRestoreReport(
    val envScheduledAt: Long?,
    val remindersPending: Int,
    val remindersRestored: Int,
    val remindersOverdue: Int,
    /**
     * 过期追问行补上的 TIMEOUT 钟条数（V2.2.4 审查 R1）。
     *
     * 这些行**不在** [remindersPending] 里（它们不走「按 triggerTime 重排」那条路），
     * 单独报数是为了让日志能对上「库里 2 条 followup、重排 0 条」这种看似矛盾的现象：
     * 0 条重排 + 2 条补钟 = 正确，而不是丢了。
     */
    val followupTimeouts: Int = 0
)
