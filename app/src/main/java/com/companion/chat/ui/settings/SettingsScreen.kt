package com.companion.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.companion.chat.data.environment.CityGeocoder
import com.companion.chat.data.environment.GeocodeOutcome
import com.companion.chat.data.environment.LocationCache
import com.companion.chat.data.power.SystemPermissionStatus
import com.companion.chat.data.remote.ProviderPresets
import com.companion.chat.data.system.KeepAliveScriptRunner
import com.companion.chat.data.tts.TtsEngineCatalog
import com.companion.chat.domain.mood.MoodTone
import com.companion.chat.domain.power.KeepAliveGuide
import com.companion.chat.domain.power.KeepAliveStatus
import com.companion.chat.domain.power.KeepAliveTarget
import com.companion.chat.domain.proactive.ProactiveGateRules
import com.companion.chat.domain.settings.EnvironmentSettings
import com.companion.chat.ui.permission.rememberLocationPermission
import kotlinx.coroutines.launch

/** 设置表单数据。任务 1.5（设置页 = 1.5 人设 + 7.1 部分） */
data class SettingsForm(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val visionModel: String,
    val persona: String,
    val contextWindow: Int,
    // DeepSeek 新 API 相关开关（必须与 ConfigRepository/ApiConfig 双向映射，否则保存会被默认值覆盖）
    val thinkingEnabled: Boolean = false,
    val deepSeekTtsEnabled: Boolean = false,
    val ttsVoice: String = "stella",
    // 语音总开关 / 语速 / 系统引擎（任务 4.1）
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsSystemEngine: String? = null,
    // V2.3.5：语音输入（STT）开关，默认开（计划书 L1101）；v3.4 云端方案无引擎/模型配置
    val sttEnabled: Boolean = true,
    // V2.3.6：通知栏常驻快捷入口（默认关，与 ConfigRepository 默认值一致）
    val quickAccessEnabled: Boolean = false,
    // 主动消息语音 + 勿扰（任务 4.3）
    val proactiveVoiceEnabled: Boolean = false,
    val dndEnabled: Boolean = false,
    // 主动消息总开关 / 每日上限（任务 7.1）
    val proactiveEnabled: Boolean = true,
    val proactiveDailyLimit: Int = ProactiveGateRules.DEFAULT_DAILY_LIMIT,
    // 勿扰区间 + 「区间内是否发通知」独立开关（任务 7.2）
    val dndStartMinute: Int = 23 * 60,
    val dndEndMinute: Int = 7 * 60,
    val dndSuppressNotification: Boolean = true,
    // BUG-1：勿扰期间是否连「我自己设的提醒」也压掉（默认关 = 闹钟准时）
    val dndSuppressInstruction: Boolean = false,
    val latestReminderEnabled: Boolean = true,
    val autoTitleEnabled: Boolean = true,
    val jsonModeDefault: Boolean = false,
    // ---- 任务 V2.1.6：环境感知（默认值与 EnvironmentSettings 一致） ----
    val environmentEnabled: Boolean = EnvironmentSettings.DEFAULT_ENVIRONMENT_ENABLED,
    val lateNightEnabled: Boolean = EnvironmentSettings.DEFAULT_LATE_NIGHT_ENABLED,
    val lateNightHour: Int = EnvironmentSettings.DEFAULT_LATE_NIGHT_HOUR,
    val rainThreshold: Int = EnvironmentSettings.DEFAULT_RAIN_THRESHOLD,
    val tempDropThreshold: Int = EnvironmentSettings.DEFAULT_TEMP_DROP_THRESHOLD,
    /** 手动城市名（只读回显；由 LaunchedEffect(locationCache) 填充） */
    val manualCity: String = ""
)

private data class ProviderOption(val id: String, val label: String)

private val providers = listOf(
    ProviderOption(ProviderPresets.DEEPSEEK, "DeepSeek"),
    ProviderOption(ProviderPresets.OPENAI, "OpenAI"),
    ProviderOption(ProviderPresets.CUSTOM, "自定义（OpenAI 兼容）")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: SettingsForm,
    onSave: (SettingsForm) -> Unit,
    onBack: () -> Unit,
    onPersist: (SettingsForm) -> Unit = {},
    onTestProactive: () -> Unit = {},
    // ---- 以下为任务 7.1/7.2 新增：只读状态 + 跳系统设置的回调 ----
    permissionStatus: SystemPermissionStatus = SystemPermissionStatus(true, true, false),
    keepAliveStatus: KeepAliveStatus? = null,
    currentMood: String = "normal",
    /**
     * 模型最近一次给的判定理由（`app_state[mood_reason]`，2026-11-20 起显示）。
     *
     * 以前只写库不显示 —— 用户能看到「平常」却看不到「为什么平常」，
     * 排查「怎么突然变低落了」时只能翻数据库。空串 = 还没跑过判定，整行不占位。
     */
    currentMoodReason: String = "",
    /** 情绪系统 P1（2026-11-21）：她的情绪摘要（`委屈 0.6 ｜因为：… ｜8 分钟前`）。 */
    emotionSummary: String = "平静",
    /** P3（2026-11-21）：关系阶段展示，如「亲近（已聊 213 条，第 5 天）」。 */
    relationshipText: String = "生疏",
    /** P3.1（2026-11-21）：热络度展示，如 `0.35（有点生分，久没聊会有点生分）`。 */
    warmthText: String = "0.5（还行）",
    onRefreshPermissions: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenBatteryOptimization: () -> Unit = {},
    onOpenAutostart: () -> Unit = {},
    onOpenBackgroundSettings: () -> Unit = {},
    onResetKeepAliveGuide: () -> Unit = {},
    // ---- 任务 V2.1.6：环境感知 / 手动城市 / 关于 ----
    // 逻辑注入路径：CompanionApp 补齐 locationCache/cityGeocoder 后由 MainActivity 传入。
    // 城市为空 = 未注入 → 输入框与搜索按钮自动隐藏。
    cityGeocoder: CityGeocoder? = null,
    locationCache: LocationCache? = null
) {
    var form by remember { mutableStateOf(initial) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    // V2.4.3：一键保活。runner 只在这里 new，**不进 CompanionApp**（零启动副作用、零调用点）。
    // 按钮永远可点：点下去才 runner.run(pkg)，它内部自己 probe 判 root（非 root → failure 原文提示），
    // 全流程只 probe 一次。刻意不做「进页面先探测 root 再决定按钮可用性」——那会一进设置页就弹 Magisk 框。
    val keepAliveRunner = remember { KeepAliveScriptRunner() }
    var keepAliveState by remember { mutableStateOf<KeepAliveUiState>(KeepAliveUiState.Idle) }
    var showNeedRootDialog by remember { mutableStateOf(false) }
    // 日志对话框开关独立于 keepAliveState：关掉对话框后状态要保留，否则日志就没了
    var showLogDialog by remember { mutableStateOf(false) }
    // V2.1.1：粗定位权限状态（设置页只提供按钮，进页面不自动弹）
    val locationPermission = rememberLocationPermission(autoRequestOnce = false)

    // 手动城市回显 + 搜索（任务 V2.1.6）
    var manualCity by remember { mutableStateOf(initial.manualCity) }
    var citySearching by remember { mutableStateOf(false) }

    // 注入路径确认：LocationCache 在 app 容器；这里只拿结果回显
    LaunchedEffect(locationCache) {
        locationCache?.let { manualCity = it.manualCityName() ?: "" }
    }

    suspend fun searchCity(query: String) {
        val geocoder = cityGeocoder ?: return
        citySearching = true
        val outcome = runCatching { geocoder.resolve(query) }.getOrElse { GeocodeOutcome.NetworkError }
        citySearching = false
        when (outcome) {
            is GeocodeOutcome.Hit -> {
                Toast.makeText(context, "已设为手动城市：${outcome.name}", Toast.LENGTH_SHORT).show()
                manualCity = outcome.name
            }
            GeocodeOutcome.NoMatch -> {
                Toast.makeText(context, "找不到该城市，请检查拼写", Toast.LENGTH_SHORT).show()
            }
            GeocodeOutcome.NetworkError -> {
                Toast.makeText(context, "网络异常，使用上次位置", Toast.LENGTH_SHORT).show()
            }
            GeocodeOutcome.Cleared -> {
                // 不弹 Toast（计划书约定）
                manualCity = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("模型与服务商", style = MaterialTheme.typography.titleMedium)

            DropdownField(
                label = "服务商",
                value = providers.firstOrNull { it.id == form.providerId }?.label ?: form.providerId,
                options = providers.map { it.label },
                onSelect = { idx ->
                    val p = providers[idx]
                    form = form.copy(
                        providerId = p.id,
                        baseUrl = ProviderPresets.defaultBaseUrl(p.id),
                        model = ProviderPresets.models(p.id).firstOrNull() ?: "",
                        visionModel = ProviderPresets.defaultVisionModel(p.id)
                    )
                }
            )

            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { form = form.copy(baseUrl = it) },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { form = form.copy(apiKey = it) },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            val modelOptions = ProviderPresets.models(form.providerId)
            if (modelOptions.isNotEmpty()) {
                DropdownField(
                    label = "模型",
                    value = form.model,
                    options = modelOptions,
                    onSelect = { idx ->
                        val m = modelOptions[idx]
                        form = form.copy(
                            model = m,
                            contextWindow = ProviderPresets.defaultContextWindow(m)
                        )
                    }
                )
            } else {
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { form = form.copy(model = it) },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = form.visionModel,
                onValueChange = { form = form.copy(visionModel = it) },
                label = { Text("识图模型（多模态，带图时使用）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "DeepSeek 留空即用主模型 deepseek-flash（原生视觉）；OpenAI 填 gpt-4o。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Text("功能开关（DeepSeek 新 API）", style = MaterialTheme.typography.titleMedium)
            SwitchRow("深度思考（慢、更聪明，默认关）", form.thinkingEnabled) {
                form = form.copy(thinkingEnabled = it)
            }
            SwitchRow("自动生成会话标题", form.autoTitleEnabled) {
                form = form.copy(autoTitleEnabled = it)
            }
            SwitchRow("每轮注入时间/地点元数据（latest_reminder）", form.latestReminderEnabled) {
                form = form.copy(latestReminderEnabled = it)
            }
            SwitchRow("官方语音（联网计费）", form.deepSeekTtsEnabled) {
                form = form.copy(deepSeekTtsEnabled = it)
            }
            if (form.deepSeekTtsEnabled) {
                val voiceOptions = listOf("stella", "tide")
                DropdownField(
                    label = "音色",
                    value = form.ttsVoice,
                    options = voiceOptions,
                    onSelect = { idx -> form = form.copy(ttsVoice = voiceOptions[idx]) }
                )
                Text(
                    "官方 TTS 约 \$0.45/万字符（限时五折 0.225）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SwitchRow("语音总开关（关闭则完全不朗读）", form.ttsEnabled) {
                form = form.copy(ttsEnabled = it)
            }

            // V2.3.5：语音输入（STT）总开关。与上面的朗读开关同属「语音」区块，但互不影响：
            // 朗读是输出，这里是输入（长按说话）。关闭后聊天页麦克风按钮隐藏、且不创建录音控制器。
            SwitchRow("语音输入（长按说话）", form.sttEnabled) {
                form = form.copy(sttEnabled = it)
            }
            Text(
                "关闭后输入框的麦克风按钮会隐藏，不会开麦。识别使用与聊天相同的 API Key（云端，按秒计费约 0.39 元/小时）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // V2.3.6：通知栏常驻快捷入口（关掉时 persist 会当场 sync 撤掉通知）
            SwitchRow("通知栏常驻快捷入口（低优先级，可随时关闭）", form.quickAccessEnabled) {
                form = form.copy(quickAccessEnabled = it)
            }
            Text(
                "开启后通知栏常驻一条低优先级通知，点「🎙 语音输入」可直接说话，识别结果会填进输入框。需要通知权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (form.ttsEnabled) {
                Text(
                    "语速：${"%.1f".format(form.ttsSpeechRate)}×",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = form.ttsSpeechRate,
                    onValueChange = { form = form.copy(ttsSpeechRate = it) },
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )
                val engines = remember { TtsEngineCatalog.available(context) }
                if (engines.isEmpty()) {
                    Text(
                        "未检测到系统语音引擎，请安装「Google 文字转语音」等引擎（当前仅能用官方语音）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    val engineLabels = listOf("系统默认") + engines.map { it.label }
                    val currentLabel = form.ttsSystemEngine
                        ?.let { name -> engines.firstOrNull { it.name == name }?.label }
                        ?: "系统默认"
                    DropdownField(
                        label = "系统语音引擎",
                        value = currentLabel,
                        options = engineLabels,
                        onSelect = { idx ->
                            form = form.copy(
                                ttsSystemEngine = if (idx == 0) null else engines[idx - 1].name
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("主动消息", style = MaterialTheme.typography.titleMedium)
            SwitchRow("主动消息总开关（关闭后不再主动搭话）", form.proactiveEnabled) {
                form = form.copy(proactiveEnabled = it)
            }
            if (form.proactiveEnabled) {
                Text(
                    "每日自发消息上限：" + form.proactiveDailyLimit + " 条" +
                        if (form.proactiveDailyLimit == 0) "（= 完全不自发，只发你自己设的提醒）" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = form.proactiveDailyLimit.toFloat(),
                    onValueChange = { form = form.copy(proactiveDailyLimit = it.toInt()) },
                    valueRange = 0f..10f,
                    steps = 9
                )
            }
            Text(
                "当前心情：${MoodTone.label(currentMood)}（影响主动消息语气）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "你们的关系：$relationshipText（决定她能不能撒娇）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "当前热络度：$warmthText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "备注：关系 = 你们聊了多久、能不能撒娇（只升不降）；" +
                    "热络度 = 最近聊得勤不勤（久不聊会变淡，但关系不会退）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                "她的情绪：$emotionSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (currentMoodReason.isNotBlank()) {
                // 理由比标签有用：标签只有「平常/心情不错/有点低落」三档，理由能看出模型是怎么判的。
                Text(
                    "判定理由：$currentMoodReason（每 5 轮对话更新一次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SwitchRow("主动消息自动语音（通知弹出后后台念）", form.proactiveVoiceEnabled) {
                form = form.copy(proactiveVoiceEnabled = it)
            }
            SwitchRow("勿扰模式", form.dndEnabled) {
                form = form.copy(dndEnabled = it)
            }
            if (form.dndEnabled) {
                val hourOptions = (0..23).map { "%02d:00".format(it) }
                DropdownField(
                    label = "勿扰开始",
                    value = "%02d:%02d".format(form.dndStartMinute / 60, form.dndStartMinute % 60),
                    options = hourOptions,
                    onSelect = { idx -> form = form.copy(dndStartMinute = idx * 60) }
                )
                DropdownField(
                    label = "勿扰结束",
                    value = "%02d:%02d".format(form.dndEndMinute / 60, form.dndEndMinute % 60),
                    options = hourOptions,
                    onSelect = { idx -> form = form.copy(dndEndMinute = idx * 60) }
                )
                SwitchRow("区间内也不发 AI 消息（彻底安静；关闭则只静音语音）", form.dndSuppressNotification) {
                    form = form.copy(dndSuppressNotification = it)
                }
                SwitchRow("区间内也压掉我自己的提醒（默认关；关着时闹钟照常准时响）", form.dndSuppressInstruction) {
                    form = form.copy(dndSuppressInstruction = it)
                }
                Text(
                    "区间内 AI 主动消息一律不自动念语音。开着「不发 AI 消息」时，闲聊/追问/节日/环境会顺延到区间结束；" +
                        "你自己设的提醒默认仍然准时送达，只有打开上面第二个开关才会被一起顺延。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("环境感知", style = MaterialTheme.typography.titleMedium)
            Text(
                "熬夜 / 下雨 / 降温时主动关心你。天气按「手动城市」查，没设城市则只用时间规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // V2.1.1：定位权限只在「环境感知开着」时才相关，放在下面 if 块内（缺陷 C）

            SwitchRow("环境感知总开关（关闭后不再提醒熬夜/下雨/降温）", form.environmentEnabled) {
                form = form.copy(environmentEnabled = it)
            }

            if (form.environmentEnabled) {
                // V2.1.1：定位权限（只申请粗定位）。关掉总开关后不再显示——那时根本不需要定位
                if (!locationPermission.granted) {
                    Text(
                        "未授权定位，天气提醒不可用；可在设置里手动填城市",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        onClick = { locationPermission.request() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (locationPermission.needsAppSettings) "已被系统拒绝，去设置里开启定位"
                            else "授权定位（仅需大致位置）"
                        )
                    }
                } else {
                    Text(
                        "已授权定位（大致位置）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SwitchRow("熬夜提醒（过了设点的整点才提醒）", form.lateNightEnabled) {
                    form = form.copy(lateNightEnabled = it)
                }

                Text(
                    "降水概率阈值（%）：${form.rainThreshold}（概率严格大于它才提醒带伞）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = form.rainThreshold.toFloat(),
                    onValueChange = { form = form.copy(rainThreshold = it.toInt()) },
                    valueRange = 1f..100f,
                    steps = 99
                )

                Text(
                    "降温提醒阈值（℃）：${form.tempDropThreshold}（降幅严格大于它才提醒添衣）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = form.tempDropThreshold.toFloat(),
                    onValueChange = { form = form.copy(tempDropThreshold = it.toInt()) },
                    valueRange = EnvironmentSettings.MIN_TEMP_DROP_THRESHOLD.toFloat()..30f,
                    steps = 28
                )
            }

            // ---- 手动城市（V2.1.6）----
            Text("手动城市", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    cityGeocoder == null ->
                        "城市搜索待接线（CompanionApp 尚未注入 CityGeocoder / LocationCache）。以下输入框先禁用。"
                    manualCity.isBlank() ->
                        "当前使用自动定位。输入城市名后搜索，可设为手动城市（此后天气以它为准）。"
                    else ->
                        "当前手动城市：$manualCity（天气以它为准）。清空输入框再点搜索，可恢复自动定位。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cityGeocoder == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = manualCity,
                onValueChange = { manualCity = it },
                label = { Text("城市名（如：北京）") },
                singleLine = true,
                enabled = cityGeocoder != null,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { scope.launch { searchCity(manualCity) } },
                    enabled = cityGeocoder != null && !citySearching,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (citySearching) "搜索中..." else "搜索并设为手动城市")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("人设", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = form.persona,
                onValueChange = { form = form.copy(persona = it) },
                label = { Text("人设提示词（性格 / 说话风格 / 称呼）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "示例：你叫小陪，是我温柔体贴的女朋友。说话口语化、偶尔撒娇，用「宝宝」称呼我。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Text("主动消息测试", style = MaterialTheme.typography.titleMedium)
            Text(
                "立即手动触发一次：决策 → 召回 → 生成 → 通知（绕过冷却/上限/勿扰）。点它会先保存当前设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    // 静默保存当前设置（不跳页、不 Toast），避免把触发协程所在作用域销毁
                    onPersist(form)
                    onTestProactive()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("立即触发一次主动消息")
            }

            Spacer(Modifier.height(8.dp))
            Text("系统权限与保活", style = MaterialTheme.typography.titleMedium)
            Text(
                "这三项没开，主动消息可能不会按时弹出来。点右侧按钮跳系统设置，回来后点「刷新状态」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionRow("通知权限", permissionStatus.notificationsEnabled, "去开启", onOpenNotificationSettings)
            PermissionRow("精确闹钟", permissionStatus.exactAlarmAllowed, "去开启", onOpenExactAlarmSettings)
            PermissionRow(
                "电池优化白名单",
                permissionStatus.ignoringBatteryOptimizations,
                "去设置",
                onOpenBatteryOptimization
            )

            keepAliveStatus?.let { ka ->
                Text(
                    "检测到机型：" + ka.rom.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    KeepAliveGuide.description(KeepAliveTarget.AUTOSTART, ka.rom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    KeepAliveGuide.description(KeepAliveTarget.BACKGROUND_SETTINGS, ka.rom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onOpenAutostart, modifier = Modifier.weight(1f)) {
                    Text("允许自启动")
                }
                OutlinedButton(onClick = onOpenBackgroundSettings, modifier = Modifier.weight(1f)) {
                    Text("关闭后台限制")
                }
            }
            TextButton(onClick = onResetKeepAliveGuide) { Text("查看保活引导（重新运行）") }
            TextButton(onClick = onRefreshPermissions) { Text("刷新权限状态") }

            // ---- V2.4.3 一键保活（root）：调 su 把本包写进白名单 / 放开后台限制 ----
            Spacer(Modifier.height(8.dp))
            Text(
                "一键保活（需 root）：自动把本应用加入电池优化白名单，并放开后台运行限制。" +
                    "没 root 的设备请用上面的「去设置」手动处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // 已在跑就忽略重复点击（按钮本身 disabled，这里再兜一层连点）
                        if (keepAliveState is KeepAliveUiState.Running) return@OutlinedButton
                        keepAliveState = KeepAliveUiState.Running
                        // scope = rememberCoroutineScope()：点完立刻返回 → 协程取消 →
                        // runner 的 CancellationException 透传（V2.4.2 保证）→ 不崩、不留半截日志。
                        // 这是预期行为，不要在这里 try/catch 或 runCatching 去吞：run() 的契约是
                        // 「除 Cancellation 外全部 Result.failure、绝不抛」，能捕到的只有不该捕的取消。
                        scope.launch {
                            val result = keepAliveRunner.run(context.packageName)
                            val next = keepAliveResultToUi(result)
                            keepAliveState = next
                            if (next is KeepAliveUiState.Failed) showNeedRootDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = keepAliveState !is KeepAliveUiState.Running
                ) {
                    Text(if (keepAliveState is KeepAliveUiState.Running) "正在执行…" else "一键保活（需 root）")
                }
                OutlinedButton(
                    onClick = { showLogDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = keepAliveState is KeepAliveUiState.Done || keepAliveState is KeepAliveUiState.Failed
                ) {
                    Text("查看执行日志")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "天气数据来自 Open-Meteo（https://open-meteo.com）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 可点开浏览器（Data 的 UriHandler 打开系统浏览器）
            TextButton(onClick = { uriHandler.openUri("https://open-meteo.com") }) {
                Text("访问 Open-Meteo")
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSave(form) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- V2.4.3 两个对话框（Dialog 走独立窗口，不参与本 Column 布局）----

        // 执行日志：Done 逐行显示 5 条；Failed 显示失败原文（不是空白）
        if (showLogDialog) {
            val logs = when (val s = keepAliveState) {
                is KeepAliveUiState.Done -> s.logs
                is KeepAliveUiState.Failed -> listOf(s.message)
                else -> emptyList()
            }
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = { Text("保活执行日志") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text("还没有执行记录。", style = MaterialTheme.typography.bodySmall)
                        }
                        logs.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text("关闭") } }
            )
        }

        // 需要 root 提示（计划书：「无 root → Toast + 6.2 引导」）。
        // message 原样展示（含 exit/timedOut/output）：这是用户区分「手机没 root」和
        // 「Magisk 点了拒绝」的唯一依据，不能改写成「请检查 root 权限」。
        if (showNeedRootDialog) {
            val message = (keepAliveState as? KeepAliveUiState.Failed)?.message ?: "需要 root 权限"
            AlertDialog(
                onDismissRequest = { showNeedRootDialog = false },
                title = { Text("未能自动保活") },
                text = {
                    Text(
                        message + "\n\n可以改用系统设置手动处理：",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNeedRootDialog = false
                        onResetKeepAliveGuide() // 复用现成的 6.2 保活引导对话框
                    }) { Text("打开保活引导") }
                },
                dismissButton = {
                    TextButton(onClick = { showNeedRootDialog = false }) { Text("知道了") }
                }
            )
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (value.isBlank()) "请选择" else value)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { idx, opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = {
                            onSelect(idx)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label + if (granted) "（已开启）" else "（未开启）",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
        if (!granted) {
            OutlinedButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}
