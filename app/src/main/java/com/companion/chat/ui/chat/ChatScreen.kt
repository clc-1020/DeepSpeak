package com.companion.chat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.companion.chat.R
import com.companion.chat.data.image.HeicToJpeg
import com.companion.chat.data.image.readFileHead
import com.companion.chat.data.remote.ImageFormats
import com.companion.chat.data.speech.SttManager
import com.companion.chat.ui.permission.RecordPermission
import com.companion.chat.ui.permission.rememberLocationPermission
import com.companion.chat.ui.permission.rememberRecordPermission
import com.companion.chat.ui.speech.VoiceInputController
import com.companion.chat.ui.speech.VoiceInputOverlay
import com.companion.chat.ui.speech.VoiceState
import com.companion.chat.ui.theme.LocalChatColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 单轮最多发送图片数 */
private const val MAX_IMAGES = 10

/** 语音录音上划取消阈值（V2.3.4）。80dp 约等于拇指半节，误触与真取消都够用。 */
private val VOICE_CANCEL_THRESHOLD = 80.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<MessageUiModel>,
    onSendMessage: (String, List<Uri>) -> Unit,
    onSettingsClick: () -> Unit,
    onDndClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    onVoiceClick: () -> Unit,
    /** V2.3.4：非空则麦克风按钮走「长按录音 → 松手识别」，为空时退回 [onVoiceClick]（无 STT 的兜底）。 */
    sttManager: SttManager? = null,
    /** V2.3.5：关闭后麦克风按钮隐藏、且不创建录音控制器（连手势都不挂）。 */
    sttEnabled: Boolean = true,
    /** 勿扰当前是否开启：顶栏铃铛据此换图标/着色（2026-11-20，原来那个按钮只是个摆设）。 */
    dndEnabled: Boolean = false,
    tokPerSec: Double?,
    cacheHitRatio: Double?,
    contextUsage: Double?,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    onReadAloud: (Long, String) -> Unit = { _, _ -> },
    speakingId: Long? = null,
    /** V2.3.6：通知栏语音识别结果，非空时回填输入框（只填不发送）。 */
    voicePending: String? = null,
    onVoicePendingConsumed: () -> Unit = {},
    /** 流式输出中：撤回 / 重新生成 一律禁用（会和正在写入的临时消息打架）。 */
    isStreaming: Boolean = false,
    /** 撤回：删除这条 user 消息**及其之后的全部消息**（含紧随的 AI 回答）。 */
    onTruncateFrom: (Long) -> Unit = {},
    /** 重新生成：删掉最后一条 AI 回答重新问一遍。 */
    onRegenerate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var messageInput by remember { mutableStateOf("") }

    // 撤回不可恢复 → 必须二次确认；这里只暂存"待撤回的那条"，确认后才真正删
    var pendingRecall by remember { mutableStateOf<MessageUiModel?>(null) }

    // V2.3.6：通知栏语音结果只回填输入框，绝不自动发送；回填后立即通知 ViewModel 清空标记
    LaunchedEffect(voicePending) {
        val text = voicePending ?: return@LaunchedEffect
        messageInput = text
        onVoicePendingConsumed()
    }

    val selectedUris = remember { mutableStateListOf<Uri>() }
    val listState = rememberLazyListState()

    // V2.1.1 验收：首次进主界面时若无粗定位权限，申请一次；拒绝后不再自动弹（标记在 ui_permission）
    rememberLocationPermission(autoRequestOnce = true)

    // V2.3.4 语音输入：录音权限（**不自动弹**，长按按下时才请求）
    val recordPermission = rememberRecordPermission()

    // 状态机 + 波形都收在 controller 里；这里只接「结果进输入框」「失败原文 Toast」
    val density = LocalDensity.current
    // V2.3.5：sttEnabled 必须进 remember 的 key（切开关要重建），并且关掉时要**真的不建 controller**——
    // controller 存在 ⇒ 下面的 voiceGestureModifier 就会挂着，等于「按钮藏了但手势还在监听」。
    val voiceController = remember(sttManager, sttEnabled, density) {
        if (!sttEnabled) {
            null
        } else {
            sttManager?.let { manager ->
                VoiceInputController(
                    sttManager = manager,
                    cancelThresholdPx = with(density) { VOICE_CANCEL_THRESHOLD.toPx() },
                    onResult = { text -> messageInput = text },
                    onError = { reason -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show() },
                    onNotice = { notice -> Toast.makeText(context, notice, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }
    // 页面销毁时一定要释放麦克风（否则下一个进程/下一次长按都抢不到麦）
    DisposableEffect(voiceController) {
        onDispose { voiceController?.release() }
    }

    // 长按麦克风的手势：等长按超时 → 开麦 → 跟踪位移（上划取消）→ 松手收尾
    val voiceGestureModifier: Modifier? = voiceController?.let { controller ->
        Modifier.pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                // 长按超时前松手 = 单击；手指移出 touchSlop = 想滑动，都不进录音
                val releasedEarly: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var early = false
                    while (!early) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            early = true
                            break
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            early = true
                        }
                    }
                    early
                }
                if (releasedEarly != null) return@awaitEachGesture

                // 录音是用户主动动作：只在真正长按时才要权限。
                // 用 RecordPermission.isGranted(context) 现查而不读 remember 里的快照：
                // 授权后不保证触发本函数重组，读快照会一直停在 false，长按永远开不了麦。
                if (!RecordPermission.isGranted(context)) {
                    // 系统已不再弹框（授权被彻底拒绝）时给出明确引导，不静默失败
                    if (!RecordPermission.canPrompt(context)) {
                        Toast.makeText(context, "请在系统设置里允许录音权限", Toast.LENGTH_SHORT).show()
                    }
                    recordPermission.request()
                    return@awaitEachGesture
                }
                if (controller.state is VoiceState.Transcribing) {
                    Toast.makeText(context, "正在识别，请稍候", Toast.LENGTH_SHORT).show()
                    return@awaitEachGesture
                }
                if (!controller.start()) return@awaitEachGesture

                var totalY = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            controller.cancel() // 指针被系统吞掉：按取消处理，绝不误发
                            break
                        }
                        if (!change.pressed) {
                            controller.finish() // 松手：IO 线程 stop → 识别
                            break
                        }
                        // 先取位移再 consume：positionChange() 在已消费的 change 上恒返回 Zero
                        val dy = change.positionChange().y
                        change.consume()
                        totalY += dy
                        controller.updateDrag(totalY)
                    }
                } catch (e: CancellationException) {
                    controller.cancel()
                    throw e
                }
            }
        }
    }

    // 相册多选（≤ MAX_IMAGES）
    // 加固 A（V1.6.2.6）：选完先验一遍类型。**只要有任意一个不是可发送的图片，整批拒绝**，
    // 并弹内嵌赞助码的对话框——把"莫名其妙的失败"换成一句有人话的解释。
    var showUnsupportedDialog by remember { mutableStateOf(false) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.all { isSendableImageUri(context, it) }) {
                val remain = (MAX_IMAGES - selectedUris.size).coerceAtLeast(0)
                if (uris.size > remain) {
                    Toast.makeText(context, "最多 $MAX_IMAGES 张，已截断", Toast.LENGTH_SHORT).show()
                }
                selectedUris.addAll(uris.take(remain))
            } else {
                showUnsupportedDialog = true
            }
        }
    }

    // 加固 B：点发送**不立刻清空**，等这条消息真的出现在列表里（说明读图/持久化都过了）再清。
    // 原先是乐观清空：一旦发送失败（图太大、格式不认、Uri 失效），字和图全没了，只剩一个 Toast。
    var pendingSentText by remember { mutableStateOf<String?>(null) }
    var pendingBaseMaxId by remember { mutableStateOf(0L) }
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        val pending = pendingSentText ?: return@LaunchedEffect
        val landed = messages.any {
            it.isUser && it.id > pendingBaseMaxId && it.content.trim() == pending.trim()
        }
        if (landed) {
            // 发送期间用户可能又打了新内容：只在没改动时清空，别把新打的字吃掉
            if (messageInput.trim() == pending.trim()) messageInput = ""
            selectedUris.clear()
            pendingSentText = null
        }
    }

    // 自动滚动到最新消息（流式输出时也能触发）
    LaunchedEffect(messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                onSettingsClick = onSettingsClick,
                onDndClick = onDndClick,
                dndEnabled = dndEnabled,
                onNewChatClick = onNewChatClick,
                onMemoryClick = onMemoryClick,
                onOpenDrawer = onOpenDrawer
            )
        },
        bottomBar = {
            Column {
                // 重试按钮（失败时显示）
                if (canRetry) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重试")
                            }
                        }
                    }
                }

                // 状态栏
                StatusBar(
                    tokPerSec = tokPerSec,
                    cacheHitRatio = cacheHitRatio,
                    contextUsage = contextUsage
                )
                // 输入框（含已选图片预览）
                InputBar(
                    message = messageInput,
                    onMessageChange = { messageInput = it },
                    selectedUris = selectedUris,
                    onPickImages = { pickImages.launch("image/*") },
                    onRemoveImage = { selectedUris.remove(it) },
                    onSend = {
                        if (messageInput.isNotBlank() || selectedUris.isNotEmpty()) {
                            // 加固 B：只登记"这一轮发的是什么"，清空交给上面的 LaunchedEffect
                            pendingSentText = messageInput
                            pendingBaseMaxId = messages.maxOfOrNull { it.id } ?: 0L
                            onSendMessage(messageInput, selectedUris.toList())
                        }
                    },
                    onVoiceClick = onVoiceClick,
                    voiceGesture = voiceGestureModifier,
                    sttEnabled = sttEnabled
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (messages.isEmpty()) {
                EmptyState()
            } else {
                MessageList(
                    messages = messages,
                    listState = listState,
                    onReadAloud = onReadAloud,
                    speakingId = speakingId,
                    actionsEnabled = !isStreaming,
                    onRecall = { pendingRecall = it },
                    onRegenerate = onRegenerate
                )
            }

            // V2.3.4 录音/识别浮层。state 用 lambda 取，采样只在浮层内部触发重组，
            // 不会因为波形每秒 16 次刷新把整个消息列表一起重绘。
            voiceController?.let { controller ->
                VoiceInputOverlay(
                    stateProvider = { controller.state },
                    waveform = controller.waveform,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ---- 撤回二次确认（不可恢复）----
    pendingRecall?.let { target ->
        val startIndex = messages.indexOfFirst { it.id == target.id }
        val count = if (startIndex >= 0) messages.size - startIndex else 1
        AlertDialog(
            onDismissRequest = { pendingRecall = null },
            title = { Text("撤回这条消息？") },
            text = { Text("撤回后，这条消息及之后的 $count 条消息将被删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRecall = null
                    onTruncateFrom(target.id)
                }) {
                    Text("撤回", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecall = null }) { Text("取消") }
            }
        )
    }

    // ---- 选了非图片文件：整批拒绝 + 内嵌赞助码（加固 A / V1.6.2.6）----
    // 放在 ChatScreen 顶层而不是 InputBar 里：输入栏每次重组（打字、波形刷新）都不会把弹窗状态带掉。
    if (showUnsupportedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsupportedDialog = false },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "目前仅支持图片，赞助我一杯奶茶钱，催我更新",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Image(
                        painter = painterResource(id = R.drawable.img_footer),
                        contentDescription = "赞助收款码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showUnsupportedDialog = false }) { Text("知道了") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    onSettingsClick: () -> Unit,
    onDndClick: () -> Unit,
    dndEnabled: Boolean,
    onNewChatClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            Row {
                IconButton(onClick = onOpenDrawer) {
                    Icon(imageVector = Icons.Default.Menu, contentDescription = "会话列表")
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "设置")
                }
            }
        },
        actions = {
            // 1.8.0：原来是「记忆」文字按钮，改成描线大脑图标（用户指定 🧠）。
            // 用 Icons.Default.Psychology（单色，跟旁边勿扰/新建同色同尺寸），
            // 不用彩色 emoji —— 顶栏其它图标都是单色描线，混一个彩色 emoji 会很跳。
            IconButton(onClick = onMemoryClick) {
                Icon(imageVector = Icons.Default.Psychology, contentDescription = "记忆")
            }
            IconButton(onClick = onDndClick) {
                // 开启状态必须**看得出**差别：只换 contentDescription 等于还是「点了没反应」。
                // extended 图标已在依赖里（`filled.*` 通配导入），NotificationsOff 无需新增 import。
                Icon(
                    imageVector = if (dndEnabled) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    tint = if (dndEnabled) LocalChatColors.current.accentFill else LocalContentColor.current,
                    contentDescription = if (dndEnabled) "勿扰已开启，点按关闭" else "勿扰已关闭，点按开启"
                )
            }
            IconButton(onClick = onNewChatClick) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新建对话")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 1.9.2：图标按深浅各一张，互不通用 ——
        //   深色：ic_whale.png = 384×384 黑底圆角卡片 + 白鲸（黑底上只剩白色轮廓）
        //   浅色：ic_whale_light.png = 透明底 + 黑色墨迹鲸鱼（白底上只剩墨迹，卡片的白色本来就看不见）
        // 不能只用一张反色：反色会把卡片也翻成一块黑的。
        Image(
            painter = painterResource(
                id = if (isSystemInDarkTheme()) R.drawable.ic_whale else R.drawable.ic_whale_light
            ),
            contentDescription = "DeepSpeak",
            modifier = Modifier.size(96.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "欢迎回来，我一直在",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageList(
    messages: List<MessageUiModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onReadAloud: (Long, String) -> Unit,
    speakingId: Long?,
    actionsEnabled: Boolean,
    onRecall: (MessageUiModel) -> Unit,
    onRegenerate: () -> Unit
) {
    val lastId = messages.lastOrNull()?.id
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages) { message ->
            MessageBubble(
                message = message,
                onReadAloud = onReadAloud,
                speakingId = speakingId,
                actionsEnabled = actionsEnabled,
                // 只有最后一条 assistant 消息能「重新生成」，否则后面的对话会变成孤儿
                canRegenerate = message.id == lastId && !message.isUser,
                onRecall = { onRecall(message) },
                onRegenerate = onRegenerate
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageUiModel,
    onReadAloud: (Long, String) -> Unit,
    speakingId: Long?,
    actionsEnabled: Boolean,
    canRegenerate: Boolean,
    onRecall: () -> Unit,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    // 气泡/图形色走「色板」：深浅两套，由 Theme 一次选好，这里不判断深浅。
    val palette = LocalChatColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            // 1.8.0：不再用 primaryContainer / secondaryContainer（壁纸动态取色的色，且
            // 「用户=蓝、AI=灰」的强对比块）。改走色板：用户 #333333/深、AI 与背景同色（方案 A）。
            // 结构 / 留白 / 280dp 宽度上限 / 操作行全部保留。contentColor 必须显式给，
            // 自定义 containerColor 不在配色表里，Compose 推不出对比色会退回 LocalContentColor。
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) palette.bubbleUser else palette.bubbleAi,
                contentColor = if (message.isUser) palette.bubbleUserText else palette.bubbleAiText
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                // 图片（可能多张，纵向堆叠，限高避免过高）
                message.imagePaths.forEach { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(6.dp),
                        fontSize = 16.sp
                    )
                }

                // ---- 常驻操作行：复制 / 撤回|重新生成 / 朗读 ----
                // 流式临时消息（id<0）不显示任何按钮：它还没落库，点了也删不到。
                // 注意：这里**不能**用 fillMaxWidth / weight —— 气泡宽度是自适应的（widthIn max 280），
                // 一旦子项 fillMaxWidth，所有气泡都会被撑满 280dp，短消息"你好"也会变成一条超宽块。
                if (message.id > 0 && (message.content.isNotBlank() || !message.isUser)) {
                    Row(
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.content.isNotBlank()) {
                            MessageActionButton(
                                icon = Icons.Default.ContentCopy,
                                label = "复制",
                                onClick = { copyMessage(context, message.content) }
                            )
                        }
                        if (message.isUser) {
                            // user 消息：撤回（删除这条及之后全部）
                            MessageActionButton(
                                icon = Icons.Default.Delete,
                                label = "撤回",
                                enabled = actionsEnabled,
                                tint = MaterialTheme.colorScheme.error,
                                onClick = onRecall
                            )
                        } else if (canRegenerate) {
                            // AI 消息：重新生成（图标与撤回区分开：循环箭头 vs 垃圾桶）
                            MessageActionButton(
                                icon = Icons.Default.Refresh,
                                label = "重新生成",
                                enabled = actionsEnabled,
                                onClick = onRegenerate
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        if (!message.isUser && message.content.isNotBlank()) {
                            val playing = speakingId == message.id
                            IconButton(
                                onClick = { onReadAloud(message.id, message.content) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (playing) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = if (playing) "停止朗读" else "朗读",
                                    tint = LocalChatColors.current.accentFill,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 气泡里的 18dp 小图标按钮（复制 / 撤回 / 重新生成），点按区域 34dp。 */
@Composable
private fun MessageActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 复制到系统剪贴板。Android 13+ 系统会自己弹「已复制」浮层，这里就不再 Toast 重复提示。 */
private fun copyMessage(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching {
        cm.setPrimaryClip(ClipData.newPlainText("DeepSpeak", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun InputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    selectedUris: List<Uri>,
    onPickImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit,
    /** 非空时麦克风换成「长按录音」手势（V2.3.4），此时不再挂 click。 */
    voiceGesture: Modifier? = null,
    /** V2.3.5：语音输入总开关，关掉时整颗麦克风按钮不渲染（左侧「+」布局保持靠左）。 */
    sttEnabled: Boolean = true
) {
    val canSend = message.isNotBlank() || selectedUris.isNotEmpty()
    // 中性灰面板（不跟随动态取色的紫色），亮/暗主题都能用
    val fieldColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // 发送键/光标的 accent 走色板（深=#7797EC / 浅=#467CF6）
    val palette = LocalChatColors.current

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = fieldColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                start = 14.dp,
                end = 10.dp,
                top = 10.dp,
                bottom = 8.dp
            )
        ) {
            // 已选图片预览（横向滚动，不撑高输入框）
            if (selectedUris.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(selectedUris) { uri ->
                        SelectedImageThumb(uri = uri, onRemove = { onRemoveImage(uri) })
                    }
                }
            }

            // 文本输入：无边框、自动增高（最多 6 行）
            BasicTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(LocalChatColors.current.accentFill),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (message.isEmpty()) {
                            Text(
                                text = "发消息或按住说话",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // 底部工具行：左「+」，右侧「语音 / 发送」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(onClick = onPickImages) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "图片",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 输入栏改版（2026-11-21）：麦克风与发送键**常驻并排**，不再「有字才冒出发送键」。
                // 空输入时发送键置灰且不挂 click（点了无反应，不弹 Toast）。
                if (sttEnabled) {
                    RoundIconButton(
                        onClick = if (voiceGesture == null) onVoiceClick else null,
                        modifier = voiceGesture ?: Modifier
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (voiceGesture == null) "语音" else "按住说话",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) {
                                // 1.9.0：图形填充走色板 accentFill（深=#7797EC / 浅=#467CF6）
                                palette.accentFill
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            }
                        )
                        .then(if (canSend) Modifier.clickable(onClick = onSend) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = if (canSend) "发送" else "发送（尚未输入内容）",
                        modifier = Modifier.size(20.dp),
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
}

/** 输入框内的圆形小按钮（+ / 麦克风）。onClick 为 null 时只渲染、不挂 click（由外部挂手势）。 */
@Composable
private fun RoundIconButton(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun SelectedImageThumb(uri: Uri, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(60.dp)) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun StatusBar(
    tokPerSec: Double?,
    cacheHitRatio: Double?,
    contextUsage: Double?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = buildString {
                append(tokPerSec?.let { "%.1f tok/s".format(it) } ?: "?? tok/s")
                append(" · ")
                append(cacheHitRatio?.let { "缓存命中 %.0f%%".format(it * 100) } ?: "缓存命中 ??%")
                append(" · ")
                append(contextUsage?.let { "上下文 %.0f%%".format(it * 100) } ?: "上下文 ??%")
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// UI 数据模型
data class MessageUiModel(
    val id: Long,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long,
    /** 该消息附带的本地图片文件路径（多图），气泡据此渲染图片 */
    val imagePaths: List<String> = emptyList()
)

/**
 * 加固 A（V1.6.2.6）：一个 MIME + 一段文件头，能不能当图片发出去。
 *
 * 纯函数，可以 JVM 单测；真正读文件的那一步在 [isSendableImageUri]。
 *
 * 判据三条，顺序有讲究：
 * 1. **HEIC/HEIF 算"能发"** —— data 层会自动转 JPEG（V1.6.2.5），这里拦住等于把刚修好的功能又关掉；
 * 2. MIME 在白名单里 → 放行（绝大多数相册图片走这条，零额外读盘）；
 * 3. MIME 不可信时只看头：`ImageFormats.detect` 认 jpeg/png/gif/webp 的魔数。
 */
internal fun isSendableImagePick(mime: String?, head: ByteArray?): Boolean {
    if (HeicToJpeg.looksLikeHeic(head, mime)) return true
    if (mime != null && mime in ImageFormats.SUPPORTED) return true
    val detected = head?.let { ImageFormats.detect(it) } ?: return false
    return detected in ImageFormats.SUPPORTED
}

/** 读 MIME + 前 16 字节交给 [isSendableImagePick]。只读头，不加载整张图。 */
private fun isSendableImageUri(context: Context, uri: Uri): Boolean {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val head = readFileHead(context, uri)
    return isSendableImagePick(mime, head)
}
