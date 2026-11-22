# 陪聊 APP 项目规则

> **状态：现行** ｜ 工程规则、数据保护铁律与逐版变更记录（每次开发前必读）。
> 本文档随代码演进，细节以代码与本目录其他现行文档为准。
> ⚠️ **任何 bug 修复 / 新功能完成后，必须到仓库根目录 CHANGELOG.md 追加一条**（含 versionCode 与测试基线），并同步对应领域设计文档。规则见 CONTRIBUTING.md。

> \\\*\\\*每次开发前必读此文件，完成后更新进度。\\\*\\\*

\---

## 项目基本信息

* **项目名称**：陪聊 APP
* **技术栈**：Kotlin 原生 Android
* **开发模式**：蓝图先行 + 分功能迭代
* **当前版本**：v0.1（第一版开发中）
* **项目目录**：`待定`（任务 0.1 初始化时创建）

\---

## 核心约束（永远不变）

1. **纯客户端**：无服务器、无后端、电脑不常驻
2. **API Key 用户自填**：所有 AI 调用费用由用户承担
3. **进程平时不常驻**：靠 WorkManager / AlarmManager 唤醒，执行完退出
4. **已 root**：可用 root 加白名单解决保活
5. **用户填的 Key 承担所有费用**：记忆提取、mood 更新、主动消息生成全用同一个 Key

\---

## 分工（本会话约定）

* **Claude （检查决策**）。两个 DeepSeek 分工：

  * **本会话（逻辑）**：数据层、网络/SSE、记忆、调度、TTS 逻辑、业务规则、构建/测试/部署。
  * **另一个 DeepSeek（UI）**：全部 Compose 界面、视觉、动效、交互观感。
* 共享文件（本规则/进度/清单）改动小步进行，避免两个会话并行覆盖；界面文件与逻辑文件尽量不交叉。
* 每完成一个功能即更新进度并列出改动文件；不主动 git commit（除非用户要求）。
* **本会话（真机测试）**：用户手动测功能；本会话负责构建 APK、adb 安装/启动、截图、抓 logcat 排查。脚本 `tools\\\\deploy.ps1`。

\---

## 关键技术决策

|模块|决策|原因|
|-|-|-|
|记忆语义去重|本地 embedding 模型（`all-MiniLM-L6-v2`），相似度 > 0.85 判定重复|不花 API 费用、隐私好、离线可用|
|记忆召回|规则算分选 TOP 3，交给大模型挑话题|平衡费用和准确度|
|mood 更新频率|每 5 轮对话更新一次|省 API 费用，心情也够即时|
|主动消息上限|一天最多 n 条 AI 自发消息（默认 3），不计入用户指令提醒和追问链|避免骚扰，用户可自行调整|
|主模型|`deepseek-flash`（V4.1-Flash-1017，原生视觉），陪伴默认**关思考**|快、省；复杂分析再开 high|
|上下文缓存|人设+长期记忆摘要作**稳定前缀**，变化内容（时间/mood/最近消息）放后|命中价约为未命中 1/40，省 token|
|结构化输出|mood/记忆提取用 `response\\\_format={"type":"json\\\_object"}`|稳定拿 JSON，免正则|
|时间/地域注入|每轮 user 前插 `latest\\\_reminder` 消息|主动消息/时间感更自然|
|会话标题|建会话首条请求 `title:{enabled:true}` 一次|免额外请求、不计费|
|TTS 通道|系统 TTS 兜底 + DeepSeek 官方 TTS 可选（联网计费）|跨机型音色统一，可切换|

\---

## 当前进度

### 阶段 0：项目基础（1/1 完成）

* \[x] 任务 0.1：项目初始化

### 阶段 1：基础聊天 + 数据层（6/6 完成）

* \[x] 任务 1.1：数据库设计与实现 ✅ **已完成**（DeepSeek）

  * 7 张表 Entity + DAO 全部实现
  * 测试用例覆盖完整
  * 预留 embedding 字段
  * Claude 已补充：Gradle Wrapper + 启动图标
* \[x] 任务 1.2：API 配置与调用 ✅ **已完成（本 AI，逻辑层，已审查通过并按建议修订）**

  * 流式 SSE 客户端 + usage 解析（tok/s、缓存命中、上下文%）
  * 配置存储 ConfigRepository
  * **设置页输入 UI 归 Claude（任务 7.1）**
* \[x] 任务 1.3：对话界面 UI ✅ **已完成（Claude）**

  * 空状态（中心图标 + 问候语）
  * 对话态（消息列表）
  * 顶部栏（设置/勿扰/新建对话/记忆）
  * 底部输入框 + 图片/语音按钮（占位）
  * 状态栏（tok/s · 缓存命中% · 上下文%）
  * **待验收**：真机运行测试布局
* \[x] 任务 1.4：多轮对话与流式输出 ✅ **已完成（本 AI）**

  * `ChatViewModel`（AndroidViewModel）：状态管理 + 多轮上下文 + 流式追加 + 错误处理
  * 对接 1.2 `ChatApiClient`，消息落 Room；`MainActivity` 已接 ViewModel（`ChatScreen` 未改）
  * 上下文裁剪 `ContextTrimmer`（system 保留 + 最近优先，预算 80%）
  * 错误：4xx 不重试、网络/5xx 自动重试 1 次；失败 Toast 提示 + `retry()`；状态栏无数据显示 `??`
  * 人设：`ConfigRepository` 已加 `getPersona/savePersona`（界面留给 1.5）
* \[x] 任务 1.5：人设设定 ✅ **已完成（本 AI，含设置页 UI）**

  * 新增 `SettingsScreen`：服务商 / Base URL / API Key / 模型 / 人设 编辑 + 保存
  * `MainActivity` 加「聊天 ↔ 设置」导航；保存写入 `ConfigRepository`（含 persona）
  * 人设在 1.4 `ChatViewModel.buildContext()` 作为 system 注入
  * 空状态改用蓝鲸图标；流式自动滚动已修
  * **\[逻辑层补充，本 AI]** 抽出纯函数 `ContextBuilder.build()`（人设 system 注入 + 窗口裁剪），`ChatViewModel.buildContext()` 改为调用它；新增 `ContextBuilderTest` 单测；新增 `PersonaDefaults.SAMPLE` 供设置页「填入示例」。
  * 说明：此设置页同时覆盖了 7.1 的「模型与角色」部分，7.1 后续补主动消息/语音等其余项
* \[x] 任务 1.6：图片输入 ✅ **逻辑层已完成（本 AI）；选图 UI 归另一个 DeepSeek**

  * 已核实 DeepSeek 官方支持识图，用 `deepseek-flash`（OpenAI 兼容 `image\\\_url` 块数组，data URL base64）
  * 多模态 DTO：`ApiMessageDto`(content: Any) + `ContentPart`/`ImageUrl`；`MultimodalRequestBuilder` 仅在最后一条 user 消息挂图，历史/assistant 不带图（省 token、避免 400）
  * `ImageLoader`：Uri→base64 data URL，按魔数校验格式（JPEG/PNG/GIF/WebP），单图 ≤32MiB
  * `ApiConfig.visionModel` + `ConfigRepository` 持久化 + `ProviderPresets`（识图默认 `deepseek-4.1-flash`）；带图自动切识图模型
  * `ChatViewModel.send(text, imageUris)`；纯图片消息展示占位「\[图片]」；带图消息重试按纯文本
  * 单测：`MultimodalRequestBuilderTest`、`ImageFormatsTest`
  * ✅ **选图 UI 已完成（本 AI / UI 线）**：相册多选 ≤10、缩略图预览/删除、`ChatScreen.onSendMessage(text, uris)`→`vm.send`；相册用 `GetMultipleContents` + Coil 缩略图
  * ✅ **设置页新增「识图模型」**（`SettingsForm.visionModel`），切换服务商自动带默认；预设加入 `deepseek-4.1-flash` 并设为 DeepSeek 默认识图模型；新增依赖 `io.coil-kt:coil-compose:2.5.0`
  * ✅ 重试条已接（`ChatScreen(canRetry,onRetry)` ← `ChatViewModel.retry()`）

### 任务 1.7：DeepSeek 新 API 接入（2026-11-20，本 AI 逻辑层）✅

依据 `陪聊APP-DeepSeek新API接入设计.md` §4/§5/§6 落地；本机 `./gradlew testDebugUnitTest` 与 `assembleDebug` 均 BUILD SUCCESSFUL。

* **DTO**：`ChatRequest` 增 `response\\\_format`/`title`/`thinking`/`reasoning\\\_effort`；`DeltaDto.title`；新增 `StreamEvent.Title`；新增 `ChatCompletionResponse/CompletionChoice/CompletionMessage`（callJson 非流式用）
* **`ChatApiClient`**：`streamChat(..., opts: ChatOptions)` 组装新参数（`thinking` 仅 DeepSeek 服务商发送、默认 `disabled`；json/title 按开关）；新增 `callJson()` 非流式返回 `Result<String>`
* **`ApiConfig`/`ConfigRepository`**：新增 `thinkingEnabled`/`deepSeekTtsEnabled`/`ttsVoice`/`latestReminderEnabled`/`autoTitleEnabled`/`jsonModeDefault` 持久化；旧模型名读入时自动迁移
* **`ProviderPresets`**：DeepSeek 模型改为 `deepseek-flash`/`deepseek-v4-pro`（下线旧名清掉）；识图默认 `deepseek-flash`（原生视觉）；`deepseek-flash` 上下文按 1M
* **`UserMetaMarker`**：latest\_reminder `<date>,<weekday>,<location>,<platform>,<language>`（中/英星期、逗号净化）+ 单测
* **`ContextBuilder`**：稳定前缀（人设 + 长期记忆摘要）→ 历史（含各轮 reminder）→ 本轮 reminder → 本轮 user；`MessageEntity.reminder` 与 user 成对持久化（DB `version=3`）
* **`ChatViewModel`**：每轮注入并持久化 reminder；新建会话首条请求带 `title` 并落库，无标题用 user 前缀兜底；主对话默认关思考
* **`JsonExtract`**：json\_object 安全解析（失败返回 null → mood 保持原值 / 记忆提取跳过）
* **TTS 双通道**：`TtsEngine` / `SystemTtsEngine`(离线兜底) / `DeepSeekTtsEngine`(官方，含 ≤9000 计费字符分片 + 队列播放) / `TtsManager`(官方失败自动降级系统)；`CompanionApp.ttsManager` 已就绪；单测覆盖选择/降级与分片
* **待 UI 线（另一个 DeepSeek）**：设置页加「语音通道 / 音色 / 深度思考」开关并映射到 `ApiConfig`；对话气泡加「朗读」按钮调用 `ttsManager.speak`

### 任务 1.8：会话管理（本地会话保存 + 历史切换）（2026-11-20）✅ 逻辑层 + 左侧滑抽屉列表 UI 完成

背景：会话本就在 Room 持久化（conversations + messages），但缺"会话列表/历史切换"入口——点「新建对话」后旧会话虽在库里却回不去。本任务补逻辑层，UI 线接界面即可。

* `ConversationDao.observeSummaries()`：会话列表（标题 + 最后一条消息摘要 + 时间，按最近活跃排序）；`deleteById(id)` 删会话（消息外键级联删）
* `ChatViewModel.switchTo(id)`：切到指定历史会话并加载其历史；`newChat()` 当前会话为空时复用、不再新建（防连点产生空会话）
* `ChatViewModel.deleteConversation(id)`：删当前会话自动切最近/一个不剩则新建（`ConversationDeletionPolicy` 纯函数 + `ConversationDeletionPolicyTest` 单测）
* `ChatUiState.currentConversationId`：供会话列表高亮当前会话
* `ConversationListViewModel`：暴露 `summaries: StateFlow<List<ConversationSummary>>`（删除走 `ChatViewModel.deleteConversation`），UI 线可直接用
* **列表 UI（已实现 2026-11-20）**：`ui/chat/ConversationDrawer.kt` 的 `ConversationDrawerContent` + `MainActivity` 用 `ModalNavigationDrawer` 包裹聊天页；顶栏加 ☰「会话列表」按钮（左边缘右滑也能拉出）。列出 `summaries`（标题 / 最后一句 / 时间，当前会话高亮），点选→`switchTo`、垃圾桶→`deleteConversation`、右上＋→`newChat`。真机验证：抽屉可开、列表渲染正常、无崩溃（versionName 2.7）。
* 编译：`assembleDebug` BUILD SUCCESSFUL（新增 DAO 查询 + 逻辑层；未加列表界面前不影响现有聊天）

### 阶段 2：记忆系统（8/8 完成）

* \[x] 任务 2.1：本地 embedding 模型集成 ✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * 模型：`all-MiniLM-L6-v2`（fp32 ONNX，90,405,214 B，sha256 `6FD5D72F…6046452`）+ `vocab.txt` 落 `app/src/main/assets/models/`，打包进 APK（⚠️ **任务 2.3 起改用 `bge-small-zh-v1.5` 中文模型**，见任务 2.3 说明）
  * **模型文件落盘** `ModelFiles`：首次在 IO 线程把 assets 拷到 `filesDir/models/`（.tmp→rename 原子），VERSION 戳做升级判断；支持 `installFromFile` 走「首次下载」；缺模型→`Missing`→降级 `NoopEmbeddingService` 不崩
  * **EmbeddingService 抽象**：`interface EmbeddingService{isReady,dimensions,embed()}` + `NoopEmbeddingService` 兜底；`OnnxEmbeddingService`（单 `OrtEnvironment`/单 `OrtSession` 常驻＝内存池，`Dispatchers.Default` 推理，按 `session.inputNames` 只喂模型声明过的输入，输出优先取 `last\\\_hidden\\\_state` 做 attention-mask mean pooling + L2 归一化）
  * **队列化防抢占** `SerialEmbeddingService`：`Mutex` 把并发 `embed()` 串行排队，绝不在同一 session 上并行 run（防 native 内存暴涨/崩溃）
  * **装配** `EmbeddingServiceFactory`（单例，首次在 IO 加载）+ `CompanionApp.ensureEmbeddingService()` / `embeddingServiceOrNoop()`
  * **纯逻辑**：`VectorMath`（点积/模长/归一化/余弦/meanPool）、`FloatArrayCodec`（FloatArray↔ByteArray，写 Room `embedding` 列用，小端）、`BertTokenizer`（uncased WordPiece：清洗/小写/去重音/标点处理/CJK 单字/贪心最长匹配 `##` 续接/\[CLS]\[SEP]/截断）
  * **单测（JVM）31 个全过**：`VectorMathTest`15、`FloatArrayCodecTest`5、`BertTokenizerTest`8、`SerialEmbeddingServiceTest`3；全仓合计 **82 用例 0 失败**，`assembleDebug` 绿（APK 157.9MB，含模型）
  * **真机验收**：`EmbeddingAcceptanceTest`（androidTest）已写好（真实 ONNX 跑 同句>0.99/近义>0.85/无关<0.5）；本机 MIUI 拦截 adb 安装（`INSTALL\\\_FAILED\\\_USER\\\_RESTRICTED`，需手机上点允许），故改用**宿主机同算法数值验证**通过：同句 1.0000、近义 0.9491、无关 0.0111、dim=384、norm≈1.0
  * 工具：`tools/fetch\\\_embedding\\\_model.ps1`（hf-mirror 下载 + 大小/SHA256 校验）
* \[x] 任务 2.2：记忆提取与存储 ✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * 每轮主对话成功后，`ChatViewModel` **后台异步**（`viewModelScope.launch(IO)`，fire-and-forget）调用 `MemoryExtractor.extractAndStore()`：独立 `ChatApiClient.callJson(json\\\_object)`（**不复用会话 ID**，全新无状态请求），输入最近 6 条消息（≈3 轮）
  * 纯逻辑：`MemoryExtractionParser`（容错解析 `{short,long,daily}`：字符串/对象元素、缺字段、类型不符、去重、非法 JSON→null）；`MemoryPromptBuilder`（system 指令含 JSON 关键字 + 对话转写，跳过 system/reminder/空行）
  * 落库 `MemoryExtractor.store()`：short→`memory\\\_short`、long→`memory\\\_long`(含 category)、daily→按日期**合并** `memory\\\_daily.events`；同表**完全相同文本跳过**（语义去重留 2.3）；embedding 就绪则附向量（`FloatArrayCodec` 存列），否则 null
  * 失败保持原记忆：解析/网络失败返回 failure、不写库、不覆盖；上层忽略结果，**绝不影响主对话**
  * **单测新增 19 个全过**（`MemoryExtractionParserTest`9 / `MemoryPromptBuilderTest`4 / `MemoryExtractorTest`6 假 DAO）；全仓 **101 用例 0 失败**，`assembleDebug` 绿（157.9MB）
  * 待真机 + 真 Key 联调：模型真实抽取质量（「我明天要面试」→short、「我对花生过敏」→long）、记忆页可见新增条目
* \[x] 任务 2.3：语义去重 ✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * `MemoryExtractor.store()` 的 short/long 落库改走 `MemoryDeduplicator`：新记忆算 embedding → 与**同类别**旧记忆算余弦 → `>0.85` 合并（更新 content/timestamp，`weight+=0.1`）否则插入
  * 同类别：短期=全部短期；长期=同 `category`（跨类别不比较）
  * 旧记忆 embedding 为空 → 就地补算并落库（backfill），之后复用；embedding 未就绪/失败 → 退回「完全相同文本」跳过（不阻断、不崩）
  * `ChatViewModel`：提取后有内容才 `ensureEmbeddingService()`（懒加载模型）再 `store()`，fire-and-forget 不影响主对话
  * **⚠️ 关键修正：embedding 模型从 all-MiniLM-L6-v2 换成 `bge-small-zh-v1.5`（512 维，BERT+WordPiece，CLS 池化，94.8MB fp32）**。原因：all-MiniLM 是英文模型，实测对中文近义/无关严重重叠（「我明天要面试」vs「明天去公司面试」=0.70，而与无关句「我对花生过敏」=0.60），**任何阈值都无法完成中文去重**。bge 实测：该近义对 0.8862（>0.85✓）、无关对 0.317（<0.5✓）、同句 1.0
  * **单测新增 8 个全过**（`MemoryDeduplicatorTest`，假 DAO+假 embedding，覆盖「连续两次只留一条」「近义合并」「同类别才比较」「未就绪退回精确去重」「weight 递增」）；全仓 **109 用例 0 失败**，`assembleDebug` 绿（157.9MB）
  * 模型资源：`app/src/main/assets/models/bge-small-zh-v1.5.onnx`（sha `69A0B846…749A`）+ 中文 `vocab.txt`（sha `45BBAC…`）；`tools/fetch\\\_embedding\\\_model.ps1` 已更新；`ModelFiles.VERSION=2`、`DIMENSIONS=512`、`MAX\\\_TOKENS=512`；`OnnxEmbeddingService` 新增 `Pooling`（CLS/MEAN），Factory 用 CLS
  * 真机验收测试 `EmbeddingAcceptanceTest` 已改为中文例句 + 512 维 + CLS（MIUI 拦截自动安装，需允许后跑）
* \[x] 任务 2.4：时间锚点转换 ✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * 新增纯函数 `domain/memory/TimeAnchorConverter.convert(text, reference): String`：相对时间→绝对日期、时间点→HH:mm，无锚点原样返回
  * 支持：相对日（今天/明天/后天/大后天/昨天/前天/今晚）；N 天/周后(前)；星期（下周X/这周X/本周X/裸周X=今天起最近）；绝对日期（2026年11月25日 / 11月25日/号 / 2026-12-01）；时间（7点 / 7点半 / 7点30分 / 下午3点 / 晚上8点半 / 9:30，含上午/下午/晚上/凌晨/中午换算 24h）
  * 接入 `MemoryExtractor.store()`：short/long/daily 落库前先 `anchor()` 归一（异常原样返回，绝不影响落库）；参考日由 `referenceDate` 注入（默认 `LocalDate.now()`）
  * 副作用（正向）：时间锚点归一后，语义去重对「明天要面试 / 后天面试」这类**不同日期**的高相似文本会因日期不同而不误合并（部分缓解审查问题 3）
  * 验收：`明天 7 点叫我起床`→`2026-11-21 07:00 叫我起床`（与计划一致）；`下周三开会`→`2026-11-25 开会`（注：计划写 11-27，是按当时不同参考日笔误；以 2026-11-20 周五为参考，下周三=11-25，逻辑正确）
  * **单测新增 9 个全过**（`TimeAnchorConverterTest` 8 + `MemoryExtractorTest` 落库转换 1）；全仓 **129 用例 0 失败**，`assembleDebug` 绿（157.9MB）
* \[x] 任务 2.5：淡忘机制 ✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * 纯逻辑：`domain/memory/MemoryDecay`（每日增量 `days×0.1`）+ `domain/memory/MemoryScoring`（得分 = `权重 × 时间新鲜度 − fadeScore`，新鲜度半衰期 7 天指数衰减；`isRecallable`/`topK`）
  * 执行器 `data/memory/MemoryDecayService.runOnce()`：读 `app\\\_state.last\\\_decay\\\_epoch\\\_day` 游标，按天数差给所有短期记忆 `fadeScore += days×0.1`，落新游标；**只更新不删除**；首次仅初始化游标（新装当天不衰减）；同天重复调用幂等
  * 调度 `worker/MemoryDecayWorker`（CoroutineWorker）：`CompanionApp.onCreate` 里 `enqueueUniquePeriodicWork(24h, KEEP)` + 启动 `enqueueUniqueWork` 补跑一次
  * 验收：新记忆（新鲜度高）得分更高；30 天前记忆 fadeScore≈3.0、权重 1 时得分<0 不召回，权重极高（100）仍可召回；旧记忆仍在库（记忆管理界面可见）
  * **审查修复·原子性（中·已修）**：改用 `MemoryShortDao.incrementAllFadeScore(increment)`（单条 SQL 全表原子自增）+ `count()`，并用 `database.withTransaction { 更新 + 写游标 }` 包裹（`MemoryDecayService.runInTransaction` 注入；生产传 Room `withTransaction`，单测直跑并断言事务被调用）——避免「游标已前移但记忆未全更新」的崩溃不一致，且比逐条 update 更快
  * **审查修复·重试上限（低·已修）**：`MemoryDecayWorker` 加 `runAttemptCount >= 3 → Result.failure()`，防持续失败无限重试耗电；半衰期/衰减因子配置化（低·③）按评审意见**延后**（MVP 保持常量）
  * **单测新增 17 个全过**（`MemoryScoringTest`8 / `MemoryDecayTest`4 / `MemoryDecayServiceTest`5 假 DAO）；全仓 **149 用例 0 失败**，`assembleDebug` + `assembleDebugAndroidTest` 绿（135MB）
* \[x] 任务 2.6：记忆召回（规则算分）✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * `MemoryScoring` 增「久未提及加分」：`recallScore = 权重×新鲜度 + min(距上次提起天数,90)×0.05 − fadeScore`（`daysSinceMentioned`，null 按上限给满加分）
  * `domain/memory/MemoryRecallRanker.rank()`：给短期记忆算分、过滤不可召回（得分≤0）、按降序取 TOP3（纯函数）
  * `data/memory/MemoryTopicSelector`：TOP3 交大模型（`callJson` json\_object，只输出 `{"id":k}`）挑一条话题；失败/解析不了退回第 1 条；`id:0`=都不合适→null
  * `data/memory/MemoryRecallService.recall(config)`：算分取 TOP3 → 选一条（单条直接选）→ 更新 `lastMentioned` → 返回记忆 id；无可召回返回 null
  * `CompanionApp` 接线 `memoryRecallService`（selector=MemoryTopicSelector）；供阶段 3 主动消息调用
  * 验收：新记忆得分更高排前；久未提及加分后反超（测试：never-mentioned 30 天旧记忆 4.55 > 刚提过的新记忆 0.9）；faded 记忆过滤；选中后 `lastMentioned` 更新
  * 冷却/拉黑过滤留任务 2.8
  * **单测新增 17 个全过**（RecallRanker4 / TopicSelector5 / RecallService4 / Scoring 追加4）；全仓 **166 用例 0 失败**，assembleDebug + assembleDebugAndroidTest 绿（135MB）
  * **审查改进（非阻断·已采纳）**：①「久未提及加分」收紧为 `min(天数,60)×0.03`（最高 +1.8），修「老记忆靠加分霸榜」；② 选择器改显式 `{"suitable":true/false,"id":k}`，`parseChoice` 先判 `suitable`（false→0=都不合适），比 `id:0` 语义清晰；③ `MemoryRecallService.recall()` 顶部加「任务 2.8 过滤冷却/拉黑」TODO；④ 选择失败/解析失败/越界分支加 `Log.w` 便于真机排查「为何总选第 1 条」。全仓 **168 用例 0 失败**
* \[x] 任务 2.7：记忆管理界面 ✅ **界面完成（本 AI / UI 线）**

  * 新增 `ui/memory/MemoryScreen.kt`：三 Tab（短期 / 长期 / 日志）+ 列表 + 新增/编辑/删除弹窗 + FAB
  * 新增 `ui/memory/MemoryViewModel.kt`：读现有 Room DAO（数据填充依赖 2.2/2.3 逻辑，当前可手动增删改）
  * `MainActivity` 加「记忆」路由，`onMemoryClick` 已接
* \[x] 任务 2.8：打分三态（回复/别提这个）✅ **已完成（本 AI 逻辑层，2026-11-20）**

  * `MemoryShortEntity` 新增三列：`dismissCount`（被点「别提这个」次数）、`coolingUntil`（冷却截止 epoch millis，null=未冷却）、`blacklisted`（永久拉黑）——**DB 版本 4→5**（破坏式迁移，开发期可接受）
  * 纯逻辑 `domain/memory/MemoryFeedback`：`afterReply(weight)=weight+0.2`（封顶 `WEIGHT\\\_MAX=10.0`）；`afterDismiss()` 第 1 次 `weight-0.5`+冷却 `COOLING\\\_DAYS`=30 天、第 2 次起永久拉黑；`isSuppressed(blacklisted,coolingUntil,now)` 判定召回是否跳过（冷却过期自动恢复）
  * 业务入口 `data/memory/MemoryFeedbackService`：`onReply(id)`→`weight+=0.2`；`onIgnore(id)`→不改库但打日志（便于真机确认被调用）；`onDismiss(id)`→第 1 次冷却 30 天返回 `false`、第 2 次拉黑返回 `true`；记忆不存在静默返回。DAO 新增原子 `applyFeedback(...)` 一次性写四列
  * 召回侧：`MemoryRecallRanker.rank()` 算分前先 `filterNot { MemoryFeedback.isSuppressed(...) }` 剔除冷却/拉黑；`MemoryRecallService` 去 TODO。用户主动问仍走正常对话（记忆仍在库）
  * `CompanionApp` 接线 `memoryFeedbackService`；通知三按钮 UI 待任务 3.5 完成后再接（本任务只交付逻辑层+入口，可被 UI/通知层直接调用）
  * 验收：点「别提这个」1 次→30 天不召回（冷却过期后恢复）；点 2 次→永久不召回（记忆仍在库）；「回复」`weight` +0.2
  * **单测新增 20 个全过**（`MemoryFeedbackTest`9 / `MemoryFeedbackServiceTest`5 / `MemoryRecallRankerTest` 追加4 / `MemoryRecallServiceTest` 追加2）；全仓 **188 用例 0 失败**，`assembleDebug` 绿（135MB）
  * **审查改进（5 条·已处理）**：① 冷却天数**本就是常量** `MemoryFeedback.COOLING\\\_DAYS=30`（非硬编码字面量），后续调 7/14 天只改此常量；未来可提升为 AppState 可配置项（延后）。② 权重上限 `WEIGHT\\\_MAX` **5.0→10.0**（极重要记忆 1.0+0.2×45 次才到顶，不易被久未提及加分/淡忘分压制；可观察真机再调）。③ `onIgnore` 增日志（注入 `log: (String)->Unit`，默认 `Log.d`），单测用记录器断言「忽略」确实被调用。④ `afterDismiss` **拉黑后不再递增** `dismissCount`（≥2 后保持 2，防误操作累积 3/4/5…）。⑤ 冷却过期自动恢复的**用户提示属 UI 层**（通知文案/记忆页倒计时），待阶段 3/UI 线处理。全仓 **189 用例 0 失败**

### 阶段 3：主动消息框架（7/7 完成｜审查线核对：代码 + 单测完成，真机待验收）

* \[x] 任务 3.1：统一待发队列 ✅ `pending_messages` 表 + `PendingMessageQueue`（状态机 PENDING→SENT 走单行 transition 防并发重复发；优先级 5/4/3/2/1 = 指令/追问/节日/环境/闲聊，与第二版 V2.1.4 的 priority 2/3 已对齐）
* \[x] 任务 3.2：WorkManager 定时检查 ✅ `ProactiveCheckWorker`（2h 周期 + `NetworkType.CONNECTED`；`CompanionApp.onCreate` 以 KEEP 注册）
* \[x] 任务 3.3：AlarmManager 精确闹钟 ✅ `AlarmScheduler` + `ReminderService` + `ReminderAlarmReceiver`；`ReminderIntent`/`ReminderParser` 纯逻辑带单测
* \[x] 任务 3.4：决策闸门（纯规则）✅ `ProactiveGateRules` + `DefaultProactiveGate`（总开关/勿扰/冷却 2h/每日上限/回复率/无钩子）
* \[x] 任务 3.5：生成与送达 ✅ `ProactiveDeliveryRunner` + `LlmProactiveMessageGenerator` + `AndroidProactiveNotifier` + `ProactiveSendCounter` + `RoomConversationWriter`
* \[x] 任务 3.6：测试模式按钮 ✅ `ProactiveTestTrigger`（`force` 绕闸门精确送达）+ 设置页入口
* \[x] 任务 3.7（计划外，已登记为偏离）提醒「已完成」回执 ✅ `ReminderDoneReceiver` + 通知按钮 → `DONE` + 关联短期记忆

### 阶段 4：语音输出（TTS）（3/3 完成｜审查线核对）

* \[x] 任务 4.1：TTS 基础实现 ✅ `TtsEngine` / `SystemTtsEngine`（离线兜底，`CompletableDeferred` 等 onInit）/ `DeepSeekTtsEngine`（官方，≤9000 字符分片）/ `TtsManager`（失败自动降级）
* \[x] 任务 4.2：对话朗读 ✅ `ReadAloudController`（朗读期间持 WakeLock，念完自动释放）
* \[x] 任务 4.3：主动消息语音 ✅ `TtsProactiveSpeaker` + `ProactiveVoiceRules`（勿扰只静音语音、通知照发）

### 阶段 5：mood（心情状态）（2/2 完成｜审查线核对）

* \[x] 任务 5.1：mood 更新 ✅ `MoodService`（每 5 轮 `callJson` 写 `app_state`，解析失败保持原值）
* \[x] 任务 5.2：mood 融入生成 ✅ `MoodPromptBuilder` / `MoodTone` + `LlmProactiveMessageGenerator.moodProvider`

### 阶段 6：省电与保活（2/2 完成｜审查线核对）

* \[x] 任务 6.1：WakeLock 管理 ✅ `WakeLockPolicy` / `WakeLockScope` / `PowerWakeLock`；Worker 与 AlarmReceiver 均持锁，带逐条契约注释
* \[x] 任务 6.2：国产 ROM 保活引导 ✅ `KeepAliveLauncher`（ROM 识别 + 厂商页跳转 + Manifest `queries` 声明）+ `KeepAliveService` / `KeepAliveGuide` / `KeepAliveGuideDialog`

### 阶段 7：设置界面（2/2 完成｜审查线核对）

* \[x] 任务 7.1：设置页 UI ✅ `SettingsScreen` 全项（服务商/Key/模型/人设/识图/TTS/主动消息/权限状态/保活入口）
* \[x] 任务 7.2：勿扰模式 ✅ `DndSettings` + `DndPolicy`（跨午夜、end 开区间）+ 闸门/送达/语音三处共用同一判据

### 阶段 V2.1：环境感知（8 个任务｜V2.1.1–V2.1.8 全部完成）

> **测试基线：677 全绿**（`failures=0 errors=0`，83 suite；2026-11-20 18:5x 审查线实测）＝ v1 收尾 418 + 第二版新增 259。
> （历史：V2.1.5 复核 567/74 → 18:1x 603/77 → 18:2x 647/81 → 18:4x 671/83 → **18:5x 677/83**。677 = 671 + `WavHeaderTest` 6 条（120s 截断修复回归，10→16）。）
> 每改完一个任务重跑 `testDebugUnitTest` 并更新本表。计划依据：`陪聊APP第二版开发计划.md`（v3.4）。

| 任务 | 状态 | 证据（单测 suite / 文件） |
|---|---|---|
| V2.1.1 环境数据采集 | ✅ | 35：`CityGeocoderTest`(7) `WeatherCacheTest`(12) `LocationCacheTest`(10) `EnvironmentUrlsTest`(6)；`data/environment/` 9 个类 |
| V2.1.2 环境规则引擎 | ✅ | 29：`EnvironmentRuleEngineTest`；`domain/environment/` 5 个类，纯函数无 Android 依赖 |
| V2.1.3 特殊日期 + 节日 | ✅ | 36：`SpecialDateMatcherTest`(17) `SpecialDateHookServiceTest`(7) `FestivalProviderTest`(6) `FestivalsJsonTest`(6) |
| V2.1.4 钩子投递 + 同日去重 | ✅ | `EnvironmentHookServiceTest`(7)；`Environment` 变体 + `LATE_NIGHT` 类型 + `onDeliveredMessage` 回调全部落地（见下） |
| V2.1.5 整点环境闹钟 | ✅ 已通过复核（567 全绿） | 12+3：`AlarmCodesTest`(6) `EnvironmentCheckSchedulerTest`(6) `EnvironmentHookServiceTest`(7+3=10)；`AlarmKind`/`AlarmCodes`/`AlarmScheduling`(5 参)/`AlarmScheduler`(按 kind 选 API)/`ReminderAlarmReceiver`(分流)/`EnvironmentCheckScheduler`(纯函数)/`CompanionApp.syncEnvCheckSchedule`(四分支) |
| V2.1.6 环境感知设置 | ✅ | `EnvironmentSettingsTest`(6) + UI + 容器接线（见下） |
| V2.1.7 节日钩子接入 | ✅（审查线 2026-11-20 18:4x 复核通过） | 12：`ProactiveCheckRunnerFestivalTest`(6) + `ProactiveDeliveryDndTest` 10→12 + `ProactiveDeliveryRunnerTest` 7→9 + `PendingMessageContentTest` 8→10；`Festival(name)` + `FestivalDedupe` + `ALREADY_SENT` + `FESTIVAL_EXPIRED` |
| V2.1.8 对话天气可见 | ✅ | `WeatherSummaryTest`(7) + `WeatherCacheTest`(16) + `UserMetaMarkerTest`(9)；`weatherLine` 只读 `peek()` 不联网 |
| 接线缺口（V2.1.5 遗留） | ⚠️ 待 UI 线接 | `syncEnvCheckSchedule` 只有 `onCreate` 一个调用点；设置页保存回调 / `MainActivity.onResume` 未接 → 运行中打开开关不补排，需重启才生效 |
| BOOT_COMPLETED | ✅ 已完成（18:12，V2.1.5 遗留补做） | `worker/BootReceiver` + `alarm/BootRestorePlanner` + `BootRestorePlannerTest`(8)；`ReminderAlarmReceiver`/`ReminderService`/`WakeLockPolicy` 同步改 |
| AlarmManager 抽接口 | ⛔ 待立项 | 纯重构，为 `AlarmScheduler` kind→API 分支补单测铺路 |

#### V2.1.7 审查明细（审查线 2026-11-20 18:4x，671 全绿）

**✅ 通过（对照计划书 §V2.1.7 验收 8 条逐条核，全部真覆盖）**

* **`PendingMessageContent.Festival(name)`**：`fest:` 前缀，encode `when` 穷尽；decode 分支插在 ENV 之后、`else` 之前，空 payload 兜底 `PlainText(raw)`，**else 契约未动**（`memoryIdOrNull` / 送达层 `as?` 依赖它）
* **`FestivalDedupe`（新增，唯一出处）**：`fes_sent_<name>_<dateKey>`；查键（runner）、记账（CompanionApp）、断言（测试）三处共用同一函数，杜绝「查键用入队日、记账用送达日」分叉——这正是环境线 `EnvironmentHooks` 的既有约定
* **`ProactiveCheckRunner`**：新签名带默认值（`recallFestival` / `checkSent`），命名参数插参对现有调用点零改动；分流顺序与计划书 L693-707 **逐条一致**（checkSent → hasPendingWithContent → gate 预筛 → 入队）；节日被拦**不回退**记忆分支；`ALREADY_SENT` 新增且 `ProactiveCheckWorker` 只在 `ENQUEUED` 触发投递（新枚举不会误触发送达）
* **`SpecialDateHit` 基类补 `abstract val name`**：`firstOrNull()?.name` 覆盖 `User`（用户生日）与 `Festival`（内置节日）两个子类——**「只取 is Festival 会让生日永远发不出去」的坑已堵**，验收 2 用真实 `SpecialDateHookService` 链路验证
* **`ProactiveDeliveryRunner`**：勿扰分支跨日判定（`isSameLocalDay(now(), end)` 为 false → `discard` + `FESTIVAL_EXPIRED`），当天内维持 `reschedule` + `DND_DEFERRED`；**`deferInsteadOfDiscard` 确认一行未动**（L190-206 与改前一致）；新增未知 tag 防御（decode 兜底成 `PlainText` 但文本仍以已知 tag 开头 → discard，不改 decode 契约）
* **`LlmProactiveMessageGenerator`**：`when` 穷尽补 `Festival` → 「今天是${name}，想一句自然又有温度的祝福」（只给 prompt，不在数据层拼死板祝福语）
* **`CompanionApp`**：`specialDateHookService` 装配 `(memoryLongDao, FestivalProvider(this))`；`recallFestival` = `specialDatesOn(LocalDate.now()).firstOrNull()?.name` + try/catch；`checkSent` = `appStateDao().get(key) != null`；记账用 **`msg.triggerTime`**（不是 `System.currentTimeMillis()`）——与 env 那行的差异是**刻意**的，跨午夜送达不会把键写错日期；与 V2.3.3 的 `sttManager` 装配块（L140-145）**共存无冲突**
* **测试扎实**：`ProactiveCheckRunnerFestivalTest` 用真 `PendingMessageQueue` + 假 DAO（`countPendingByContent` 走真实 SQL 语义，不是恒 0 的假实现）；验收 8 的记账测试钉死 `fes_sent_春节_2026-11-20`（triggerTime 那天）

**⚠️ 改进项（不阻塞，均为轻微）**

1. **`recallFestival` 在闸门之前调用**：总开关关闭时每 2h 仍会 `specialDatesOn` → 查 DB（`memoryLongDao.getByCategory`）+ 过滤 69 条节日。频率低、可忽略；若想省，可在 lambda 首行判 `configRepository.getProactiveEnabled()` 提前返回 null。
2. **`isUnknownTagContent` 会误删以 tag 开头的正常正文**：`PlainText("fest:x")`（正文恰好这样开头）会被判为未知 tag 而 discard。当前生产代码没有把用户可控文本塞进 `PlainText` 的路径，风险边缘；更精确的写法是只在「原文不是 `text:` 前缀但 decode 出 PlainText 且原文以其他 tag 开头」时丢弃。
3. **验收 8 的测试在测试内自拼记账逻辑**（`FestivalDedupe.key(fest.name, msg.triggerTime, zone)`），未覆盖 `CompanionApp` 的真实 lambda。人工已核实 L425 确实用 `msg.triggerTime`，但**无自动化防回归**——将来有人照抄 env 那行改成 `System.currentTimeMillis()` 测试不会红。`CompanionApp` 是 Android `Application`，JVM 单测无法直接验，属可接受的测试缺口（建议在 L425 加一行「⚠️ 必须 triggerTime」注释，已存在）。
4. 数字口径：提交时报 666 / 82 suite，审查线实测 **671 / 83**——差额来自同期并行的 V2.3.3 回归 +6、`SttManagerTest` +1、`VoiceBarLevelTest` 5。**V2.1.7 自身 12 条与声称完全一致**，不存在虚报。

**未发现 🔴 必修 bug。**

#### V2.1.4 明细（2026-11-20 16:0x 完成，552 全绿）

* ✅ **`PendingMessageType` 新增 `LATE_NIGHT("late_night", 2)`**（独立类型，不能塞进 `ENVIRONMENT`：闸门只看 `type`，`content` 存的是生成后的中文正文，`content.contains("LateNight")` 匹配不上）
* ✅ **`PendingMessageContent.Environment(stableId)`**：编码 `env:<stableId>`；B7-a（数值只作触发条件不持久化，正文走泛化措辞，禁止把数值拼进 `stableId`）；`decode` 空值兜底 `PlainText`
* ✅ **`ProactiveGateRules`**：勿扰豁免只留 `LATE_NIGHT_ALERT`；指令/追问注释修正（不进勿扰判断，由送达层 DND_DEFERRED 顺延）
* ✅ **`ProactiveDeliveryRunner.onDeliveredMessage(msg)`**：独立回调，与 `onDelivered` 分开（force 语义相反：`onDelivered` force 跳过、`onDeliveredMessage` force 也跑，否则测试模式会漏记去重键）
* ✅ **`EnvironmentHookService`**：`recall()` 只读不写；`enqueue()` `LateNight → LATE_NIGHT`，其余 → `ENVIRONMENT`；`as?` 链简化成 `when(city)` 模式匹配
* ✅ **`LlmProactiveMessageGenerator` 穷举 `when` 补 `Environment` 分支** → `EnvironmentHooks.genericText(stableId)` 泛化措辞
* 🔴 **修复去重 1 小时错位（上轮审查发现）**：原 `now - 4h` 把 04:00–04:59 劈成「新一夜」→ 整点 04:00 会重发。改为按本地小时判：`hour < NIGHT_END_HOUR(5)` 归前一天；`EnvironmentRuleConfig.nightEndHour` 直接绑定 `EnvironmentHooks.NIGHT_END_HOUR`（两处分叉从根上消除）
* ✅ **回归测试**：`late_night_0430_same_night_as_2330`（04:30/04:59 同夜）+ `late_night_date_key_rolls_at_05_00`（05:00 翻夜）；`EnvironmentRuleEngineTest` 29→30

#### V2.1.5 审查明细（审查线 2026-11-20 16:2x，564 全绿）

**✅ 通过**

* **`AlarmCodes`**（新增 39 行）：`ENV_CHECK = 999_999`、`first = id.toInt()`、`followup = 1_000_000+id`、`timeout = 2_000_000+id`、`cancelAll` 三码成套 —— 与计划书 §3.5 公式表逐字一致，6 条单测覆盖（含 `id=0` 是真 id、三码互不重叠）
* **`AlarmScheduling` 5 参**：默认值只写在**接口**上，实现类不重复声明 —— 现有 3 参调用点（`ReminderService.scheduleFromText` L37）零改动，设计正确；`ReminderServiceTest.FakeScheduler` 已同步升 5 参（计划书 L41 的「改接口会崩 Fake」风险已消解）
* **`AlarmScheduler` 按 kind 选 API**：`ENV_CHECK → setAndAllowWhileIdle`（不刷状态栏闹钟图标），其余 → `setAlarmClock` + 无权限时退回 + Toast —— 符合 §3.5 表格；**ENV_CHECK 分支不会弹「精确闹钟」Toast**（此前担心的每小时 Toast 噪音不存在）
* **`nextWholeHour` 严格大于 now**：`plusHours(1).truncatedTo(HOURS)` → `11:00:00.000 → 12:00`，不会到点重排同刻。**此前「自排循环」隐患已消除**，6 条单测覆盖 10:37 / 整点 / 整点+1s / 23:30 跨日 / 23:00 跨日 / 00:15
* **`ReminderAlarmReceiver` kind 兼容**：旧 PendingIntent 无 `EXTRA_KIND` → `runCatching { valueOf("") }` 兜底 `INSTRUCTION`，已排的旧闹钟不会失联；持锁契约（同步 acquire → `runHolding` → finally release+finish）与 6.1 一致
* **首次排钟条件**：`onCreate → 开关开 && env_check_scheduled 空 → 排一次 + put("1")`，与计划书 L604 字面一致；`runCatching` + `onFailure` 保证标记只在成功后写

**🔴 必修（3 项）**

1. **`ENV_CHECK` 缺「触发送达」**（`ReminderAlarmReceiver.handleEnvCheck` L124–141）：`recall()` 命中后只 `enqueue(hook)`，**没有 `ProactiveDeliveryWorker.enqueue(context)`**；而 `handleInstruction` L115 有。计划书 L603 明确要求「有则投队列 **+ 触发送达**」。后果：环境钩子入队后无人触发送达，要等下一次 2h `ProactiveCheckWorker` 且它刚好 `ENQUEUED` 才被顺带送出（`ProactiveCheckWorker` 只在 `outcome == ENQUEUED` 时触发），熬夜/饭点钩子可能晚 1–2 小时，饭点窗口（±30min）直接错过。修法：`handleEnvCheck` 里 `enqueue` 之后补 `ProactiveDeliveryWorker.enqueue(context)`。
2. **环境总开关关闭后链路不停、钩子照发**：`CompanionApp.scheduleFirstEnvCheckOnce` L465 只在**首次排钟**时看 `environmentEnabled`；之后 `handleEnvCheck` **不检查该开关**、`EnvironmentHookService.recall()` 也不检查，闸门侧 `ProactiveSettingsMapping.gateConfig` 只读 `proactiveEnabled`（主动消息总开关）。后果：用户在设置页关掉「环境感知总开关」→ 已排的整点闹钟继续每小时 `recall → enqueue → 送达`，`SettingsScreen` 文案「关闭后不再提醒熬夜/下雨/降温」失效。修法：关闭时 `alarmScheduler.cancel(AlarmCodes.ENV_CHECK)`，并在 `recall()`/`handleEnvCheck` 里读开关短路。**反方向同样缺**：首次启动时开关是关的 → 标记未写、不排钟；用户在 App 内打开开关 → 无任何代码补排，必须等下次进程重启才生效。
3. **`recall()` 异常会永久断链**（`handleEnvCheck` L126）：`recall()`（内部会 `weatherCache.load` → 缓存过期时**发 HTTP**）放在协程 try 内，且「排下一次整点」写在它**之后**。若 `recall` 抛异常（DAO/网络/解析未兜住），或 10s `RECEIVER_TIMEOUT_MS` 锁到期/进程被冻结导致协程未跑完，**下一小时闹钟就不会被排**；而 `env_check_scheduled` 已经是 `"1"`，下次启动 `scheduleFirstEnvCheckOnce` 直接跳过 → 环境检查**永久停摆**。修法：把「排下一次」提到 `recall()` **之前**（顺序无关，先续命再干活），并给 `recall` 加 `runCatching`。

**⚠️ 需要改进（继承自 V2.1.4，被每整点检查放大）**

4. `EnvironmentHookService.recall()` 只查 `hooks.first()` 的去重键：23:00 熬夜钩子发出后，整夜（23:00–04:59）里雨/降温/高温钩子全被第一条的「已发」挡住，整夜只剩熬夜一条能发。每小时检查让这个压制更彻底（原来 2h 一次，现在每小时都命中第一条）。
5. 闸门拦 `ENVIRONMENT` → `deferInsteadOfDiscard` 顺延 +1h，但**去重键未写**；下一整点 `recall` 又投一条同 `stableId` 的 → 队列堆副本，闸门一开**连环送达**同一件事。每小时检查把这个堆叠速度提高一倍。
6. 全项目仍无 `BOOT_COMPLETED` 接收器（`RECEIVE_BOOT_COMPLETED` 0 命中）：重启后 `AlarmManager` 闹钟全丢，而 `env_check_scheduled="1"` 又阻止补排 → 重启即永久失效。应用级旧账（用户提醒闹钟同样丢），V2.1.5 受影响更重。
7. `AlarmCodes.ENV_CHECK(999_999)` 与 `first(999_999L)` 同值：真实 reminder id 到 999999 概率极低，但 `cancelAll(999999)` 会误伤环境闹钟；`AlarmCodesTest` 未覆盖这个边界。

**测试**：`AlarmCodesTest`(6) + `EnvironmentCheckSchedulerTest`(6) 全部为纯 JVM，无需 Robolectric；`ReminderServiceTest` 4 条随 Fake 升级仍绿。**缺口**：接收器 `ENV_CHECK` 分流、`AlarmScheduler` 的 kind→API 选择都没有单测（前者需 Android Context，可接受；后者建议后续用可注入的 `AlarmManager` 包装类补）。

**✅ 复核（2026-11-20 16:3x，567 全绿）**：3 必修 + 2 次级全修，修法正确——

1. `handleEnvCheck` 补 `ProactiveDeliveryWorker.enqueue(context)`（L168，计划书 L603 落实）
2. 开关联动：Receiver 每整点读开关关→cancel（L150-157）+ `recall()` 首行 `if (!enabled()) return null`（L64）+ `syncEnvCheckSchedule()` 四分支（开无→排+写、开有→不动、关有→cancel+删标记、关无→不动）
3. 先续命再干活：排下一次 → 读开关 → `runCatching{recall}` → `runCatching{enqueue+触发}`，四段隔离，断链根因消除
4. `recall()` 逐条遍历找第一条「未发过且不在队列」的钩子（L76-81）
5. `countPendingByContent` + `hasPendingWithContent`，content 与 enqueue 同源（`contentOf` 用同一编码），防副本堆积
6. 测试 +3：`disabled_returns_null` / `rain_still_returned_after_late_night_sent` / `same_content_already_queued_returns_null`；6 个 Fake 补齐 `countPendingByContent`

**新遗留（已录入状态表）**：`syncEnvCheckSchedule` 只有 `onCreate` 一个调用点，设置页保存回调 / `MainActivity.onResume` 未接 → 运行中打开开关不补排（需重启）。BOOT_COMPLETED 与 AlarmManager 抽接口待立项。

#### V2.1.6 明细（UI 线 + 逻辑线，已编译通过）

* \[x] **环境感知区块**（`ui/settings/SettingsScreen.kt`）：总开关、熬夜开关、降水阈值滑块（1–100%，严格大于才提醒）、降温阈值滑块（1–30℃，下限取 `EnvironmentSettings.MIN_TEMP_DROP_THRESHOLD`）
* \[x] **SettingsForm 环境字段**：`environmentEnabled` / `lateNightEnabled` / `lateNightHour` / `rainThreshold` / `tempDropThreshold`，默认值全部引用 `EnvironmentSettings.DEFAULT_*`（不写魔数）
* \[x] **持久化接线**（`ui/MainActivity.kt`）：`initial` 里读 `getEnvironmentSettings()` 回显；`persist` 里写 `saveEnvironmentSettings(...)`，保存后重进保持
* \[x] **手动城市区块**：输入框 + 「搜索并设为手动城市」按钮；4 态 Toast 映射在 `SettingsScreen` 内（Hit→已设为手动城市 X；NoMatch→找不到该城市，请检查拼写；NetworkError→网络异常，使用上次位置；Cleared→**不弹**，按数据层约定）
* \[x] **关于区块**：署名「天气数据来自 Open-Meteo（https://open-meteo.com）」+ `LocalUriHandler` 打开浏览器
* \[x] **粗定位权限申请（V2.1.1 验收项，此前漏做）**：新增 `ui/permission/LocationPermission.kt`
  - 只申请 `ACCESS_COARSE_LOCATION`（不碰 FINE）；`rememberLauncherForActivityResult(RequestPermission())`
  - `ChatScreen` 首次组帧自动弹一次（未授权 + 从未问过才弹）；`SettingsScreen` 只放「授权定位（仅需大致位置）」按钮，进页面不弹
  - 拒绝也记 `location_permission_asked=true`（存 `ui_permission` SharedPreferences，不污染 `ConfigRepository` 与 `app_state` 的跨版本键契约），此后不再自动弹
  - 设置页环境区块未授权时显示红字：「未授权定位，天气提醒不可用；可在设置里手动填城市」
  - ✅ 容器已接线（`CompanionApp.kt:92/96` 暴露 `locationCache`/`cityGeocoder`，`MainActivity.kt:187-188` 已传入），验收第 6 条（授权后 `LocationCache.current()` 拿到坐标）**现在可跑**
  - **缺陷 A 修复**：`ON_RESUME` 重读权限。`LifecycleEventEffect` 在 `lifecycle-runtime-compose:2.6.2` **不存在**（`jar tf` 实测只有 `FlowExtKt`，2.7.0 才加），改用等价的 `LifecycleEventObserver` + `LocalLifecycleOwner`，未动 build.gradle
  - **缺陷 B 修复**：`canPrompt()` = 已授权 or 从未问过 or `shouldShowRequestPermissionRationale`；为 false 时按钮直接 `ACTION_APPLICATION_DETAILS_SETTINGS` 跳应用详情页（Android 11+ 勾「不再询问」后 `launcher.launch()` 静默失败），按钮文案同步变「已被系统拒绝，去设置里开启定位」
  - **缺陷 C 修复**：权限提示块嵌进 `if (form.environmentEnabled)`，关掉环境感知后不再显示定位相关红字
* ✅ **容器接线（本 AI 2026-11-20 15:5x 补齐）**：`CompanionApp` 原来既没暴露 `locationCache` / `cityGeocoder`，`MainActivity` 也就传不了，手动城市输入框此前是**禁用**态。已补（`CompanionApp.kt`）：

  ```kotlin
  private val envStore by lazy { RoomKeyValueStore(database.appStateDao()) }
  val locationCache: LocationCache by lazy { LocationCache(envStore, AndroidLocationPort(this)) }
  val cityGeocoder: CityGeocoder by lazy { CityGeocoder(envStore, OkHttpHttpText()) }
  ```

  `MainActivity` 里 `SettingsScreen(...)` 已加 `cityGeocoder = app.cityGeocoder, locationCache = app.locationCache,`
  （`SettingsScreen` 已内置 `LaunchedEffect(locationCache)` 回显城市名）。
  **验证：`:app:compileDebugKotlin` BUILD SUCCESSFUL（13s，非 UP-TO-DATE）**。

#### UI 线两处偏离（已核代码属实，接受）

1. **Cleared 不弹 Toast**：计划书 §V2.1.1 只要求「空结果 / 网络失败」两条 Toast，未提「清空」；`CityGeocoder.kt` 注释明确「Cleared：输入被清空 → 不 Toast」。UI 照数据层约定做，**接受**。
2. **降水阈值滑块下限 1 而不是 0**：`EnvironmentRuleEngine` 是 `precip > threshold`，阈值 0 等于「只要预报有降水概率就提醒」→ 天天喊带伞。UI 收紧到 1（仍 ≤100），数据层仍允许 0–100。**接受**，已写入计划书 §V2.1.6「UI 下限 1」。

> `lateNightHour` 数据层注释「v2 不对外开放编辑」→ UI 不给入口，persist 保留原值，正确。

### 阶段 V2.3：语音输入（STT，云端 ASR）+ 通知栏快捷入口（6 个任务｜V2.3.1–V2.3.6 代码全部通过；真机待跑）

> **测试基线：694 全绿**（`failures=0 errors=0`，85 suite；2026-11-20 20:15 审查线实测 XML 解析）＝ v1 收尾 418 + 第二版新增 276。
> （构成：V2.3.1 `PcmUtilsTest` 11 + V2.3.2 `WavHeaderTest` 16（头 4 + 音量 6 + truncate 6）+ V2.3.3 `DeepSeekAsrClientTest` 28 + `SttManagerTest` 5 + V2.3.4 `VoiceBarLevelTest` 5；V2.3.5 **0 新增**（纯 UI）；其余为并行线 `BootRestorePlannerTest` 8、`FakeReminderDaoTest` 8、`ReminderServiceTest` 4→7 等。）
> 每改完一个任务重跑 `testDebugUnitTest` 并更新本表。计划依据：`陪聊APP第二版开发计划.md`（v3.4，云端 ASR）。

| 任务 | 状态 | 证据（单测 suite / 文件） |
|---|---|---|
| V2.3.1 ASR 端口 + PCM/WAV 工具 | ✅（审查线 2026-11-20 17:5x） | `PcmUtilsTest`(11)；`data/speech/`：`AsrResult`(sealed) + `AsrPort`(接口) + `PcmUtils`(纯函数)，零 Android 依赖 |
| V2.3.2 音频录制 | ✅（初审 17:5x → 修复复测 18:0x → 120s 截断补丁 18:5x） | `WavHeaderTest`(16：头 4 + 音量 6 + `truncatePcmToMaxDuration` 6)；`WavHeader` + `AudioRecorder`；契约写进计划书 §V2.3.2/§V2.3.4 |
| V2.3.3 DeepSeek ASR 实现 | ✅ **通过**（初审 18:2x 判 2🔴 → 修复后审查线 18:4x 复核转 ✅；留 1 项 ⚠️ 交 V2.3.4） | `DeepSeekAsrClientTest`(28) + `SttManagerTest`(4)；`DeepSeekAsrClient`（chunk 扫描解析）+ `SttManager`（开关 + 空数组双前置）+ `CompanionApp` 3 个 `by lazy` |
| V2.3.4 语音输入 UI（UI 线） | ✅ **代码通过**（审查线 18:5x；真机 8 条待用户） | `VoiceBarLevelTest`(5)；`RecordPermission` + `VoiceInputController`（状态机）+ `VoiceInputOverlay`（呼吸圆/18 柱波形）+ `ChatScreen` 长按手势 |
| V2.3.5 设置页 STT 配置 | ✅ **代码通过**（UI 线 20:1x + 逻辑线 20:2x 合拢审查；真机 6 条待用户） | 0 新增测试（逻辑线 +1：`SttManagerTest` 4→5）；`SettingsForm.sttEnabled`+SwitchRow（`SettingsScreen` L49/L273）、`ChatScreen` `sttEnabled` 参数（L71/L96 key 含它、关掉不建 controller）/`InputBar`（L446/L541）、`MainActivity` initial L111 + persist L162 + 聊天分支 L238；`ConfigRepository.getSttEnabled/setSttEnabled`（L125-127，key `stt_enabled`，默认 true）+ `CompanionApp:148` lambda 现读 + `SttManager.isEnabled()`（L21，现查；当前仅测试调用，见逻辑线审查 ⚠️1） |
| V2.3.6 通知栏常驻快捷入口 | ✅ **代码通过**（原 1 项 🔴「点击不产生识别」已闭环 + 补 `NEW_TASK` 加固；留 1 项 ⚠️ `onPause` 语义 + 真机 6 条未验，见文末复核） | `QuickAccessNotificationSpec`(5) + `QuickAccessEntrance`(4) + `VoicePrefillTest`(3)；`service/` 3 个新文件 + `VoiceInputActivity` + manifest 2 权限/specialUse service/透明 activity + `Theme.Companion.Transparent` + `quick_access_enabled`(默认 false) + `KEY_VOICE_PENDING_TEXT` |

#### V2.3.1 审查明细（审查线 2026-11-20 17:5x，597 全绿）

**✅ 通过（对照计划书 §V2.3.1 验收逐条核）**

* **`AsrResult`**（sealed）：`Success(text, durationMs)` / `Failure(reason)`，与计划书 L997 逐字一致；`Failure.reason` 中文文案约定「可直接展示」，对齐 V2.3.4 的「失败 Toast 直接用 reason」
* **`AsrPort`**：`suspend fun transcribe(wav: ByteArray): AsrResult`，与计划书 L1000–1002 逐字一致；KDoc 记录了 16 位 PCM WAV / ≤8 MiB / ≤120 秒 / 双声道只取左声道的 API 契约
* **`PcmUtils`**：
  - `stripWavHeader` 按 RIFF chunk 链表解析（`pos=12L` 起步、`size and 1L` 奇字节对齐），不硬编码 44 字节；`dataEnd > bytes.size` 与 `next <= pos` 双越界校验；无 data 抛异常 —— 计划书 L991–992 达标
  - `s16leToFloat` `(low or (high shl 8)).toShort()/32768f` 与计划书 L993 公式一致；`floatToS16le` clamp + `SATURATE_FROM=0.9999` 饱和 + NaN→0，计划书 L994 达标
  - `pcmToWav` 标准 44 字节头全小端、`byteRate=sampleRate*channels*2`、`blockAlign=channels*2`，默认 16k/mono —— 计划书 L995–996 达标
* **`PcmUtilsTest` 11 用例**：验收 4 条全覆盖（标准 44 头 / LIST+fact / 无 data 抛 / 0·0.99997·-1 浮点 / 0.9999→32767·-1→-32768 / 头部字段全断言 / 单双声道 round-trip / int16↔float round-trip）；测试用小工具手工拼字节（`buildWav` 独立实现 RIFF 组装），不复用被测代码，无自证
* **597 / 77 suite 属实**：审查线重跑 `testDebugUnitTest`（UP-TO-DATE 缓存 + XML 解析确认）`failures=0 errors=0 skipped=0`；新增 `PcmUtilsTest`(11) 恰为 552→563 的部分，其余 34 用例来自并行线（V2.1.5 复核 +15、V2.1.7 `UserMetaMarkerTest`(9)、V2.3.2 `WavHeaderTest`(4)、`WeatherSummaryTest`(7)）

**⚠️ 需要改进（不阻塞 V2.3.1，留给 V2.3.3）**

1. **双声道去交错缺口（架构契约）**：`AsrPort` KDoc 声明「双声道只取左声道」，但 `stripWavHeader` 只切 data 字节不去交错，`pcmToWav` 又能产出双声道 —— 当前无任何代码实现「左声道提取」。正常路径（`AudioRecorder` 恒 `CHANNEL_IN_MONO`）不会触发，但 V2.3.3 的 `DeepSeekAsrClient` 若收到双声道 WAV 直接上传，右声道会被 ASR 当垃圾。**V2.3.3 必须在客户端做「双声道 → 左声道」归一化**（去交错），或在 `AsrPort` 实现里拒绝双声道。
2. **`pcmToWav` Int 乘法溢出（理论）**：`byteRate = sampleRate * channels * 2` 是 Int 运算，`sampleRate` 无上限校验，极端值（如 >1e6）会溢出成负。实际录音 16k 不会触发，但可加 `require(sampleRate <= 48000)` 或改 Long 计算。
3. **`stripWavHeader` 不校验 fmt 位深/声道**：若传入 8 位或 24 位 WAV，`s16leToFloat` 会按 16 位误读。V2.3.3 应在上传前校验 `bitsPerSample == 16`。

**未发现 🔴 必修 bug。**

\---

### 阶段 V2.2：追问型任务 / 监督链（5 个任务｜V2.2.1–V2.2.5 ✅ 全部审查通过）

> **测试基线：666 全绿**（`failures=0 errors=0 skipped=0`，82 suite）＝ 552 基线 + V2.2.1 `FakeReminderDaoTest` 8 + 并行三线约 106。
> 审查线已独立聚合 `app/build/test-results/testDebugUnitTest/*.xml` 复核该数字（非转述）。
> ⚠️ **666 不是 V2.2.1 的独立成绩**：新增 suite 里 `AlarmCodesTest`(6)、`BootRestorePlannerTest`(8)、`EnvironmentCheckSchedulerTest`(6)、`ProactiveCheckRunnerFestivalTest`(6)、`EnvironmentHookServiceTest` 7→10、`WeatherCacheTest` 12→16、`ProactiveDeliveryRunnerTest` 7→9、`ProactiveDeliveryDndTest` 10→12 来自 V2.1.5 / V2.1.7，`PcmUtilsTest` / `WavHeaderTest` / `DeepSeekAsrClientTest` / `SttManagerTest` 来自 V2.3.x。反过来看是更强的证据：**V2.2.1 的改动与三条并行线共存可编译、可全绿**。
> 计划依据：`陪聊APP第二版开发计划.md`（§V2.2.1 / §3.1.1 / §3.2）。
> **V2.2.2 后（20:15 实测）：694 全绿 / 85 suite** ＝ 666（18:35）+ V2.2.2 的 16（`MoodServiceFollowupTest` 8 + `FollowupPortImplTest` 6 + `MoodUpdateTest` 6→8）+ 并行线 12（`VoiceBarLevelTest` 新 5、`SttManagerTest` 4→5、其余 6 条散在 STT/设置相关 suite）。审查线已独立聚合 XML 复核。
> **V2.2.3 后（20:38 实测）：755 全绿 / 93 suite** ＝ 694 + V2.2.3 的 30（`FollowupIntentTest` 9 + `FollowupExtractionParserTest` 9 + `FollowupTaskExtractorTest` 10 + `BootRestorePlannerTest` 8→10）+ 并行线 31（5 个新 suite：`RootPermissionCheckerTest` 11 / `RootProbeTest` 8 / `QuickAccessNotificationSpecTest` 5 / `QuickAccessEntranceTest` 4 / `VoicePrefillTest` 3）。**副本（隔离 GRADLE_USER_HOME）与工作区两处 XML 我各聚合一次，数字一致。**
> **V2.2.4 收口后（21:51 实测）：800 全绿 / 98 suite**（798 + 收口轮新增 2 条：`timeout_schedule_failure_puts_row_back_in_pending` 扩展断言 + 新增 failed-timeout 行用例）。`scheduleOverdueFollowups` 已改名、判据已放宽为「overdue followup（不分 attemptCount）」，`at` 已无默认值 —— 四项收口全部落地。
> **V2.2.5 后（22:06 实测，`--rerun-tasks` 强制重跑）：818 全绿 / 101 suite** ＝ 800 + V2.2.5 的 18（`NotificationActionsTest` 6 + `ReminderActionHandlerTest` 7 + `PendingMessageQueueCancelFollowupsTest` 3 + `ProactiveDeliveryRunnerTest` 13→15）。审查线独立跑并自行聚合 XML（22:06:34 > 源码 22:03:42），**两条变异探针由审查线亲自复现**（改坏源码 → 精确单红 → 还原 → 818 复绿）。

| 任务 | 状态 | 证据（单测 suite / 文件） |
|---|---|---|
| V2.2.1 数据结构与迁移（DB 6→7） | ✅ **审查通过**（审查线 2026-11-20 18:4x） | `FakeReminderDaoTest`(8) + `AppDatabaseMigrationTest`(2，真机 9/9)；`schemas/6.json`(8 列) / `7.json`(10 列) |
| V2.2.2 追问判断（合并 mood 调用） | ✅ **审查通过**（审查线 2026-11-20 20:2x） | `MoodServiceFollowupTest`(8) + `FollowupPortImplTest`(6) + `MoodUpdateTest` 6→8；`domain/reminder/FollowupPort` + `data/alarm/FollowupAlarms` + `data/reminder/FollowupPortImpl` + `CompanionApp` 真实接线 |
| V2.2.3 追问任务提取 | ✅ **审查通过**（审查线 2026-11-20 20:4x；唯一 🔴「`concurrent_calls_create_only_one` 假绿」已按建议修：`async(Dispatchers.Default)` + Fake DAO `delay(10)`，20:46 复核 755 全绿） | `FollowupIntent`(9) + `FollowupExtractionParser`(9) + `FollowupTaskExtractor`(10) + `BootRestorePlannerTest` 8→10；`FollowupIntent`/`FollowupExtraction`/`FollowupPrompt`/`FollowupTaskExtractor` + §2.6 恢复链连带修复 |
| V2.2.4 追问闹钟链 | ✅ **审查通过**（审查线 2026-11-20 21:4x；三轮：R1 每日额度 + S3「静默死链」两个真缺陷均已闭环，794 / 98 全绿） | `FollowupChainRunnerTest`(15) + `PendingMessageContentFollowupTest`(4) + `BootFollowupRestorerTest`(4) + `ProactiveDeliveryRunnerTest` 9→13；`data/reminder/FollowupChainRunner` + `data/reminder/BootFollowupRestorer` + `PendingMessageContent.Followup` + receiver 两分支 + `FOLLOWUP_TIMEOUT_MS`；**真机三码/5→ABANDONED 未验** |
| V2.2.5 追问通知按钮（UI 线） | ✅ **审查通过**（审查线 2026-11-20 22:1x；独立 `--rerun-tasks` 复跑 818 / 101 suite 全绿 + 两条变异探针亲自复现） | `NotificationActionsTest`(6) + `ReminderActionHandlerTest`(7) + `PendingMessageQueueCancelFollowupsTest`(3) + `ProactiveDeliveryRunnerTest` 13→15；`domain/proactive/NotificationActions`（纯函数）+ `data/reminder/ReminderActionHandler` + `ProactiveNotification.followupReminderId` + `PendingMessageQueue.cancelPendingFollowups` + `ReminderDoneReceiver.EXTRA_ACTION/EXTRA_REMINDER_ID`；**真机双按钮未验** |

#### V2.2.1 审查明细（审查线 2026-11-20 18:4x，666 全绿 + 真机 9/9）

**✅ 通过（逐条对照 §V2.2.1 验收 + §3.1.1 六条落地细节）**

* **迁移三处一致性已闭环（本任务唯一真风险点）**：entity `@ColumnInfo(defaultValue="0"/"5")` ↔ `MIGRATION_6_7` 的 `INTEGER NOT NULL DEFAULT 0/5` ↔ `7.json` 的 `notNull=true default="0"/"5"`，逐字段对齐；表名用复数 `reminders`（写成单数会直接崩）
* **导出顺序符合 §3.1.1**：`6.json`（8 列、无 `attemptCount`、`identityHash=2184dddf…`，18:23:45）先于 `7.json`（10 列、`ef86e714…`，18:24:19）；「先删 fallback 再测」的反例没发生
* **instrumented 实测（审查线独立读 `TEST-<DEVICE_MODEL> - 13-_app-.xml`）**：`tests=9 failures=0 errors=0 skipped=0`，含 `migrate6To7_preservesDataAndAddsDefaults`(0.029s) / `migrate6To7_preservesAllLegacyColumns`(0.049s)；断言全用 `org.junit.Assert.*`，无裸 `assert`（androidTest 里裸 assert 会静默跳过 → 假绿）
* **第二个用例打在真正会翻车的点上**：`priority` 显式给值（v6 该列 `NOT NULL` 且无 SQL DEFAULT，Kotlin 的 `=5` 不落 DDL），并断言 `followupInterval`/`stopCondition`/`priority` 三个旧列原样保留 —— 手写 `execSQL` 迁移最容易丢的就是这些
* **`runMigrationsAndValidate(db, 7, true, …)` 校验全库 8 张表**，不只 reminders → 顺带证明其他表 schema 没被破坏
* **`FakeReminderDao` 语义与真实 SQL 对齐**：`activeFollowup` 三判据（`pending` + `followup` + `attemptCount>0`）+ `triggerTime` 升序取首条；`anyPendingFollowup` 只要 `pending`（含 `attemptCount=0`）；`incrementAttempt` 累加、未知 id 静默、不牵动 `maxAttempts`
* **8 条单测方向选得准**：`anyPendingFollowup_returns_row_when_attemptCount_zero` 正是 §3.1.1 对照表里「用错查询 → 两条并行追问链、每天被催两遍」的回归保护；`activeFollowup_ignores_non_pending_and_non_followup` 同时挡住 DONE 与 INSTRUCTION 两种误命中
* **`ABANDONED` 是纯增量、零副作用**：`getPending`/`getDue` 均带 `state='pending'`，abandoned 行不会被当待办捞出；全仓 `ui/` 0 处引用 `ReminderState` → 不会把 `abandoned` 原文漏到界面
* **`ReminderEntity` 两列追加在末尾且有默认值** → `CompanionApp:272`、`AppDatabaseTest:111` 等命名实参构造零改动，`ReminderType` 未动
* **`build.gradle` 三处都踩对**：`kapt{}` 在**顶层**（放进 `android{}` 会 `Could not find method kapt()`）、`androidTest assets.srcDirs += schemas`（`MigrationTestHelper` 必需）、`room-testing` 复用 `$room_version` 不写死版本

**⚠️ 需要改进（不阻塞验收）**

1. **`FakeReminderDao.observeAll()` 是死流**：`flowOf(快照)` 只发射一次，之后 insert/update 不重发，而真实 Room 是热流。V2.2.2+ 若有测试 `first()`/`collect` 等「插入后收到新值」会假绿或挂死。建议 `MutableStateFlow<List<…>>` + `map`，一次做对（同目录其他 Fake 用 `emptyFlow()` 更差，不能当先例）
2. **`incrementAttempt` 无状态守卫、无幂等**：`WHERE id = :id` 对 DONE/CANCELLED/ABANDONED 行也会 +1。V2.2.4 里 FOLLOWUP 闹钟可能重复投递（AlarmManager 抖动 / BOOT 恢复重排 / 进程被杀重来），一次「发追问」被 +1 两次 → 5 次上限实际只催 4 次就 ABANDONED。**V2.2.4 必须**用「条件更新 + 检查 affected rows」或先 `getById` 校验 `state=='pending' && attemptCount < maxAttempts`
3. **`activeFollowup()` 不排除触顶行**：若 V2.2.4 触顶时漏转 ABANDONED，`MoodService` 会永远问「做完了没」。要么把 `attemptCount < maxAttempts` 写进判据，要么在 V2.2.4 明确契约并补一条测试
4. **默认值 `5` 在三处重复**（entity 注解 / MIGRATION DDL / 计划书）：改一处漏一处 = 打开即崩（`Migration didn't properly handle`）。建议抽 `const val DEFAULT_MAX_ATTEMPTS = "5"`（String 常量既可用于注解也可 DDL 插值），`attemptCount` 同理
5. **「改字段必须同时升 version」是 `exportSchema=true` 之后的铁律**：DeepSeek 已实测踩到（6.json 被 kapt 刷成 10 列，靠临时回退重建救回）。另外它说的备份 `build/v211-bak/6.json` **现已不存在**（`app/build` 18:33 被重建）→ 回滚不能依赖 build 目录；真正的护栏是 `app/schemas/` 未被 `.gitignore` 排除、会随代码进版本库
6. **迁移只覆盖 6→7 且 fallback 已删**：设备若停在 <6 的库（旧 APK 残留 / 恢复的旧备份）→ `IllegalStateException` 打开即崩。当前唯一设备在 v6、实机升级已验证，不阻塞；若要兜底可加 `.fallbackToDestructiveMigrationFrom(1,2,3,4,5)`（只对 6 以下生效，不违背「删掉无差别破坏式回退」）
7. **结果快照早于工作树**：单测 XML 时间 18:35:19，之后 18:35:43–18:37:21 又有 5 个 UI 文件被改（`RecordPermission` / `VoiceInputController` / `ChatScreen` / `VoiceInputOverlay` / `MainActivity`），`assembleDebug` 也早于它们 → **那 5 个文件「可编译」未验证**（与 DB 无关，不影响本任务结论；UI 线自查）

**🔴 必修：无。**

**⚠️ 顺手记（不属于 V2.2.1）**

* 仓库根有两个 `hs_err_pid7200.log` / `hs_err_pid10960.log`（17:31，各 47 KB），`Problematic frame: C [sherpa-onnx-jni.dll+0xa7601]` —— **JVM 在 sherpa JNI 里 SIGSEGV 崩过**。归 STT 线确认触发路径，两个 log 从仓库根删掉
* 用户级 `~/.gradle/gradle.properties` 确有 4 行失效代理（`127.0.0.1:12450`）。「每次构建临时剔除再还原」容易忘还原、也让构建不可复现；建议用户一次性删掉这 4 行

#### V2.2.2 审查明细（审查线 2026-11-20 20:2x，694 全绿）

**✅ 通过（逐条对照 §V2.2.2 验收 + 交接提示词 §2 的硬约束）**

* **「假完成陷阱」已排除（本任务唯一致命项）**：`CompanionApp` L222–L243 三行真实接线 —— `followupAlarms = FollowupAlarms(alarmScheduler)` → `followupPort = FollowupPortImpl(dao, alarms)` → `moodService(followup = followupPort)`；`MoodServiceFactory.create` 第 3 参透传到 `MoodService` 第 4 参，**没有留 null**
* **四条硬约束逐条命中**：① `active == null` + 未满 5 轮 → 闸门处直接 `return false`，一次模型都不调（`repeat(4)` 断言 `calls == 0`）；② `followup_status` 缺失 → 走 `advance`，**不是 no-op**；③ `abandoned` 落 `ReminderState.ABANDONED`（该用例用**真实** `FollowupPortImpl` + `FakeReminderDao` + 录制式闹钟断言，不是假 port 自说自话）；④ `Result.failure` → 不改 mood、**不** advance
* **解析失败但链不卡死**：`MoodUpdateParser.parse(raw) ?: run { if (active != null) advance(...); return true }` —— 非法 JSON 时 mood 不写、链仍推进，`invalid_json_with_active_advances_and_keeps_mood` 断言 `currentMood()` 仍是 `happy`
* **`advance` 顺序与语义正确**：`cancelTimeout` → `getById` 取 `followupInterval` → `updateTriggerTime(now + minutes)` → `scheduleFollowup(新 triggerTime)`；`advance_cancels_timeout_then_schedules_followup` 断言 `events[0]/events[1]` 顺序且 `events.size == 2`（没有多余调用）
* **`advance` 绝不 `incrementAttempt`**（`advance_does_not_increment_attempt` 断言计数不变）—— 这是「恰好 5 条追问」不被提前触顶的唯一保障
* **分钟单位正确**：`minutes * 60_000L`（`Int * Long`），不是 `* 1000L`；`null` 走 `DEFAULT_INTERVAL_MINUTES = 15` 且与测试期望 `now + 15*60_000` 一致
* **`FollowupAlarms` 不碰 Context、requestCode 全走 `AlarmCodes`**，`reminderId` 传真 id（非 `-1L` 哨兵）；`cancelAll` 复用 `AlarmCodes.cancelAll` 三码，`complete`/`abandon` 用例断言三码全被取消
* **`system()` 逐字兼容**：`MoodUpdateTest` 用**字面量**断言旧输出，只有 `withFollowup = true` 才追加 `followup_status`/`followup_reason` 与三值语义 → 5.1 的提示词契约没被顺手动掉
* **`MoodUpdate` 两字段带默认值** → 老调用点零改动；`followup_status` 用 `runCatching{}.getOrDefault(null)`，`"followup_status": null` 不会把整条 parse 带崩（Gson 的 `JsonNull.asString` 会抛，这里接住了）
* **`FollowupStatus.fromRaw` 大小写 + trim 容错**，未知值 → `null`（由调用方按 continue 处理）
* **范围干净**：`AppDatabase` 仍 `version = 7`、`MIGRATION_6_7`/`addMigrations` 原样；`AlarmCodes` 未动；`ui/` 的改动全部来自 UI 线（20:11–20:13 的 `MainActivity`/`ChatScreen`/`SettingsScreen`），不属本任务
* **测试快照覆盖当前树**（比上轮好）：XML 最新 20:15:00，源码最大 mtime 20:13:43 → 694 是**改完之后**的成绩，不是旧快照

**⚠️ 需要改进（无阻塞项，但 V2.2.4 必须知道）**

1. **每轮 `advance` 会把追问闹钟不断往后推**：用户连续聊天（每轮间隔 < 15 min）时 `triggerTime` 每轮重置 → `attemptCount` 不增长、链可能长时间不发第一条；而 `activeFollowup` 判据是 `attemptCount > 0`，所以发过的链不会熄。语义上可接受（聊天中 MoodService 每轮都在问「做完了没」），但 **V2.2.4 要在计划里把「闹钟可被对话无限顺延」写成设计而非 bug**，否则真机验收会出现「设了 15 分钟追问，聊半小时也没响」的误判
2. **链活跃期间每轮一次独立 API 调用**：绕过 5 轮闸门是计划要求，费用是平时的 5 倍。V2.2.4/2.5 验收时留意账单
3. **`FollowupStatus.fromRaw` 未知值路径无测试**：`"followup_status":"weird"` → null → advance 这条没用例（「缺失」那条有，等价但不等同）
4. **`active == null` + 恰好第 5 轮（port 已设）无回归用例**：`MoodServiceTest`（无 port）覆盖 5.1，`MoodServiceFollowupTest` 覆盖「port 非空但 active 为 null 且未到阈值」，两条中间的交点没人测
5. **`MoodServiceFactory.create` 仍保留 `followup: FollowupPort? = null`**：生产已显式传值，但默认 null 是持续邀请 —— 下一个接线的人漏传就复现本任务的假完成。建议 V2.2.4 接线完成后去掉默认值，或改 `requireNotNull(followup)`

**🔴 必修：无。**

**⚠️ 顺手记（事实订正）**

* 报告说「交接基线 666/82 过时、改动前实际 678/83」：666/82 是审查线 **18:35 的实测值**，不是笔误也不是过时；18:35→20:15 并行线（UI/STT 设置）又加了 12 条（`VoiceBarLevelTest` 新 5 + `SttManagerTest` 4→5 + 其余 6 条）→ 678 的倒推成立。当前实测 **694 / 85 / 0 / 0 / 0**
* 报告说 `MoodUpdateTest` 有「4 条老测试」——实际 **6 条**（改后 8 条，+2 本身正确）
* **「678/83 全绿」那一轮不可能成立（V2.3.5 逻辑线顺带定位）**：`FollowupPortImplTest.fixture()` 原为非 suspend 却调 suspend 的 `dao.insert` → **整个单元测试源集编译失败**，全量与 `--tests` 过滤运行都起不来。缺陷自 V2.2.2 交付起存在，2026-11-20 20:13:43 由 V2.3.5 逻辑线改成 `private suspend fun` 才解除。⇒ 当时的 678/83 只能是旧 XML 或估算，**不是那轮真成绩**；可用成绩是修完后的 **694 / 85**。已写进「测试纪律」第 4 条。

#### V2.2.3 审查明细（审查线 2026-11-20 20:4x，755 全绿）

**✅ 通过（对照 §V2.2.3 验收 + 交接提示词 5 条确认项）**

* **坑 A 已避开**：首次闹钟走 `alarms.scheduleFollowup(id, triggerAt)`（内部 `AlarmCodes.followup(id)` + `AlarmKind.FOLLOWUP`），用例还写了 `assertNotEquals(AlarmCodes.first(id), e.requestCode)` 把「写成首钟」钉死；`FollowupTaskExtractor.kt` 里 `AlarmCodes.first` **0 命中**、`activeFollowup` **0 命中**（grep 复核，属实）
* **坑 B（本审查线上轮发现的雷）已真修**：`RestoreTarget` 增 `kind`、`plan` 入参升 `Triple(id, triggerTime, type)`、`restoreAlarmsAfterBoot` L677/L692 与 `restorePendingRemindersLight` L760/L773 都改走 `target.kind`；`followup` → `AlarmCodes.followup` + `FOLLOWUP`，其余 type 行为不变（两条新用例各钉一边）
* **`restorePendingRemindersLight` 保住了 `triggerTime > now` 过滤**（L756）—— 最容易在「改走 plan」时顺手丢掉的一行，丢了就变成「每次打开 App 重放全部历史 pending」。**特意查过，没丢**
* **执行顺序与提示词一致**：闸门 → 转录 → 模型（锁外）→ 解析（锁外）→ `resolveTriggerTime`（锁外纯计算）→ `mutex.withLock { 查 + 建 + 排钟 }`；`return mutex.withLock { … }` 的返回值形状正确，`return@withLock` 标签用法无误
* **防重复用 `anyPendingFollowup()`**（L75），并有 `pending_followup_with_zero_attempts_blocks_duplicate` 专门钉「`attemptCount=0` 的刚建链也算已有」—— 正是 V2.2.1 对照表划出的那条
* **时间解析复用 `TimeAnchorConverter.parseComponents`**，没另写解析器；`start` 指向过去/解析失败 → 退回 `now + interval`（「今天早上8点」在 10:00 的场景有用例钉住）
* **解析器容错完备**：`is_followup` 布尔/字符串双接受、`task` 空白 → null、`interval_minutes` 缺失/0/负数 → `FollowupPortImpl.DEFAULT_INTERVAL_MINUTES`（**复用常量，没另写 15**）、`>1440` 钳到 1440、```json 包裹与前后说明可容忍、非法 JSON 不抛
* **落库失败不排钟**：`insert` 的 `runCatching` 失败直接 `Result.failure`；排钟失败也返回 failure → 不会出现「闹钟指向不存在的行」
* **范围干净**：`AppDatabase` 仍 `version = 7`（mtime 18:30）、`ReminderEntity` 未加字段、`ReminderAlarmReceiver` mtime 18:12:53 未触碰、`AlarmCodes`/`AlarmScheduling`/`FollowupAlarms` 签名未动、`ui/` 未动
* **数字双处复核**：副本 `C:\1\tmp\v223-verify` 与工作区 `app/build/test-results` 各聚合一次，均 `SUITES=93 TESTS=755 FAIL=0 ERR=0 SKIP=0`；四个目标 suite 计数 9/9/10/10 与报告一致

**🔴 必修（1 项：测试假绿）**

* **`concurrent_calls_create_only_one` 根本没测并发**：`fixture` 注入的 `callJson` 是 `{ _, _, _ -> calls++; Result.success(raw) }` —— **不含任何挂起点**；`FakeReminderDao` 的 `suspend` 方法也无挂起点。`runBlocking` 单线程事件循环下，`async` 的 `a` 会**整个跑完**才轮到 `b`，两条协程在 `mutex` 前不交错 → 这条用例退化成「顺序调用两次」，与 `two_sequential_calls_create_only_one` 同义。**验收项「并发调用只建一条（锁生效）」实际未被验证**，属项目规则「测试纪律」明令禁止的假绿。
  * 最小修法（二选一）：① 注入的 `callJson` 里加 `delay(30)`，两条协程会在 `delay` 处真实交错；② `async(Dispatchers.Default) { … }`，两个线程真竞争同一把锁。
  * 修完后 `assertEquals(1, dao.items.size)` 才有意义。

**⚠️ 需要改进（不阻塞验收）**

1. **链活跃期间每轮多烧一次模型调用**：`two_sequential_calls_create_only_one` 断言 `calls() == 2`，即「已有链时仍先调模型、解析完才在锁里发现已有链并丢弃」。叠加 V2.2.2（链活跃时每轮也调 mood 模型）→ **链活跃期间每轮 2 次额外 API**。建议在模型调用**之前**加一次便宜的 `dao.anyPendingFollowup()` 预检（锁内仍留权威复查防竞态）
2. **`kindForRestore` 已是死代码**：`CompanionApp.kt` L728 定义，全仓 **0 调用点**（两处恢复都改用 `target.kind`）。「保留作兜底」不成立 —— 未调用的 private 函数只带来编译警告与「还有别处在用」的错觉。删掉或就地注明原因
3. **发版顺序风险（真机坑）**：V2.2.3 一旦被 UI 线接上、V2.2.4 还没上线 → followup 行到点只打一行日志（receiver 的 FOLLOWUP 分支是空壳）；而 `restorePendingRemindersLight` 只排 `triggerTime > now` → 该行**永久 pending 且再无闹钟** → `anyPendingFollowup()` 恒真 → **新建链被永久挡住**，且 V2.2.5 的取消入口还没做。**结论：V2.2.3 + V2.2.4 必须同一个包上线；在此之前别让 UI 线把 `extractAndSchedule` 接进 `ChatViewModel`。**（已核：`ChatViewModel` 里 `extractAndSchedule` 0 命中，尚未接入，安全）
4. **`FollowupIntent` 的 `"看着我"` 没有对应否定**：`"别看着我了"` 仍命中 → 白烧一次模型调用；`NEGATIONS` 补 `"别看着"`/`"不用盯着"` 即可
5. **新链被静默丢弃时用户无感知**：已有链时用户说「监督我跑步」，返回 `success(null)`，不落库不提示。V2.2.5 需要「当前正在追踪 X，是否换成 Y」的入口，否则用户只能等 5 次上限耗完

**⚠️ 顺手记（事实订正）**

* 报告说并行线「新增 `QuickAccessEntranceTest` 等 9 个用例」：实际 **31 条 / 5 个新 suite** —— `RootPermissionCheckerTest` 11、`RootProbeTest` 8、`QuickAccessNotificationSpecTest` 5、`QuickAccessEntranceTest` 4、`VoicePrefillTest` 3。`755 = 694 + 30 + 31` 完全对得上（按 mtime 20:15 之后改动的测试文件逐个统计 `@Test` 得出）

\---

### 阶段 V2.4：root 自动保活（3 个任务｜V2.4.1 ✅ 审查通过；V2.4.2 ✅ 审查通过（1 ⚠️ 已修）；V2.4.3 ✅ 代码完成（待审查））

> **测试基线：755 / 93 suite 全绿**（审查线 2026-11-20 20:47 实测，`failures=0 errors=0 skipped=0`；XML mtime `20:47:17` 晚于源码 `20:46:42`）。
> 计算：694/85（20:2x）＋ V2.2.3 的 30 ＋ 并行语音/快捷入口线 31（`RootPermissionCheckerTest` 11 / `RootProbeTest` 8 / `QuickAccessNotificationSpecTest` 5 / `QuickAccessEntranceTest` 4 / `VoicePrefillTest` 3）。
> **V2.4.2 后（21:10 实测）：763 / 94 suite（本线自身口径）** ＝ 755 + V2.4.2 的 8（`KeepAliveScriptRunnerTest`）；XML mtime `21:10:29` 晚于源码 `21:10:12`。
> ⚠️ **同一次全量运行的合计是 782 / 96 suite**：21:08–21:09 并行线（V2.2.4 追问链）落地了 `FollowupChainRunnerTest`(15) + `PendingMessageContentFollowupTest`(4) ＝ +19 / +2 suite，`763 + 19 = 782` 精确吻合，`FAIL=0 ERR=0 SKIP=0`（含其工作树）。V2.4.2 的独立增量按「我的用例数」记，不把并行线的 19 条算进来。
> **V2.4.3 后（21:13 实测）：786 / 97 suite** ＝ 782 + V2.4.3 的 4（`KeepAliveUiStateTest`）；XML mtime `21:13:28` 晚于源码 `21:12:5x`；`FAIL=0 ERR=0 SKIP=0`。**起点实测 782 / 96**（不是提示词估的 762/763 —— 并行线那 19 条当时已在树里）。

| 任务 | 状态 | 证据（单测 suite / 文件） |
|---|---|---|
| V2.4.1 root 权限检测 | ✅ **审查通过**（审查线 2026-11-20 20:4x；两轮：首轮 6 条 ⚠️ 全部落地后复核通过） | `RootProbeTest`(8) + `RootPermissionCheckerTest`(11)；`domain/system/`：`ShellOutcome` / `ShellRunner`(接口) / `RootProbe`（纯判据 + `DEFAULT_TIMEOUT_MS` + `LOG_HEAD_CHARS`）；`data/system/`：`ProcessShellRunner`（`su -c` 起进程）/ `RootPermissionChecker`（`probe()` / `isRooted()` / `run(cmd, timeoutMs)`） |
| V2.4.2 保活脚本库 | ✅ **代码完成**（2026-11-20 21:05；待审查；真机未验） | `KeepAliveScriptRunnerTest`(7)；`domain/system/KeepAliveScripts`（5 条命令纯函数 + `DUMP_TIMEOUT_MS=5s` + `DEFAULT_TIMEOUT_MS` 取自 `RootProbe`）+ `data/system/KeepAliveScriptRunner`（`probe()` 一次判 root；逐条跑，单条失败不断链、不改外层 `Result`）；`CompanionApp` 零改动、零调用点 |
| V2.4.3 设置页保活按钮 | ✅ **代码完成**（2026-11-20 21:13；待审查；真机未验） | `KeepAliveUiStateTest`(4)；`ui/settings/KeepAliveUiState.kt`（sealed state + `keepAliveResultToUi` 纯函数）+ `SettingsScreen.kt`（状态 4 行 / 按钮 Row / 两个 AlertDialog，**未新增入参、未动 MainActivity·CompanionApp·data·domain**） |

#### V2.4.1 审查明细（审查线 2026-11-20 20:4x，755 全绿）

**✅ 通过**

* **分层**：`domain/system` 零 Android import（只用 kotlin + `ShellOutcome`），`data/system` 才碰 `ProcessBuilder`/`Dispatchers.IO`；`domain` **未**反向依赖 `data`（与 `KeepAliveIntents` → `KeepAliveLauncher` 同一套分法）
* **判据三条全要**：`!timedOut && exitCode == 0 && contains("uid=0")` —— 防「有 su 但被 SELinux 挡」与「Magisk 点拒绝也返回 0」
* **12 条坑逐条落实**：三独立参数 `ProcessBuilder(su, "-c", cmd)`、`redirectErrorStream`、**先起读线程再 waitFor**（防 64K 管道死锁）、stdin 立刻 `close()`、超限后**继续 drain**（`continue` 而非 `break`）、`destroyForcibly()` + 有界 `waitFor(1s)`、`exitValue()` 只在 waitFor 成功后取、候选 su 只在「起不来」时切换（不会 4×3s）
* **零启动副作用**：`RootPermissionChecker|ProcessShellRunner|RootProbe|ShellRunner` 在 `domain/system`、`data/system` 之外 **0 命中** → 冷启动不会弹 Magisk 授权框
* **19 条测试全 `org.junit.Assert`**，无裸 `assert(`；后加的 5 条钉住「取消透传」「`probe()` 原样透传（`assertSame`）」「非取消异常合成 `-1`」「不缓存」「单条命令超时覆盖」
* **超时/失败全部用 `ShellOutcome` / `Result` 表达，不抛异常**；唯一例外是 `CancellationException`（必须向上传播，否则 V2.4.2 的 5 条循环在协程取消后会跑完剩余几条）

**⚠️ 遗留（不阻塞）**

* `isRooted()` / `probe()` 只区分「无 su / 被拒 / 超时」；若要单独提示「SELinux 拒绝 exec」，V2.4.3 得读 `probe().output` 文本（现在只作诊断用）
* `ProcessShellRunner` **无 JVM 单测**（主机无 `su`、Windows JVM 起不了 `sh`）：与 `ConfigRepository` 同类的已知空白，真机一次性验证

**真机（未验）**：`adb devices` 空 → `isRooted()` 在非 root 设备返回 false、以及 `root=true` 两条**均未真机验证**；`root=true` 只能靠单测保证。接设备后一次性验证：`adb shell su -c id`（非 root 应非零/`Permission denied`，且不 ANR）。

#### V2.4.2 交付记录（实现线 2026-11-20 21:1x，**763 / 94 全绿**；审查 1 ⚠️ 已修）

**文件（3 个，全新增，零改动既有文件）**

| 文件 | 行数 | 职责 |
|---|---|---|
| `domain/system/KeepAliveScripts.kt` | 44 | 5 条命令的纯函数清单 + 单条超时 + 标签 + `optional`；零 Android 依赖 |
| `data/system/KeepAliveScriptRunner.kt` | 73 | 持有 `RootPermissionChecker`，先判 root 再逐条执行，逐条进日志 |
| `test/.../data/system/KeepAliveScriptRunnerTest.kt` | 195 | 8 条单测（假 `ShellRunner` 队列） |

**关键决策（与提示词示例的 1 处有意偏离）**

* **判 root 只 `probe()` 一次，用 `RootProbe.isRooted(probe)` 判**，而不是 `if (!checker.isRooted()) { checker.probe() }`：
  `isRooted()` 内部已经 probe 一次，再补一次 `probe()` 会让非 root 路径**调 runner 两次**（两次 `su`），
  与同节硬要求「非 root 时 Runner 只调用 runner 1 次（id）」直接冲突。改后语义完全等价（`isRooted()` 的定义就是 `RootProbe.isRooted(probe())`），
  单测 `non_root_returns_failure_and_runs_only_probe` 用 `calls == 1` 钉死。
* 单条失败**不进外层 `Result`**（验收「一条失败不中断后续」），只在日志行标 `fail`；`optional` 步骤额外加「（可选，失败可忽略）」。
* `dumpsys` 那条 5s、其余 3s（低端机冷启动 dumpsys 偶发 >3s）；`DEFAULT_TIMEOUT_MS` 直接取 `RootProbe.DEFAULT_TIMEOUT_MS`，不另写 3000。
* 日志格式固定 `[n/5] ok|fail 标签 → 命令 [→ 输出/原因]`，供 V2.4.3 对话框逐行显示。
* `KeepAliveScriptRunner` 用默认构造器注入（`KeepAliveScriptRunner()` 可直接 new），**`CompanionApp` 零改动**：全仓 `KeepAliveScripts|KeepAliveScriptRunner` 在 `domain/system` / `data/system` 之外 **0 命中**。
* **probe 原始数据保真、展示层清理（审查 ⚠️1 修复）**：`probe()` 返回的是**原始** `ShellOutcome`，不过 `run()` 里的 `normalize`（去 `\r` + trim）；非 root 失败消息里的 `output` 补了 `replace("\r","").replace("\n"," ")` 再 `take(200)`，否则 su 的 `\r\n` 会让 V2.4.3 的对话框/Toast 断行错乱。**没有**去改 `RootPermissionChecker.probe()` 加 normalize —— 它的契约是「原始探测结果」，V2.4.3 要靠 stderr 的换行结构判读 Magisk 拒绝原因。新增单测 `non_root_failure_message_has_no_newlines` 同时钉「无 `\n`/`\r`」与「内容不丢（含 `second line`）」，防「整段删掉」的假修法。

**数字**：`SUITES=94 TESTS=763 FAIL=0 ERR=0 SKIP=0`（本线口径；全量同一次运行为 `96 / 782`，差额 19 条来自 21:08 并行线 V2.2.4 的 2 个新 suite，均为 0 失败）；新 suite `KeepAliveScriptRunnerTest` 8/8；`compileDebugKotlin` + `assembleDebug` 均 BUILD SUCCESSFUL。

**真机（未验）**：`adb devices` 空，且 SDK 无 `emulator` 组件 / 无 AVD → 三条 `cmd appops get`（RUN_ANYWHERE / RUN_IN_BACKGROUND / AUTO_START）与 `dumpsys deviceidle whitelist` 前后对比**均未执行**；`RUN_IN_BACKGROUND` 的 op 名是否被 ROM 认，需接 root 设备后按提示词补跑。

#### V2.4.3 交付记录（实现线 2026-11-20 21:1x，**786 / 97 全绿**；审查 1 ⚠️ 已修）

**文件（1 新增 + 1 改，均为 UI 层；其它文件零改动）**

| 文件 | 行数 | 改动 |
|---|---|---|
| `ui/settings/KeepAliveUiState.kt`（新） | 37 | `sealed interface KeepAliveUiState`（Idle / Running / Done / Failed）+ `keepAliveResultToUi(Result<List<String>>)` 纯函数；零 Android 依赖 |
| `ui/settings/SettingsScreen.kt` | 665 → 779 | import 1 行（`FontFamily`；`AlertDialog`/`heightIn` 由既有 wildcard 覆盖）+ 1 行 `KeepAliveScriptRunner`；L117-124 状态 4 项；L582-622 一键保活说明 + 按钮 Row；L646-705 日志对话框 + 需要 root 对话框 |
| `test/.../ui/settings/KeepAliveUiStateTest.kt`（新） | 63 | 4 条纯 JVM 单测 |

**关键决策（按提示词的三个定案落地）**

* **不做「进页面先探测 root」**：`KeepAliveScriptRunner` 在 composable 里 `remember { KeepAliveScriptRunner() }`，按钮**永远可点**，点下去才 `run(pkg)`；runner 内部 `probe()` 一次判 root，非 root → failure 原文。全流程只弹一次 su 探测（非 root 时一次都不弹 Magisk）。
* **不碰 `KeepAliveStatus.rootAutoHandleSupported`**（恒 false 的 domain 占位）；**未新增任何入参**（`onResetKeepAliveGuide` 现成，MainActivity 已接线，零改动）。
* **失败语义原样透传**：`Failed.message` = `Result.failure` 的 `message` 原文（含 exit/timedOut/output），空/空白 message 才退到 `javaClass.simpleName`；对话框里不写「请检查 root 权限」这类改写。单测 `failure_keeps_original_message` 断言 `assertEquals(raw, message)`（逐字相等，不是 contains）。
* **日志开关独立**：`showLogDialog` 与 `keepAliveState` 分开，关掉对话框后状态保留；`Done` 逐行、`Failed` 显示同一段 message、`Idle/Running` 显示「还没有执行记录。」。
* **取消即停（审查 ⚠️1 已修）**：`scope` 是 `rememberCoroutineScope()`（跟随设置页生命周期），点完立刻返回 → 协程取消 → `CancellationException` 透传（V2.4.2 保证）→ 不崩、不留半截日志。**直接 `keepAliveRunner.run(pkg)`，没有 try/catch 也没有 `runCatching` 去吞**：`run()` 的契约是「除 Cancellation 外全部 `Result.failure`、绝不抛」，`runCatching` 捕 `Throwable` 唯一能捕到的就是**不该捕的取消**（语义违规；若日后把状态提升到 ViewModel / `rememberSaveable`，会变成幻影 `JobCancellationException` 对话框）。
* **最长 20s 无进度条**：`probe 3s + 5s + 3s×4` 上限；按钮 `disabled` + 文案「正在执行…」是唯一反馈。

**数字**：`SUITES=97 TESTS=786 FAIL=0 ERR=0 SKIP=0`；起点实测 **782 / 96**（V2.4.2 的 763 + 并行线 V2.2.4 的 19），本任务 +4 / +1 suite；新 suite `KeepAliveUiStateTest` 4/4；`compileDebugKotlin` + `assembleDebug` 均 BUILD SUCCESSFUL。

**真机（未验）**：无设备（`adb devices` 空、无 emulator/AVD）。待跑：root 设备按钮 5-20s 转圈恢复 + 5 行等宽日志 + `dumpsys deviceidle whitelist` 见包名 + AUTO_START 行 fail 带「（可选，失败可忽略）」；无 root 设备弹「未能自动保活」+ 正文含 exit/timedOut/output + 「打开保活引导」接 6.2 + 日志对话框显示同一段 message。**日志对话框 5 行真实内容无可贴**（无设备）。

---

## 开发工作流（每个任务）

1. **读取规则**：开工前先读 `陪聊APP项目规则.md`，确认当前进度
2. **读取计划**：读 `陪聊APP开发计划.md`，找到当前任务的详细说明
3. **实现功能**：按任务内容实现
4. **验收测试**：按任务的验收标准自测
5. **更新进度**：在本文件对应任务前打勾 `\\\[x]`
6. **记录问题**：遇到问题或偏离计划，在本文件「已知问题」章节记录
7. **提交代码**：（可选）git commit

\---

## 测试纪律（硬规则，违反等于测试假绿）

1. **禁止裸 `kotlin.assert()`，一律用 `org.junit.Assert.*`**（`assertEquals` / `assertTrue` / `assertNull` / `assertNotNull`）。
   - **`src/test`（JVM 单测）**：Gradle `Test` 任务默认 `enableAssertions = true` → `assert()` **会生效**，所以这里犯错了也看不出来。
   - **`src/androidTest`（仪器测试）**：`kotlin.assert` 编译成 `_Assertions.ENABLED`（`desiredAssertionStatus()`）判断；ART / `app_process` **默认关闭断言**，也没有 `-ea` 可加 → `assert()` **静默跳过，等于没写**。
   - 同一个 `assert` 在 JVM 能跑、搬到 androidTest 就变空操作，**不会报错、不会告警**，只会"测试绿着但什么都没验证"。
   - 已核：2026-11-20 全仓 `app/src` grep `\bassert\s*\(` **0 命中**，现有 567 条测试全部走 JUnit 断言，基线是真绿。
2. **`MigrationTestHelper` 的测试特别容易踩这条**：它只能跑在 androidTest，且 `runMigrationsAndValidate` 只自动校验 identityHash（schema 层面），**业务数据正确性全靠手写断言** —— 断言失效就等于什么都没测。
3. 新增 androidTest 后必须**真机跑一遍**（`connectedDebugAndroidTest`），`testDebugUnitTest` 覆盖不到 androidTest。
4. **「全绿」的定义＝无过滤全量 + 测试源码编译通过 + XML 里 `failures=0 / errors=0 / skipped=0`**。
   - 只跑 `--tests ...` 过滤、只贴末尾 `BUILD SUCCESSFUL`、拿上一轮的 XML 报数，**都不算绿**。
   - 反例（2026-11-20 实例）：V2.2.2 交付的 `FollowupPortImplTest.fixture()` 是非 suspend 却调 suspend 的 `dao.insert`，**整个 test 源集编译不过**，任何运行都起不来，但报数仍写「678/83 全绿」。假绿直到 V2.3.5 逻辑线顺手修掉才暴露。
   - 复核方式（审查线固定动作）：脚本遍历 `app/build/test-results/testDebugUnitTest/*.xml` 求和 `testsuite.tests / failures / errors / skipped`，并比对「最新 XML mtime vs 本轮源码最大 mtime」确认成绩晚于改动。

\---

## 已知问题与偏离

> 记录实现过程中的问题、妥协、未按计划实现的地方。

**示例格式**：

```
\\\[任务 X.Y] 问题描述
- 原计划：...
- 实际做法：...
- 原因：...
- 影响：...
```

### \[任务 0.1 遗留] 缺 Gradle Wrapper 与启动图标

* 原计划：任务 0.1 交付可编译运行的项目骨架。
* 现状：目录中没有 `gradle/wrapper/`、`gradlew`、`gradlew.bat`；`AndroidManifest.xml` 引用了 `@mipmap/ic\\\_launcher`、`@mipmap/ic\\\_launcher\\\_round`，但 `res/` 下没有 mipmap 资源。
* 影响：目前无法直接 `./gradlew assembleDebug`（缺 wrapper）；即使有 wrapper 也会因缺图标资源编译失败。
* ✅ **Gradle Wrapper**：已由 Claude 补全（gradle-8.7）。
* ✅ **启动图标**：已改用用户提供的**蓝鲸 logo**（本 AI 生成全套：5 密度方形 `ic\\\_launcher.png` + 圆形 `ic\\\_launcher\\\_round.png` + 自适应前景 `ic\\\_launcher\\\_foreground.png` + `mipmap-anydpi-v26/\\\*.xml`），替换了原先的"笑脸"占位。
* ✅ **重复资源清理**：删除了重复的 `ic\\\_launcher\\\_background` 颜色定义（原 `themes.xml #3DDC84`、`values/ic\\\_launcher\\\_colors.xml #4A90E2`），统一由 `values/colors.xml #FFFFFF` 提供；并删除未使用的 `drawable/ic\\\_launcher\\\_background.xml`、`drawable/ic\\\_launcher\\\_foreground.xml`。

### \[任务 1.1] 为 embeddding 预留列

* 原计划：任务 1.1 的表结构未包含向量列。
* 实际做法：`memory\\\_short`、`memory\\\_long` 各加了可空列 `embedding: ByteArray?`。
* 原因：任务 2.x 语义去重需要在记忆上存 384 维向量，提前预留避免后续改表迁移。
* 影响：无；该列当前不写，任务 2.x 启用。

### \[任务 1.1] 迁移策略

* 实际做法：`AppDatabase` 暂用 `fallbackToDestructiveMigration()`。
* 原因：第一版表结构仍在演进，避免每次改表都写 Migration。
* 影响：升级会清空本地库；正式发版前需改为显式 Migration（上线前必须处理）。

### \[任务 1.1] 采纳审查建议（补索引）

* 依据：Claude 审查建议（`getRecent()` 排序、任务 2.6 召回排序）。
* 改动：`messages` 增加 `Index("timestamp")`；`memory\\\_short` 增加 `Index("fadeScore")`。
* 配套：因 schema 变更，`AppDatabase` 版本 `1 -> 2`（仍用 `fallbackToDestructiveMigration`）。

### \[任务 1.2] 实现说明与偏离

* **范围**：本 AI 只做逻辑层（网络 SSE + usage 解析 + 配置存储）。"设置页输入 API Key/选模型"的**界面**归 Claude（任务 7.1/1.3），本 AI 只提供 `ConfigRepository`。
* **API Key 存储**：按计划用普通 `SharedPreferences`（`api\\\_config`）。**正式版建议改 `EncryptedSharedPreferences` 加密**（后续加固项）。
* **未用 Retrofit**：流式 SSE 直接用 OkHttp `okhttp-sse` 的 `EventSource` 更稳；Retrofit 依赖保留但不使用。
* **依赖 `stream\\\_options.include\\\_usage`**：DeepSeek/OpenAI 均支持；若自建兼容网关不支持，可能不返回 usage（状态栏显示 `??`），不影响正文流。

### \[任务 1.2] 审查后修订（2026-11-20，采纳 Claude 建议）

1. **tok/s 口径**：改为「首 token → 末 token」的生成用时（排除网络/首 token 延迟）。
2. **contextUsage 边界**：`total\\\_tokens == 0` 时返回 `null`，UI 显示 `??`（不误报 0%）。
3. **API Key 加密**：`ConfigRepository` 升级为 `EncryptedSharedPreferences`（AES256\_GCM），Keystore 异常时降级普通存储；新增依赖 `androidx.security:security-crypto:1.0.0`。
4. **重试**：按建议留到任务 1.4 在 ViewModel/Repository 层加 `retry`（本任务不加）。
5. **测试**：新增 `ChatApiClientTest`（空 Key、HTTP 500 均走 `Failed` 不崩）；新增测试依赖 `okhttp3:mockwebserver:4.12.0`。

### \[任务 1.1] 审查结果（Claude - 2026-11-20）

**✅ 通过验收，代码质量优秀。**

**审查要点：**

* ✅ 7 张表结构完全符合需求文档
* ✅ FK 级联删除正确（删会话自动删消息）
* ✅ DAO 方法完整（增删改查 + 专用查询）
* ✅ SQL 写法正确（`getRecent` 子查询、`getDue` 时间过滤）
* ✅ ByteArray 的 equals/hashCode 正确实现
* ✅ 测试覆盖 7 张表的基础读写

**🟡 建议改进（非阻塞）：**

1. `MessageEntity.timestamp` 建议加索引（`getRecent()` 排序用）
2. `MemoryShortEntity.fadeScore` 建议加索引（任务 2.6 召回排序用）

→ 不影响功能，可在后续优化时加上。

### \[真机崩溃修复] launcher 图标被 painterResource 解析导致闪退（UI 线 / 用户 2026-11-20，已归档）

* **症状**：安装后启动即 `IllegalArgumentException` 闪退。
* **根因**：`ChatScreen.kt:191` 用 `painterResource(R.mipmap.ic\\\_launcher\\\_round)`；Android 8+ 该名字解析到 `mipmap-anydpi-v26/ic\\\_launcher\\\_round.xml`（自适应图标 XML），`painterResource` 不支持自适应 XML → 抛异常。
* **修法（已落地）**：新增 `res/drawable-nodpi/ic\\\_whale.png`（圆鲸，13KB），改为 `painterResource(R.drawable.ic\\\_whale)`。
* **复核（本 AI）**：全仓已无 `painterResource(R.mipmap.\\\*)` 误用；`AndroidManifest` 仍用 `@mipmap/ic\\\_launcher(\\\_round)` 作启动图标（合法）。属**运行期**问题，编译/单测无法捕获。
* **后续建议（UI 线）**：`drawable-nodpi` 不随密度缩放，高 DPI 可能偏模糊 → 建议换 **vector drawable**（`ImageVector`/`Icons`）或放 `drawable-xxxhdpi` 位图。
* **配套依赖/环境（已确认在仓内）**：`security-crypto 1.1.0-alpha06`（含 `MasterKey`）、`material-icons-extended`（`KeyboardVoice`）；`gradle.properties`、`local.properties` 已补。`gradle.properties` 里 `android.nonTransitiveRChannel=true` 疑似笔误（应为 `nonTransitiveRClass`），当前被 Gradle 当未知属性忽略，不影响构建，建议顺手更正。

### \[审查修复 2] TTS 初始化竞态 / 设置存取链 / provider 关闸（本 AI 2026-11-20，已修，`testDebugUnitTest` 49 用例全过 + assembleDebug 绿）

1. **必修·系统 TTS 初始化竞态**：`SystemTtsEngine` 改用 `CompletableDeferred<Int>` 挂起等 `TextToSpeech.onInit`（5s 超时），就绪后才 `speak`；`stop()` 以 failure 结束（停止≠播放成功）。`isAvailable()` 仍恒 true——因为 TtsManager 把系统当**无条件兜底**调用，若返回 false 会跳过兜底；真正就绪由 speak 内部等待保证。
2. **必修·6 个开关存取链**：`SettingsForm` 补齐 `thinkingEnabled/deepSeekTtsEnabled/ttsVoice/latestReminderEnabled/autoTitleEnabled/jsonModeDefault`，`MainActivity` 读取(initial)与保存(onSave 重建 ApiConfig)双向映射；`SettingsScreen` 加 4 个 Switch + 音色下拉。避免"保存即被默认值覆盖"。
3. **建议·provider 关闸**：`latest\\\_reminder` 与 `title` 注入均加 `ProviderPresets.isDeepSeekCompatible(providerId, baseUrl)`（显式 DeepSeek 或 BaseUrl 含 deepseek.com）；`thinking` 也用同一判断，避免切 OpenAI/自定义时未知参数 400 死循环。
4. **建议·MediaPlayer**：`DeepSeekTtsEngine` 改 `prepareAsync()` + `setOnPreparedListener`，不再在主线程同步 `prepare()`。
5. **建议·上下文窗口**：`deepseek-v4-pro` 也按 1M（`defaultContextWindow` 对 `deepseek\\\*` 统一 1\_000\_000）。
6. **建议·思考内容**：`DeltaDto` 增 `reasoning\\\_content` 解析（当前不展示，避免将来开思考后静默丢失）。
7. **⚠️ title 400 兜底**：`ChatViewModel` 若首轮因 `title` 顶层参数被拒（HTTP 400），自动去掉 title 重试一次；`latest\\\_reminder` 走 DeepSeek 官方文档支持，风险低，真机再验。
8. 仍存在的已知边界：DB `version 2→3` + `fallbackToDestructiveMigration` → 升级清空本地历史（上线前改显式 Migration）。
9. **visionModel 迁移补漏**：`ConfigRepository` 读/存都对识图模型做归一——DeepSeek 强制 `deepseek-flash`（纠正历史 `deepseek-4.1-flash`/`deepseek-v4-flash-vision-exp`），其它服务商迁移旧名、空则取默认（OpenAI→gpt-4o）。避免带图时用无效模型名报错。

### \[任务 2.1] 本地 embedding 集成（本 AI 2026-11-20，已完成；`testDebugUnitTest` 82 全过 + assembleDebug 绿）

* **APK 体积**：模型 fp32 90MB 打包进 assets → debug APK 约 **157.9MB**。个人自用可接受；若嫌大，改用「首次启动下载」：不打包 assets，用 `ModelFiles.installFromFile()` + `tools/fetch\\\_embedding\\\_model.ps1` 里的镜像地址下载（或提供设置页下载按钮，属 UI 线）。
* **真机数值验收暂未自动跑**：`EmbeddingAcceptanceTest`（androidTest）会真加载模型跑阈值断言，但 adb 安装被 MIUI 拦（`INSTALL\\\_FAILED\\\_USER\\\_RESTRICTED: Install canceled by user`，需在手机上点「允许通过 USB 安装」或开「USB 调试(安全设置)」）。已用**宿主机 onnxruntime 同算法**验证阈值全部达标（同句 1.0000 / 近义 0.9491 / 无关 0.0111），故算法与模型可信；真机 ONNX Runtime Android 的加载可在允许安装的机器上补跑。
* **JVM 不可跑 ONNX**：`onnxruntime-android` 无桌面 native，故 ONNX 推理不做 JVM 单测，只测纯逻辑（分词/相似度/编解码/串行化）。
* **token\_type\_ids**：all-MiniLM 需要它；代码按 `session.inputNames` 动态决定是否喂，兼容缺该输入的导出。

### \[任务 2.2] 记忆提取与存储（本 AI 2026-11-20，已完成；101 单测全过 + assembleDebug 绿）

* **成本**：每完成一轮主对话会额外发 1 次 `callJson`（独立请求）→ 用户 Key 多花一点 token，符合设计「每轮对话后额外 API 调用」。若嫌频繁可后续加开关或隔轮提取。
* **embedding 默认可能为空**：`MemoryExtractor` 用 `EmbeddingServiceFactory.peek()`，仅当 embedding 已在别处初始化（isReady）才写向量；当前无全局预热，故通常 `embedding=null`。任务 2.3 语义去重时再 `ensure()` 并 on-demand 补算旧记忆向量。
* **去重边界**：2.2 只做「完全相同文本」跳过；「我明天要面试」vs「明天去公司面试」这类语义近义合并属任务 2.3。
* **时间锚点**：2.2 原样存提取文本；「明天」→绝对日期是任务 2.4。
* **DB 版本**：实体未改（embedding 列 1.1 已预留），`AppDatabase.version` 维持现有值，无需迁移。

### \[任务 2.3] 语义去重 + embedding 模型换成中文模型（本 AI 2026-11-20，已完成；109 单测全过 + assembleDebug 绿）

* **发现并修正模型选型问题**：任务 2.1 原选 `all-MiniLM-L6-v2`（英文模型）。实测中文——近义句「我明天要面试 vs 明天去公司面试」余弦仅 **0.70**，而与无关句「我明天要面试 vs 我对花生过敏」高达 **0.60**，近义/无关区间完全重叠，**阈值 0.85 下中文永远合并不了**，2.3 验收无法通过。
* **改用 `bge-small-zh-v1.5`**（BAAI，MIT；Xenova ONNX 导出）：BERT+WordPiece（兼容现有分词器）、512 维、**CLS 池化**。宿主机同算法实测：近义对 **0.8862**、同句 **1.0**、无关对 **0.317**，干净达标。fp32 94.8MB（与旧模型体积相当；量化版仅 24MB，后续可换）。
* **影响面**：`ModelFiles`（VERSION=2 / DIMENSIONS=512 / MAX\_TOKENS=512）、`OnnxEmbeddingService`（新增 `Pooling.CLS/MEAN`）、`EmbeddingServiceFactory`（CLS）、assets 模型与 `vocab.txt`、`tools/fetch\\\_embedding\\\_model.ps1`、`EmbeddingAcceptanceTest`（中文例句）。
* **代价**：模型换了，之前 all-MiniLM 生成的向量若已存库将失效（当前无历史数据，DB 装新包本就 destructive 清空，无影响）。BGE 模型仍建议用 CLS 池化，勿改回 MEAN（虽中文 MEAN≈0.87 也能过，但 CLS 是官方推荐）。
* **真机**：`EmbeddingAcceptanceTest` 因 MIUI 安装拦截仍未自动跑，需允许安装后补跑；算法已在宿主机 bge 同算法上验证通过。

### \[任务 2.3 审查修复] Category 归一 + Pooling 配置化（本 AI 2026-11-20，`testDebugUnitTest` 120 全过 + assembleDebug 绿）

* **问题 4（已修·必做）**：新增 `domain/memory/MemoryCategory.normalize()`，把中英文/拼写变体（`过敏`/`allergy`/`preferrence`…）归一到固定枚举 `fact|preference|allergy|relationship|other`；`MemoryDeduplicator.upsertLong` 的**比较与写入都用归一后 category**，避免同概念因 category 写法不同而漏去重/碎片化；`MemoryPromptBuilder.SYSTEM` 的 category 枚举改为取自 `MemoryCategory.ALL`（单一真源）。单测：`MemoryCategoryTest`(6) + dedup「过敏/allergy 跨语言同类合并」用例。
* **问题 1（已修）**：池化策略与模型绑定为常量 `ModelFiles.POOLING\\\_STRATEGY="CLS"`，`EmbeddingServiceFactory` 按它选 `Pooling.CLS/MEAN`；换模型只改一处、无需改代码。
* **问题 2（Backfill 批量）——生产前优化，暂不做**：当前仅对 embedding 为空的旧记忆补算并**持久化**，之后不再重算；个人数据量小可接受。上线前可加 `backfillAllEmbeddings()`（DAO 加 `WHERE embedding IS NULL` 查询）在启动后台跑一次，移出关键路径。
* **问题 3（合并策略增强）——观察后决定，暂不做**：`shouldMerge()` 的「长度差>50% 视为增量信息不合并 / 含时间词且不完全一致不合并 / 否定词处理」等，等真机观察误合并率再调，避免过早复杂化。
* 结果：全仓 **120 用例 0 失败**，`assembleDebug` 绿（157.9MB）。

### \[任务 2.4] 时间锚点转换（本 AI 2026-11-20，已完成；129 单测全过 + assembleDebug 绿）

* **范围**：只做「相对时间→绝对日期、时间点→HH:mm」，覆盖中文常见表达（相对日 / N天周 / 星期 / 绝对日期 / 时间点+上下午换算）。不做时区/夏令时特殊处理（用系统默认 LocalDate）。
* **计划验收 vs 实际**：`明天 7 点`→`2026-11-21 07:00` 完全一致；`下周三`计划写 `2026-11-27`，但以 `2026-11-20`(周五) 为参考，本周一 11-16，下周三=**11-25**，计划里的 11-27 应是作者按别的参考日笔误。逻辑以正确日期数学为准。
* **口径**：`referenceDate` 默认 `LocalDate.now()`；测试注入固定 `2026-11-20`。转换失败一律原样返回（`anchor()` 兜底），不阻断落库。
* **未覆盖**：「下下周X」「农历」「每周末/每周X 周期性」「Vague 时间（过几天/周末）」等暂不支持，后续需要再加。

### \[任务 2.4 审查修复] 时区 / 中文数字 / 下月明年 / 12h 歧义（本 AI 2026-11-20，132 单测全过 + assembleDebug 绿）

* **① 时区（中·已改进）**：`TimeAnchorConverter.convert` 默认参照日改 `LocalDate.now(ZoneId.systemDefault())`（显式）；新增 `todayIn(zone)` / `convertInZone(input, zone)` 供跨时区显式调用；`MemoryExtractor.referenceDate` 默认同用系统时区。注：当前 `UserMetaMarker` 也用系统时区，两者一致；将来支持多时区时从 reminder 的 zone 注入即可。
* **② 中文数字（低·已补）**：`parseNumber` 本就支持「三十 / 五十六」（X十 / X十Y，原审查判断有误），本次再补「百」（一百 / 两百 / 一百零五 / 一百二十）；N 天/周正则字符集加入「百」。已加测试锁定 30/56/105。
* **③ 下个月 / 明年（中·已加）**：新增 `findRelativeMonth`：下个月X日、下个月(→+1月同日)、这个月X日、明年X月Y日、明年X月(→明年该月同日)、明年(→+1年)。并修正优先级：**相对月/明年 先于「M月D日」绝对日期**，避免「明年3月5日」被误解析为今年。
* **④ 12 小时制歧义（低·prompt 缓解）**：代码无法访问 LLM 上下文，行为保持（无前缀按字面 24h）；在 `MemoryPromptBuilder.SYSTEM` 要求 LLM 提取时保留「上午/下午/晚上」限定词（如「明天晚上8点」），便于后续换算。
* **⑤ 下周三=11-25 说明**：已在 \[任务 2.4] 已知问题写明「计划里的 11-27 为笔误」；转换器内亦加注释，测试固定参考日 2026-11-20 断言 11-25。
* 结果：全仓 **132 用例 0 失败**，`assembleDebug` + `assembleDebugAndroidTest` 绿（157.9MB）。
* **遗留观察项（非阻断，保持现状）**：① 跨小月 clamp——「下个月31号」在 2 月会落到月末(28号)，月份溢出无通用解，clamp 是合理选择；② 裸「下个月」按 +1 月同日转换，若用户本意是「下月某天未定」可能影响去重，真机观察反馈再定是否改为不转换。

### \[任务 2.5] 淡忘机制（本 AI 2026-11-20，已完成；148 单测全过 + assembleDebug 绿）

* **设计取舍**：没有引入 AppState 之外的新表；用 `app\\\_state` 存 `last\\\_decay\\\_epoch\\\_day` 游标，按「实际经过天数×0.1」补齐未运行空档（比固定每天+0.1 更抗断点）。衰减只动 `fadeScore`、从不删记忆。
* **召回算分**已在 `MemoryScoring` 落地（2.6 会加「久未提及加分」）。新鲜度用半衰期 7 天指数衰减，`fadeScore` 可把老记忆压到分数<0 → 不召回；权重极高仍可召回。
* **WorkManager**：24h 周期 + 启动补跑；两者都幂等（同天游标已今天 → days=0 不动），避免重复衰减。
* **未覆盖**：衰减因子/半衰期为常量，暂不做设置项；精确「凌晨」触发靠周期近似（WorkManager 24h 非严格午夜），启动补跑兜底。

### \[任务 2.6] 记忆召回（本 AI 2026-11-20，已完成；166 单测全过 + assembleDebug 绿）

* **算分**：`权重×新鲜度 + min(久未提及天数,90)×0.05 − fadeScore`。加分上限 90 天防无限增长；从未提及(null)给满加分。
* **选择**：TOP3 交大模型 `callJson(json\\\_object)` 返回 `{"id":k}`；单条候选不调模型直接选；模型失败退回第 1 条；`id:0`=都不合适→null（上层不发）。
* **副作用**：`recall()` 选中即更新 `lastMentioned`（下次不再立刻重复同一记忆）。
* **接入**：`CompanionApp.memoryRecallService` 就绪，但**真正调用方是阶段 3 主动消息框架（3.5）**，当前无 UI 触发（属正常，逻辑先行）。
* **冷却/拉黑**（2.8）本次未做：`MemoryRecallRanker` 暂无排除参数，2.8 加字段后在此过滤。

### \[审查记录] 第一版收尾 + 第二版开工前基线（审查线 2026-11-20）

**证据**：`testDebugUnitTest` 418 用例 0 失败（60 个测试类，2026-11-20 08:36 产物）；`assembleDebug` 绿，`app-debug.apk` 141,728,603 B。第二版 V2.1–V2.4 **零代码**（`EnvironmentSensor` / `FestivalProvider` / `FollowupJudge` / `SherpaSttEngine` / `RootPermissionChecker` 全仓 0 命中）。

**🔴 必修 1：指令提醒被勿扰吞掉（产品级）**

* 位置：`domain/proactive/ProactiveGateRules.kt:91-94`（勿扰对所有类别生效）+ `data/proactive/ProactiveDeliveryRunner.kt:75-84`（勿扰期间把消息 reschedule 到区间结束，含指令提醒）
* 复现：勿扰 23:00–08:00（`DndSettings.suppressNotification` 默认 `true`）+ 用户设「早上 7 点叫我起床」→ 7:00 到点被 `DO_NOT_DISTURB` 拦，或被顺延到 08:00 才响
* 影响：闹钟不准时＝核心功能失效；第二版 V2.2 追问链同理
* 修法（择一，建议 a）：a) 勿扰只约束 `SPONTANEOUS`，`INSTRUCTION` / `FOLLOW_UP` 只在语音层静音、通知照发；b) 设置页拆两个开关「勿扰期间不响 AI 消息」/「勿扰期间不响我的提醒」，后者默认关
* 别动：`DndPolicy.inQuietHours` 的跨午夜 + end 开区间语义是对的

**🔴 必修 2：闸门类别未贯通 → V2.1.4 验收必挂**

* 位置：`data/proactive/DefaultProactiveGate.kt:50` 硬编码 `category = SPONTANEOUS`；`ProactiveDeliveryRunner.kt:67-72` 闸门豁免只判 `INSTRUCTION`
* 后果：`FOLLOW_UP` / `SPECIAL_DATE` / `ENVIRONMENT` 出队时按自发消息吃「冷却 2h + 每日上限 3 + 回复率」，被拦时走 `queue.discard()` **直接丢弃**（不是延后）
* 直接冲突：计划 V2.1.4 验收「环境钩子消息绕过冷却时间」不可能通过；节日祝福会被每日上限挤掉、当天丢失
* 修法：`PendingMessageType → ProactiveMessageCategory` 映射；闸门按类别评估（环境/特殊日期/追问豁免冷却与上限，仍受勿扰——按必修 1 的结论调整）；拦截时对 `SPECIAL_DATE` 保留到当天结束而非 discard

**⚠️ 改进 1**：`LOW_REPLY_RATE` 是死规则——`CompanionApp.kt:189` 的 `replyRateProvider = { null }`，而 v1 计划没有「回复率统计」任务，规则永不触发。要么补统计（SENT 与用户回复配对），要么删规则，别留半截。

**⚠️ 改进 2**：`WorkManager` 周期任务用 `ExistingPeriodicWorkPolicy.KEEP`——将来调 `ProactiveCheckWorker.PERIOD_HOURS` 不生效（旧请求被保留）。改周期时用版本号后缀改名或临时 `UPDATE`。

**⚠️ 改进 3**：任务 3.7（提醒已完成回执）是 v1 计划外实现，已在本表登记为偏离，需在 `陪聊APP开发计划.md` 补一节说明，否则后续对不上号。

**✅ 通过项**：3.1 队列状态机（单行 transition 认领，天然防重复发送）；优先级 5/4/3/2/1 与第二版 priority 2/3 对齐；6.1 持锁契约逐条注释；`DndPolicy` 三个消费方共用同一判据，没有三处各写一遍时间比较。

\---

## 构建与真机（本机环境，2026-11-20）

* **Android SDK**：`C:\\\\ai-deepseek\\\\android-sdk`（cmdline-tools + platform-tools 37.0.1 + `platforms;android-34` + `build-tools;34.0.0`）
* **JDK17**：`C:\\\\ai-deepseek\\\\jdk17`（Microsoft OpenJDK 17.0.21）。**必须用 JDK17**：kapt 在 JDK21 上会 `IllegalAccessError: com.sun.tools.javac.main`。
* **本 AI 补的文件**：`local.properties`（`sdk.dir=C:/ai-deepseek/android-sdk`）、`gradle.properties`（`android.useAndroidX=true` 等，缺它必挂）。
* **编译**：`JAVA\\\_HOME=C:\\\\ai-deepseek\\\\jdk17` 下 `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（debug 约 78MB，含 ONNX + extended 图标）。
* **安装/启动**：`adb -s <DEVICE_SERIAL> install -r app-debug.apk`；`am start -n com.companion.chat/com.companion.chat.ui.MainActivity`
* **设备**：`<DEVICE_SERIAL>` <DEVICE_CODENAME>（Android 13 / MIUI14）

### \[构建修复] 依赖调整（本 AI，2026-11-20）

* `androidx.security:security-crypto` `1.0.0 → 1.1.0-alpha06`：1.0.0 没有 `MasterKey` 类（只有旧的 `MasterKeys`），而 `ConfigRepository` 用的是 `MasterKey`。
* 新增 `androidx.compose.material:material-icons-extended`：`ChatScreen` 用了 `Icons.Default.KeyboardVoice`（仅 extended 图标库提供）。**未改动任何 UI 代码**。
* 结果：`assembleDebug` 成功，真机 `<DEVICE_SERIAL>` 安装、启动、1.3 空态显示正常。

\---

## 附录：DeepSeek 官方 API 能力增补与采纳决策（2026-11-20）

> 详细设计与「任务 1.2 类/方法清单」见 `陪聊APP-DeepSeek新API接入设计.md`。以下是摘要，方便开工前速览。

### 一、这次开放了什么（官方，2026-11-13 全家桶应用 API）

* **A 类：原接口开参数即用**（Chat Completions / Messages / Responses）：联网搜索 `deepseek\\\_web\\\_search`、深度思考搜索、文件输入、阅读链接 `deepseek\\\_read\\\_url`、模型安全 `safety\\\_check`、会话标题 `title`、用户元数据 `latest\\\_reminder`、思考控制 `thinking`+`reasoning\\\_effort`、`response\\\_format=json\\\_object`。
* **B 类：独立底层接口**：Search `/v1/tools/search`、Fetch `/v1/tools/fetch`、OCR、ASR `/v1/tools/asr`、TTS `/v1/tools/tts`（含 WebSocket 流式）。
* **模型**：`deepseek-flash`（V4.1-Flash-1017，**原生视觉**、1M 上下文、384K 输出）；`deepseek-v4-pro`（文本强、贵、无视觉）。旧名 `deepseek-chat/reasoner` 已下线。

### 二、采纳决策

|优先级|项|动作|
|-|-|-|
|★ 立即用|TTS 语音合成|`TtsEngine` 抽象：系统兜底 + DeepSeek 官方可选|
|★ 立即用|上下文缓存|人设+长期记忆作稳定前缀，变化内容置后|
|★ 立即用|JSON Output|mood / 记忆提取用 `json\\\_object`|
|★ 立即用|`latest\\\_reminder`|每轮 user 前注入日期/星期/地点/平台/语言|
|★ 立即用|`title`|建会话首条请求开一次，落 `conversations.title`|
|○ v1.1|联网搜索|关键词/手动触发，别默认每句搜|
|○ v1.1|视觉输入|**已实现（1.6）**，仅需修正模型名|
|○ v1.1|ASR 语音输入|以后"按住说话"用官方 `/v1/tools/asr`|
|○ v1.1|OCR|纯文字截图抽取|
|✕ 不用|`safety\\\_check`|个人自用会打断，不开|
|✕ 不用|文件输入 / FIM / Prefix / Responses / Anthropic / v4-pro|与陪伴无关|

### 三、计费要点（省钱）

* 自 2026-11-18 12:00 起，**服务端**触发的搜索/抓取不再按次计费（算 token）；客户端直连 Search/Fetch 仍 `$0.75/千次`。
* TTS `$0.45/万字符`（五折 0.225）；ASR `$0.116/小时`（五折 0.058）；OCR `$0.225/千页`。
* 上下文缓存命中价约为未命中 1/40（flash 命中 `$0.003\\\~0.006`/1M vs 未命中 `$0.15\\\~0.3`/1M）。谷时=峰时一半。

### 四、需修正的既有问题（实现期处理）

* **识图模型名**：`ProviderPresets` 现用 `deepseek-4.1-flash` 是**无效模型名**；`deepseek-flash` 已原生支持视觉，识图直接用 `deepseek-flash`（或复用主模型），见设计文档 §6。

\---

## 参考资料

* **需求原文**：`C:\\\\Users\\\\clc\\\\.deepseek-project\\\\dsh-brainstrom\\\\陪聊APP-最终方案.md`
* **开发计划**：`陪聊APP开发计划.md`
* **参考 UI**：`参考图-deepseek手机端.png`、`参考图-dsh-harness.png`
* **GitHub 项目**：见需求文档第四节

\---

## 第一版不做的功能（提醒）

以下功能留待第二版及以后，**第一版不要实现**：

1. 环境感知（时间/位置/天气）
2. 特殊日期与节日（生日、农历节日）
3. 追问型任务/监督链
4. 语音唤醒
5. 语音输入（按住说话）
6. 通知栏常驻快捷入口
7. root 自动保活脚本
8. 环境钩子

\---

**当前状态（2026-11-20 审查线核对）：第一版阶段 0–7 全部有代码落地，`testDebugUnitTest` 418 用例 0 失败、`assembleDebug` 绿（APK 141.7MB）。下一步按 `陪聊APP第二版开发计划.md` 进入「阶段 V2.1：环境感知层」→ 任务 V2.1.1「环境数据采集」。**
**⚠️ 开 V2.1.4 之前必须先修「审查记录」里的两个 🔴：指令提醒被勿扰顺延、闸门类别未贯通（环境/节日/追问会被当自发消息丢弃）。**

---

## 1.0.3（2026-11-20 下午）真机反馈修复：主动消息完全不触发

真机实测「2 小时一次的随机消息一条都没来」。取证链（adb + WorkManager workdb + companion.db）：

| 证据 | 观测值 |
|---|---|
| `WorkSpec(ProactiveCheckWorker).period_count` | 4（周期本身在正常跑） |
| `WorkSpec(ProactiveDeliveryWorker)` | 仅 1 条，`last_enqueue 07:16:29` |
| `app_state.proactive_sent_count` | `{"count":11,"day":"2026-11-20"}` |
| `app_state.last_interaction` | 09:10:35 |
| DeviceIdle 白名单 / standby bucket | 已白名单 / bucket 5 |
| `pending_messages` | 11 条全部 `sent`，其中 6 条是 `text:` 时间钩子=手动测试 |

根因两条：

1. **手动测试污染每日上限**：`ProactiveDeliveryRunner.deliver()` 无条件 `onDelivered()`，
   设置页「立即触发一次」走 `force=true` 同样 +1。验收时点了几次 → 计数 11，
   而上限 3 → 之后每轮检查都被 `DAILY_LIMIT` 拦死。
2. **上限/冷却与「每 2 小时一条」语义冲突**：周期 2h（一天最多 12 次），默认上限却只有 3；
   冷却窗口原来也是 2h，等于「检查前一小时内聊过天 → 这一轮必被吞」，连续两轮就静默 4 小时。

修复：

- `ProactiveDeliveryRunner`：`if (!force) onDelivered()`（测试不计数）+ 回归单测
  `force_delivery_does_not_count_toward_daily_limit`
- `ProactiveGateRules.COOLDOWN_MILLIS` 2h → **1h**（必须小于 2h 周期）+ 断言单测
  `cooldown_window_is_shorter_than_check_period`
- 默认每日上限 3 → **8**（`ProactiveGateRules.DEFAULT_DAILY_LIMIT` = 16h ÷ 2h），
  四处默认值同步（Context / GateConfig / ConfigRepository / SettingsScreen）
- 一次性迁移 `proactive_count_migration_v2`：升级当天清掉被污染的存量计数（否则当天仍发不出去）
- 可观测性：`ProactiveCheckWorker` / `ProactiveGate` / `ProactiveCheck` / `ProactiveDelivery`
  关键日志升 **W 级**，并附带 `sentToday=x/limit`、`sinceLastInteraction=xxmin`、`outcome=…`、`force=…`
  ——真机上 D 级日志经常一条都拿不到，之前完全无法排查

### 同时修掉的 BUG-2：闸门类别未贯通

另一轮代码审查指出（与本次真机现象**无关**，但会让第二版 V2.1.4 验收直接挂）：
`DefaultProactiveGate` 硬编码 `category = SPONTANEOUS`，`ProactiveDeliveryRunner` 豁免只判
`INSTRUCTION`，于是环境/节日/追问出队时全按自发消息吃「冷却 + 每日上限」，被拦时走
`queue.discard()` **直接丢弃而非顺延**。

真机排除了它：当日 11 条待发全是 `chitchat`/`instruction`，没有走该路径的样本。

修复：

- `ProactiveMessageCategory` 新增 `SPECIAL_DATE` / `ENVIRONMENT`，并加
  `ofPendingType(code)` 做 `PendingMessageType → 闸门类别` 映射（domain 层不反向依赖 data 层）
- `ProactiveGate` 接口改为 `allow(category: ProactiveMessageCategory)`，无参版保留为默认转发
- `ProactiveDeliveryRunner` 按消息类型决定闸门类别；**指令提醒/追问不进闸门**（勿扰由
  `DND_DEFERRED` 分支顺延，避免设了勿扰就丢提醒）；**节日/环境被拦时保留并顺延**
  （节日 → 当天 23:59、环境 → +1h），并各排一次延迟送达
- 回归单测：`force_delivery_does_not_count_toward_daily_limit`、
  `cooldown_window_is_shorter_than_check_period`

### 真机联调（已完成，开关已关）

`ProactiveDebugFlags`（`domain/proactive/ProactiveDebugFlags.kt`）是**临时调试开关**，
现为 `ENABLED=false` / `BYPASS_GATE=false`，不要随意打开（1 分钟一条 = 1440 条/天，真花钱）。

- `PeriodicWorkRequest` 硬下限 15 分钟，做不出 1 分钟，所以调试模式改用
  `ProactiveCheckWorker` 跑完自排下一轮的一次性任务链（unique name `proactive_check_fast`），
  间隔由 `INTERVAL_SECONDS` 控制
- 恢复 2h 时 `CompanionApp.scheduleProactiveCheck()` 会 `cancelUniqueWork(FAST_WORK_NAME)`，
  避免调试链残留
- 现场证据：旧进程（调试版）日志有 `调试模式：绕过闸门` + `调试链：60s 后再跑一轮`；
  新进程（恢复版）日志只有 `周期检查开始` / `周期检查结束 outcome=ENQUEUED`，
  无绕过日志 → 闸门确实回到真实规则
- 调试验证结果：13:37–13:40 每分钟一条正常送达（messages id 48–52），链路通
- `proactive_count_migration_v3`：调试链灌进去的计数同样不可信，升级后再清一次
  （现场：重置后 `proactive_sent_count = {"count":1}`）

---

## 1.0.4（2026-11-20 13:5x）修 BUG-1：勿扰会把用户自己的闹钟吞掉

审查指出的产品级缺陷，已核实成立且**当初是有意设计**（`ProactiveDeliveryRunner` 旧注释原话：
「把队列里这条（含指令提醒）的 triggerTime 推到区间结束」）。问题在于这个设计本身是错的：

- `DndSettings.suppressNotification` 默认 **true**
- `DndPolicy.blocksNotification` = 开关 && 在区间内 → `ProactiveDeliveryRunner` 对**所有类型**顺延
- 复现：勿扰 23:00–07:00，用户设「6:30 叫我吃早餐」→ 到点被顺延，7:00 才响
- **闹钟的本质是准时**，被勿扰顺延 = 核心功能失效；第二版 V2.2 追问链会同样踩

修法（采用「拆双开关 + 闹钟默认准时」）：

- `DndSettings` 新增 `suppressInstruction: Boolean = **false**`——默认不压用户提醒
- `DndPolicy` 新增 `blocksInstruction()`，与 `blocksNotification()` 分开（`inQuietHours` 不动）
- `ProactiveDeliveryRunner` DND 分支按类别分流：`INSTRUCTION` 走 `dndBlocksInstruction`，
  其余（自发/追问/节日/环境）走 `dndBlocksDelivery`
- `CompanionApp` 装配 `dndBlocksInstruction`；`ConfigRepository` 新增
  `getDndSuppressInstruction()` / key `dnd_suppress_instruction`
- 设置页在勿扰区内新增开关「区间内也压掉我自己的提醒（默认关；关着时闹钟照常准时响）」，
  并把说明文案改成「AI 消息会顺延，你自己的提醒默认仍准时」
- 回归单测：`instruction_is_not_suppressed_by_default`、
  `dnd_does_not_defer_user_instruction_by_default`、`dnd_defers_instruction_only_when_user_opts_in`

⚠️ **阶段 7 验收项 ④ 的措辞要改**：原「勿扰三种行为」里「指令提醒顺延」不再默认成立，
现在是「AI 消息顺延、用户提醒照发；打开新开关后两者都顺延」。

---

## 1.0.5（2026-11-20 13:5x）修「KEEP 卡死周期调整」+ `LOW_REPLY_RATE` 死规则定性

### ① `ExistingPeriodicWorkPolicy.KEEP` 会让改周期永远不生效 —— **必修，已修**

`enqueueUniquePeriodicWork(name, KEEP, request)` 在「同名任务已存在」时**直接忽略新请求**，
根本不比对 interval。所以只改 `ProactiveCheckWorker.PERIOD_HOURS` 而名字不变，
老任务会继续按旧周期跑 —— 改周期等于没改。

（这不是假设：1.0.3 调试时我就得靠 `cancelUniqueWork` 才能切周期，正是同一机制的现场表现。）

修法：**unique work 名带排程版本 + 周期**

- `SCHEDULE_SCHEMA = 2`、`WORK_NAME = "proactive_check_v${SCHEDULE_SCHEMA}_${PERIOD_HOURS}h"`
  → 现为 `proactive_check_v2_2h`
- 新增 `LEGACY_WORK_NAMES` 黑名单（`proactive_check_2h`、`proactive_check_v1_2h`），
  `CompanionApp.scheduleProactiveCheck()` 启动时逐个 `cancelUniqueWork`，避免新旧两个周期任务同时跑
- **契约**：改 `PERIOD_HOURS` 或任何约束（网络/电量）时，必须 `SCHEDULE_SCHEMA + 1`，
  并把上一个名字追加进黑名单
- 单测 `ProactiveCheckWorkerScheduleTest`：断言名字含版本+周期、当前名不在黑名单、
  黑名单覆盖旧名、fast 名与生产名不同

现场验证（1.0.5 装机后）：

```
proactive_check_2h        CANCELLED   interval=7200000
proactive_check_v2_2h     ENQUEUED    interval=7200000  period=1  last=13:55:15
W/ProactiveCheck: 清理历史排程 proactive_check_2h
W/ProactiveCheck: 清理历史排程 proactive_check_v1_2h
```

### ② `LOW_REPLY_RATE` 是死规则 —— **不补统计、不删规则，明确标注**

`replyRateProvider = { null }` 恒 null，规则第 5 步永远不会触发。

判断：**保留规则、暂不接线**，理由是三条都有代价：

- 删规则：等于放弃「发了没人理还一直发」这个真实体验防线，与计划意图相悖
- 补统计：需要「SENT 的主动消息在 N 小时内是否有用户回复」的配对，属新功能，
  塞进 1.0.x 补丁会把包体稳定性和回归面一起放大
- 接假值：**最危险**——接成恒 0 会判定「回复率过低」，把所有自发消息压到一天一次，
  比不触发更糟

所以：`CompanionApp` 处留显式 `TODO(V2.3 回复率统计)` + 说明「为何刻意不接假值」；
`app_state[KEY_REPLY_STATS]` 这个 key 本来就预留了，接线时直接往里写。
**第二版任务清单里必须包含这一项**，否则它会一直挂着。

---

## 🔴 数据保护铁律（2026-11-20 事故后新增，必须遵守）

### 事故

用户真实数据（会话 63 条消息、全部记忆、**API Key**）在 18:34 前后被**清空且无法恢复**。

**根因**：`connectedAndroidTest`（instrumented 测试）跑完，AGP **自动卸载被测 App**，
卸载会删除 `/data/data/com.companion.chat` 整个目录 —— 数据库和所有 `shared_prefs` 一起没了。
证据：`app/build/outputs/androidTest-results/connected/` 有 18:34:34 在 `<DEVICE_MODEL> - 13` 的测试结果；
事故后 `messages=0`、`memory_*=0`、`api_config_enc.xml` 只剩两个 keyset（无任何配置项）。

**不是** Room 破坏性迁移：`AppDatabase` 已无 `fallbackToDestructiveMigration()`，
只剩显式 `MIGRATION_6_7`，迁移失败会崩溃而非静默清库。

### 铁律

1. **禁止直接执行 `gradlew connectedDebugAndroidTest`**（或 `connectedCheck`）。
   必须用 `.\tools\deploy.ps1 -RunTests`（可配 `-TestClass`），它会在测试前自动备份、
   测试后重装并恢复数据。
2. 备份/恢复工具：`tools\app-data-backup.ps1`
   - `-Backup` → `.backups\<yyyy-MM-dd_HHmmss>\`
   - `-Restore -From <目录名>`
   - `-List`
   覆盖 `databases` / `shared_prefs` / `files` / `no_backup`（WorkManager db 在 `no_backup`）。
3. `gradle.properties` 里的 `android.injected.androidTest.leaveApksInstalledAfterRun=true`
   **实测对 AGP 8.1.0 无效**（测试结束仍卸载），**不可作为防线依赖**，只留作注释参考。
4. 恢复后必须 `am force-stop` + 重启 App（脚本已做），否则 Room/SharedPreferences 句柄仍指向旧文件。

### 验证记录（2026-11-20 19:01）

- 备份：9 文件，含 `databases\companion.db`、`shared_prefs\api_config_enc.xml` ✅
- 卸载 → 重装 → `-Restore`：`app_state` 恢复为备份时的 4 条完全一致
  （`proactive_count_migration_v3` / `env_check_scheduled` / `app_launch_count` /
  `last_decay_epoch_day=20777`）✅
- `deploy.ps1` 的任务名已修为 `app:connectedDebugAndroidTest`（不带模块前缀会报任务不存在）✅

### 顺带记录的两个脚本坑

- **PowerShell 函数参数不能叫 `$args`** —— 那是自动变量，会覆盖函数参数，
  表现为「命令执行了但拿不到任何输出」（备份脚本曾因此 0 文件）。
- `adb` 会往 stderr 打 `Installed as ...`，合并 `2>&1` 后会混进文件列表被当成路径。

---

## 1.1.1（2026-11-20 17:1x）V2.1.8 真机三条反馈的根因与修复

用户实测报三条：「她还是看不到天气」「发消息有延迟」「我发的是文字她认为是图片」。逐条取证后的结论：

### ① 「看不到天气」——1.1.0 的 6 段注记被服务端拒绝（HTTP 400）

真机数据：`app_state.manual_city_name=佛山市`；天气缓存**成功拉到**（`12~18°C 降水概率98%`，
对应 Open-Meteo `2026-11-20` 的 18.0/11.9/98%）。

`messages` 表时间线是决定性证据：

| id | 时间 | reminder（注记） | 结果 |
|---|---|---|---|
| 57 / 59 / 61 | 16:24–16:37 | `2026-11-20,星期五,,App,中文`（**5 段**，装机前旧包） | 正常回复 |
| 63 | 16:51:35 | `2026-11-20,星期五,,App,中文,12~18°C 降水概率98%`（**6 段**，新包） | **失败**（截图红条「重试」，无 assistant 行） |

唯一带 6 段的请求恰好失败、此前 5 段全部成功 → **`latest_reminder` 字段结构固定为 5 段，
多一段被服务端拒绝**。修法：天气**并入第 3 段**（location 是自由文本）：
`2026-11-20,星期五,佛山市 12~18°C 降水概率98%,App,中文`，段数恒为 5。
`UserMetaMarker` 类注释已写明这条教训，禁止再加第 6 段。

### ② 「发消息有延迟」——发送路径在拉天气

`weather_cache_at` 与 id=63 的 `timestamp` **只差 2ms**，证明整条发送被那次天气 HTTP 挡住
（天气客户端 readTimeout 20s，网络差时可感知数秒）。修法：新增 `WeatherCache.peek()`
（**只读缓存、绝不联网**），发送路径只用它；聊天页 `init` 里 `prefetchWeather()` 后台预热，
整点环境检查也照旧填充缓存。发送路径现在零网络等待。

### ③ 「认为是图片」——查无此事，实为生成失败

`messages` 全表 assistant 消息里 **0 条**含「图/图片」；`toUi()` 只在
`content.isBlank() && role==USER && paths.isEmpty()` 时才显示 `[图片]`，而 id=63 是正常文本。
用户看到的红条是 ① 的 400 失败。**不是被当成图片。**

### ④ 顺带修掉的可观测性缺陷（本次卡住排查 20 分钟的元凶）

- `ChatApiClient.onFailure` 此前**不写任何日志** → 400/500 在 logcat 里完全查不到。
  现改为写 `Log.w(TAG, "流式请求失败: ...")`。
- ⚠️ **但这个日志差点酿成测试死锁**：`onFailure` 跑在 OkHttp 线程，
  `android.util.Log` 在 JVM 单测里是未实现 stub（抛 "not mocked"）；异常若发生在
  `trySend(Failed)` / `close()` **之前**，`callbackFlow` 永不结束 → 单元测试 `runBlocking` 永久挂死
  （现场：`ChatApiClientTest.http_error_emits_failed` 卡在 `Builders.kt:88`，CPU 不动、无任何输出）。
  修法双保险：日志放在 `trySend`+`close` **之后**并 `runCatching` 包住；
  `build.gradle` 加 `testOptions { unitTests.returnDefaultValues = true }`。
- **纪律**：`callbackFlow` 的回调里，任何可能抛异常的语句都必须放在 `trySend`/`close` 之后并兜住，
  否则真机表现为「一直转」、单测表现为「挂死且无栈无输出」。

### 测试与版本

- `WeatherCacheTest` +4（`peek` 新鲜命中/无缓存/过期不重拉/畸形 JSON），
  `UserMetaMarkerTest` 改为验证「天气并入第 3 段、段数恒 5」。
- 全量 **582 用例 0 失败**（75 suite），1.1.1（versionCode 21）已装机 17:17:50。

---

## 1.1.0（2026-11-20 16:4x）V2.1.8：对话天气可见

用户问 Ai「能不能看到我这边的天气」，Ai 答看不到——**属实**：V2.1 只做了「天气→主动提醒」，
`latest_reminder` 时间注记只有 5 段（日期/星期/地点/平台/语言），没有天气；`CAPABILITIES`
也没声明天气能力，模型只会老实说不知道。这不是 bug，是计划里没有的合理增量，已立项 V2.1.8 并当场做完。

改动（纯函数先行）：

- **新增 `domain/environment/WeatherSummary.kt`**（纯函数，无 Android 依赖）：
  快照 → 中文短语 `18~25°C 降水概率10%`（缺字段逐级降级：双温 > 单温 > 仅降水 > null）。
  与 `EnvironmentHooks.genericText` 分工明确：主动消息继续走泛化措辞（B7-a 不拼数值），
  对话注记必须带真实数字（否则模型答不了「今天热不热」）。**不编造**：Open-Meteo 该请求
  没取天气状况字段（晴/云），没有就是没有，不猜「晴」。
- **`UserMetaMarker.build()`** 新增可选第 6 段 `weather`：有 → 追加；空/缺 → 维持 5 段旧行为。
  文档注明「段数不是恒定 5 段」，旧测试 `empty_location_keeps_five_fields` 等语义不变。
- **`ContextBuilder.CAPABILITIES`** 新增第 4 条「天气」能力声明：明确注记里的天气是真实数据、
  可以回答「今天热不热/会不会下雨/要带伞吗」；**注记没有天气段 = 这会儿没拿到（定位/网络），
  就说不知道，不要编造**。能力清单从 5 条扩到 6 条（追问提醒顺延为第 6 条）。
- **`ChatViewModel`**：`handleSend` 拼 reminder 时调 `weatherLine()` —— 复用 `LocationCache.current()`
  （手动城市优先，其次自动定位）+ `WeatherCache.load()`（24h TTL，过期自动重拉），
  结果进 `WeatherSummary.line(bundle.today)`；全程 `runCatching`，天气失败绝不影响主对话。

测试：`WeatherSummaryTest` 新增 7 条（双温+降水 / 缺降水 / 仅最高 / 仅最低 / 仅降水 / 全缺 null /
null 快照）；`UserMetaMarkerTest` 新增 3 条（追加第 6 段 / 空天气保持 5 段 / 天气段逗号净化）。

版本：1.0.6 → **1.1.0**（versionCode 19 → 20），已装机（lastUpdateTime 2026-11-20 16:39:23）。


### 现象

跑基线 `testDebugUnitTest` 时发现**主代码根本编译不过**（不是测试红，是 `compileDebugKotlin` 红）：

```
e: MoodService.kt:59:17 Cannot find a parameter with this name: createdAt
e: MoodService.kt:59:17 No value passed for parameter 'timestamp'
e: MoodService.kt:65:17 Cannot find a parameter with this name: createdAt
e: MoodService.kt:65:17 No value passed for parameter 'timestamp'
```

之后 `compileDebugUnitTestKotlin` 也红：

```
e: MoodServiceTest.kt:30:40 Cannot find a parameter with this name: callModel
e: MoodServiceTest.kt:30:40 No value passed for parameter 'callJson'
```

**这意味着在此之前任何 V2 工作都无法开工**——构建链断了，只是没人跑过基线。

### 根因（三处，同一条线改的）

1. `MoodService` 构造函数被改成
   `class MoodService(appStateDao, callJson: suspend (ApiConfig, List<MessageEntity>) -> Result<String>, everyTurns)`
   ——不再是 3 参 `callModel: (cfg, system, user) -> Result<String>`
2. 同步新增了 `data/mood/MoodServiceFactory`（把 `List<MessageEntity>` 适配成 `List<ChatMessageDto>`），
   但 **`CompanionApp` 的 `by lazy` 装配没改**，还在传旧的 `callModel` 三参 lambda
3. `MoodService` 内部构造 `MessageEntity` 时用了 **`createdAt`**，
   而 `MessageEntity` 的真实字段名是 **`timestamp`**（没有 `createdAt`）

### 修法（本 AI，2026-11-20）

| 文件 | 改动 |
|---|---|
| `data/mood/MoodService.kt` | `MessageEntity(... createdAt = ...)` → `timestamp = ...`（2 处） |
| `CompanionApp.kt` | `MoodService(appStateDao=…, callModel={…})` → `MoodServiceFactory.create(appStateDao = database.appStateDao(), chatClient = chatApiClient)`；补 `import MoodServiceFactory` |
| `test/.../MoodServiceTest.kt` | `callModel = { _, _, _ -> result }` → `callJson = { _, _ -> result }` |

### 验证

```
testDebugUnitTest + assembleDebug → BUILD SUCCESSFUL
测试合计 431 / 失败 0 / 错误 0
APK 135.2 MB
adb install -r -t → Success（设备 <DEVICE_SERIAL> / <DEVICE_CODENAME>）
```

> 注意：**431 才是当前真实用例数**。文档里写的 418 是这条线改坏前的旧快照；
> 差异来自那次并行改写期间其他任务补的测试，不是本次修复新增。

### 契约（新增，必须遵守）

1. **`data/mood/` 归逻辑线**，改 `MoodService` 构造签名必须**同步**改三处：
   `MoodService.kt` 自身、`CompanionApp` 装配、`MoodServiceTest`。
   只改一处 = 构建链断裂，而且 `testDebugUnitTest` 会先报"测试红"掩盖真实原因。
2. **`MessageEntity` 的时间字段叫 `timestamp`**，没有 `createdAt`。
   写 `MessageEntity(...)` 时用 `timestamp = System.currentTimeMillis()`。
3. **动工前先跑一次 `compileDebugKotlin`**。测试任务会连带编译主代码，
   但报错信息指向"测试"，容易误判——**先看 `e:` 的行号落在 main 还是 test**。
4. **`MoodServiceFactory` 是 `callJson` 适配的官方入口**，不要再在 `CompanionApp` 里手写 lambda 适配器。

### 顺带核实：农历库与真实节日日期（解除计划书 B2 类风险）

下载 `cn.6tail:lunar:1.7.8`（221,848 字节，Maven Central）后用 JDK17 单文件源码直跑，
生成 2026–2036 全部六个农历节日，实测结果：

| 节日 | 2026 | 2027 | 2028 |
|---|---|---|---|
| 春节（正月初一） | **2026-02-17** | 2027-02-06 | 2028-01-26 |
| 元宵（正月十五） | 2026-03-03 | 2027-02-20 | 2028-02-09 |
| 端午（五月初五） | 2026-06-19 | 2027-06-09 | 2028-05-28 |
| 七夕（七月初七） | 2026-08-19 | 2027-08-08 | 2028-08-26 |
| 中秋（八月十五） | 2026-09-25 | 2027-09-15 | 2028-10-03 |
| 重阳（九月初九） | 2026-10-18 | 2027-10-08 | 2028-10-26 |

→ 计划书 v3.1 里「2026-02-17 → 春节」的验收日期**正确**；
→ 生成脚本可直接用 `Lunar.fromYmd(year, month, day).solar.toYmd()`，无需再猜；
→ **不要手写这 66 个日期**（v2 手写版本整体差一年）。

### 解除的卡点

- **B1（设备装不上）**：已在本机关闭 MIUI 的 USB 安装限制，
  `adb install -r -t app-debug.apk` 返回 `Success`。**真机验收从此可做**，
  `INSTALL_FAILED_USER_RESTRICTED` 不再是阻塞项。（注意：拔线后 `adb devices` 为空属正常，不代表 B1 回退；真机验收时需插 USB。）
- 计划书 v3.1 附录 B 里 B2（模型名/体积）、B3（AAR 内 `.so`）、B4（Sherpa 2.x 类名）
  仍待 V2.3.1 开工前核。
- **B7（环境钩子数值传不到文案层）已拍板 = a**（数值只作触发条件、文案泛化），
  落定写法见计划书 §3.4 末两条 + V2.1.2 验收「stableId 不含数值」回归断言。

---

## 阶段 V2.3：语音输入（STT，云端 ASR）+ 通知栏快捷入口

> **测试基线：603 全绿**（`failures=0 errors=0`，77 suite；2026-11-20 18:0x 复测）。
> 计划依据：`陪聊APP第二版开发计划.md` §V2.3（v3.4 云端 ASR 版，B2–B4 已作废）。

| 任务 | 状态 | 证据（单测 / 文件） |
|---|---|---|
| V2.3.1 ASR 端口 + PCM/WAV 工具 | ✅（审查线 17:5x，明细见「当前进度」同名小节） | `AsrPort` / `AsrResult` / `PcmUtils`；`PcmUtilsTest`(11) |
| V2.3.2 音频录制 | ✅ 已交付（审查意见全部闭环） | `WavHeader`(4) + `WavHeaderTest`(6 条音量/入参，合计 10) + `AudioRecorder`；manifest `RECORD_AUDIO`；契约已写进计划书 §V2.3.2/§V2.3.4 |
| V2.3.3 DeepSeek ASR 实现 | ✅ 已交付（初审 2🔴 修复后复核通过，`DeepSeekAsrClientTest` 28） | — |
| V2.3.4 语音输入 UI（UI 线） | ✅ 已交付（`VoiceBarLevelTest` 5；真机 8 条待用户） | **契约已定案**：`onAutoStop` 回调 + 空数组 Toast + IO 线程 stop + 波形 sqrt/log 拉伸（计划书 §V2.3.4） |
| V2.3.5 `stt_enabled` 设置 | ✅ 已交付（数据层 + UI 双线合拢） | `ConfigRepository` L125-127（key `stt_enabled`，默认 true）；`CompanionApp:148` `{ configRepository.getSttEnabled() }` 每轮现读；UI 见上方同名表格行 |
| V2.3.6 通知栏快捷入口 | ✅ **代码通过**（原 1 项 🔴 已闭环，另补 `NEW_TASK` 加固；留 1 项 ⚠️ `onPause` 语义） | `service/QuickAccess{Spec,Entrance,Service}` + `ui/speech/VoiceInputActivity`；真机 6 条未跑 |

### V2.3.2 审查闭环（初审 2026-11-20 17:5x，修复复测 18:0x，603 全绿）

**✅ 通过**

* `WavHeader.buildWavHeader`：44 字节 RIFF/WAVE 头字段全对（ChunkSize=36+pcm、fmt 16、PCM=1、byteRate、blockAlign、data），小端写法正确；补入参校验（pcmLength≥0 / sampleRate>0 / channels>0 / bitsPerSample 为 8 的正倍数）
* `AudioRecorder`：16k / mono / PCM16，**音源改 `VOICE_RECOGNITION`**（避开 AGC/降噪）；与 ASR 要求天然对齐（32 KB/s，120s ≈ 3.84 MB < 8 MiB）；`bufSize = maxOf(minBuf, READ_FRAMES*2)` 正确；`state != INITIALIZED` 与 `recordingState` 双检
* 失败降级：未 start / 初始化失败 / `getMinBufferSize` 返回负值 / 读取错误码 / 任意 `Throwable` / **空录音** → `stop()` 一律返回 `ByteArray(0)`，不向外抛
* 资源释放：`readLoop` 的 `finally` 里 `safeStop + safeRelease`，全路径不泄漏 `AudioRecord`
* 并发模型：每次录制独立 Session + `volatile` 标志 + `Thread.join` + `synchronized(pcm)`（锁对象统一是 `s.pcm`），stop() 超时滞留 worker 只写旧 Session，不会污染下一次 start() / 抢麦；无协程依赖，纯函数已抽出可 JVM 测
* manifest 新增 `RECORD_AUDIO` + 中文注释，未碰 `ui/`、`worker/`、`build.gradle`，无新增依赖——与提交声明一致
* 实测：`compileDebugKotlin` / `testDebugUnitTest` BUILD SUCCESSFUL；**审查线 18:1x 复跑 = 614 / 0 / 0，78 suite**（其中 `WavHeaderTest` 10、`PcmUtilsTest` 11；+11 来自并行线 `BootRestorePlannerTest`(8) 与 `ReminderServiceTest`(4→7)，与本任务无关）

**🔴 必修（初审 2 项）—— 已全部闭环**

1. ✅ **音量换算零单测** → `WavHeaderTest` 补 6 条：全零→0、±满幅→100、半幅→50、奇数长度（末字节丢弃）、length=0→0、offset≠0 取值正确。同时把映射从 `toInt()` 改 `roundToInt()`，满幅 32767 现在正好得 100（原来得 99）。
2. ✅ **空录音返回 44 字节空 WAV** → `stop()` 改为 `if (s.failed) return ByteArray(0)` 且 `if (pcmBytes.isEmpty()) return ByteArray(0)`；未 start 也返回空数组。

**🔴 契约（初审第 3 项）—— 已拍板 (a) 并写进计划书**

3. ✅ **120s 自动停回调**：实现 `var onAutoStop: (() -> Unit)? = null`，在 `readLoop` 置 `autoStopped` 后于录制线程触发（回调异常被吞，不影响收尾）；`isAutoStopped` 供事后判因。**已写入计划书 §V2.3.2「已定案契约」+ §V2.3.4「录音结束路径」**，UI 线按此实现，不采用轮询。

**⚠️ 改进项 —— 处理结果**

4. ⚖️ **两套 WAV 头并存**：保留 `WavHeader`（原始交付契约要求独立文件且 `AudioRecorder` 直接调它），未改队友负责的 V2.3.1 `PcmUtils` 文件；已在两处 KDoc + 计划书 §V2.3.2 写明「录音活路径 = `WavHeader.buildWavHeader`，`PcmUtils.pcmToWav` 属 ASR 侧」，杜绝改错。
5. ✅ **`bitsPerSample` 死参数**：删掉 8bit 噪音用例，改为「入参校验」用例；KDoc 明确本项目录音路径固定 16，签名保留只为头部构造通用性。
6. ✅ **`AudioSource.MIC`** → 改 `MediaRecorder.AudioSource.VOICE_RECOGNITION`，计划书 §V2.3.2 已补。
7. ✅ **音量线性贴底**：`volumeLevel` 保持线性（与「半幅→50」验收一致），在 §V2.3.2 / §V2.3.4 明确要求 UI 做 `sqrt/log` 拉伸，KDoc 同注。
8. ✅ **`stop()` 阻塞 + 5s 超时竞态**：注释改正（阻塞读 ≈128 ms，非「立即返回」）；KDoc 明确「IO 线程调用」；每次录制独立 Session，滞留 worker 只写旧缓冲，杜绝两次 `start()` 抢麦；`start()` 在 worker 仍存活时不重复建录音器。
9. ✅ **`maxDurationMs` 无上限**：`init { require(maxDurationMs in 1..120_000) }`（另校验 sampleRate>0）。
10. ✅ **manifest 缺注释**：已加「语音输入（任务 V2.3.2）：长按录音…」中文注释。
11. ✅ **`buildWavHeader` 无入参校验**：已加四条 `require`，并有单测覆盖。

**⚠️ 交给 UI 线（V2.3.4）的硬要求 —— 已写进计划书 §V2.3.4**

* 挂 `onAutoStop` 回调，到 120 秒切 IO 线程 `stop()`，按 `isAutoStopped` 提示「录音已满 2 分钟」
* 用户松手同样在 IO 线程 `stop()`；空数组必须弹 Toast 并**不**发消息（录音器不碰 Context，Toast 归 UI）
* 波形用 `volumeLevel()` 做 `sqrt/log` 拉伸
* 失败提示优先用 `AsrResult.Failure.reason` 原文（计划书 §V2.3.4 已禁「统一写死识别失败」）

**⚠️ 复测新增（18:0x 复核发现，不影响 ✅，但必须写进 V2.3.4 契约）**

1. **在 `onAutoStop` 回调里同步调 `stop()` 会 join 自己**：回调在录制线程触发，而 `stop()` 内部是 `s.worker?.join(5_000)`；对自身 join = 等自己退出，会**卡满 5 秒**才返回（数据不丢，pcm 已写完，但 UI 僵住 5 s）。计划书 §V2.3.4 已写「切到 IO 线程调 `stop()`」，需再补一句**「禁止在回调内直接调用 `stop()`」**，`AudioRecorder` KDoc 同注。
2. **`stop()` 不是幂等**：第一次调用取走并 `reset` 了 pcm，第二次返回 `ByteArray(0)`。自动停后 UI 若先因回调调一次、又因松手事件调一次，第二次为空 → 用第二次结果判断会误报「空录音」。§V2.3.4 要写「只取第一次返回值，回调与松手两条路径用一次性标记去重」。
3. **`start()` 在 worker 仍存活时静默 `return`**：极端情况（`stop()` 5 s 超时后立刻重录）UI 以为在录、实际没录，松手得空数组。彻底修法是 `start(): Boolean`；当前概率极低，先记为已知限制。

**ℹ️ 次要**：`AudioRecord(5 参构造)` 在 API 31+ 已 deprecated（官方替代为 `AudioRecord.Builder`），targetSdk34 下仅报 deprecation 警告，不阻塞、也不影响功能；将来重构时可顺带换成 Builder 并加 `setPrivacySensitive`。

**结论**：编译绿，**审查线 18:1x 复跑 614 / 0 / 0（78 suite）全绿**，初审 2 项必修 + 1 项契约 + 8 项改进全部闭环，**V2.3.2 转 ✅**。

**遗留缺陷修复（逻辑线 2026-11-20 18:4x）**：`readLoop` 到点后的残余读会多录 1~2 批（939 × 4096 = 3,846,144 B → ASR 按公式算出 120,192 ms），被 `DeepSeekAsrClient` 的 120,000 ms 闸门拦掉，两分钟录音整段丢弃。已在 `AudioRecorder.stop()` 拼头前插入 `truncatePcmToMaxDuration(pcm, sampleRate, maxDurationMs)`（同文件顶层 `internal fun`，与 `rmsToVolumeLevel` 同风格、零 Android 依赖），截断到 3,840,000 B = 120,000 ms；**闸门阈值未改、UI 未动**。`WavHeaderTest` 增 6 条（含 939 批复现、偶数样本边界、极小上限、入参校验），全量复跑全绿。

**审查线独立复核（2026-11-20 18:1x，第二遍读码）**

* ✅ 上一轮 11 项改进逐条落地属实：`Session` 内部类隔离、`VOICE_RECOGNITION`、`onAutoStop`、空录音返回空数组、`init` 的 `maxDurationMs in 1..120_000`、`buildWavHeader` 四条 `require`、`WavHeaderTest` 4 头 + 6 音量
* ✅ `rmsToVolumeLevel` 的 `roundToInt` 修正确认：满幅 32767 → 32767/32768*100 = 99.997 → roundToInt = **100**（原 `toInt` 会截断成 99，已修）
* ⚠️ **新增可加固点（不阻塞）**：`rmsToVolumeLevel` 未校验 `offset + length <= pcm.size`，内部调用点 `(buf, 0, n)` 恒安全；函数是 `internal`，将来外部复用需补 `require` 或 `coerce`。
* ⚠️ **`s.pcm` 锁对象一致**：`stop()` 与 `readLoop` 都用 `synchronized(s.pcm)`，未发现混锁——与线上版本一致，**无线程安全问题**。
* 说明：本轮 614 / 78 与上一轮 603 / 77 的 +11 用例已定位到并行线 `BootRestorePlannerTest`(8) + `ReminderServiceTest`(4→7)，**V2.3.2 自身仍是 10 条**，不存在虚报。

---

### V2.3.3 审查报告（审查线 2026-11-20 18:2x，647 全绿，**2 项 🔴 必修**）

**审查方法**：读 4 个新文件 + `CompanionApp` 装配 → 对照计划书 §V2.3.3 验收 → 重跑全量（647/81/0/0/0）→ 写临时探针实测两个可疑点（跑完删除，工作区已恢复干净，`TempProbeTest.xml` 不存在）。

#### ✅ 通过

* **协议逐字对齐**：`POST {baseUrl}/v1/tools/asr`、`Bearer`、`multipart` 的 `name="file"` + `filename="speech.wav"` + `audio/wav`、取 `text`/`duration_ms` —— 计划书 L1033–1040 全部命中，且 22 条 MockWebServer 用例逐项断言（URL 结尾、Bearer、multipart 三要素）
* **错误映射齐全**：400/408/413/429/503/504 六条文案与计划书 L1043–1050 完全一致；额外兜 `in 500..599` →「服务正忙」，比计划书更稳
* **本地前置拦截 4 类**（空数组 / 8 MiB / 120 s / 非 16 bit / 坏头 / 无 key）全部 `requestCount == 0`，省钱省时间达成；时长用**头声明的 dataSize** 而非实到字节（注释写明理由：极小字节数组可声明超长时长），正确
* **双声道去交错**（V2.3.1 遗留 #1）：`deinterleaveLeft` 按 4 字节/帧抽 L 声道，用 `WavHeader.buildWavHeader` 重建头，`byteRate=32000`、`blockAlign=2`、`dataSize` 自动重算；测试逐样本断言 `1000+i`；无法整帧 → `Failure`，**不直传原始双声道**
* **bitsPerSample != 16 拦截**（遗留 #2）：偏移 34–35 读取，8/24 位不进上传路径
* **8 MiB + 120 s 双拦截**（遗留 #3）：两个独立检查，各有独立用例断言 `requestCount == 0`
* **`SttManager`**：19 行，`enabled` 是 lambda、每轮现调、关闭时不构造请求；`SttManagerTest` 3 条覆盖「关闭不调 asr」「同实例 false→true 生效」「结果原样透传」
* **容器装配**：`asrHttpClient` 短超时 10/30/60/90 s、`deepSeekAsrClient` 复用 `configRepository.getApiKey()`、`sttManager` 起初用 `{ true }` + `TODO(V2.3.5)`（当时不自造 `stt_enabled`，遵守指示）；**V2.3.5 已补齐**为 `{ configRepository.getSttEnabled() }`，lambda 每轮现读、不缓存
* **无异常外抛（常规路径）**：`IOException`/`SocketTimeoutException`/`Exception` 三层 catch，JSON 畸形 → `Failure`，测试覆盖
* **未越界**：未碰 `ui/` `worker/` `build.gradle` `AndroidManifest.xml` `PcmUtils` `AudioRecorder` `WavHeader`，无新增依赖

#### 🔴 必修（2 项，均已写探针实测复现）

**1. `parseWavInfo`/`deinterleaveLeft` 硬编码 44 字节头 —— 与 V2.3.1 的契约直接冲突，会静默上传畸形数据**

同包 `PcmUtils.stripWavHeader`（V2.3.1）的 KDoc 明确写着「**不硬编码 44 字节**，手机录音常在 fmt 与 data 之间插入 LIST、fact 等块」，而 V2.3.3 的 `parseWavInfo` 从固定偏移 22/24/34/40 读、`deinterleaveLeft` 从 `44 + i*4` 抽帧。

**探针实测（带 LIST chunk、fmt 后插入 9 字节 LIST + pad、data 3200 字节，总 3262）**：

```
PROBE2: wav=3262 result=Success(text=x, durationMs=1) threw=null requests=1
        parsedChannels=1 parsedRate=16000 parsedDataSize=9
```

即：**没有报错、没有拦截、直接发了一次请求**，而 `parsedDataSize` 读到的 `9` 是 LIST 的填充区，真实 data 是 3200 —— 时长算成 ≈0 ms，**120 秒上限完全失效**，畸形 WAV 原样上传云端。

- **影响**：当前活路径（`AudioRecorder` → `WavHeader.buildWavHeader` 产出严格 44 字节头）**不触发**；但 `AsrPort` 契约接受任意 WAV，一旦接入「从文件选音频 / 第三方录音源 / 微信语音导出」立刻踩，且是**静默损坏**而非报错。
- **修法（二选一，都很小）**：
  - (a) 复用同包 `PcmUtils.stripWavHeader` 的 chunk 扫描逻辑，先定位 `data` 块的真实偏移与长度，再解析 fmt 字段；
  - (b) 把 `stripWavHeader` 的扫描抽成 `dataChunkRange(bytes): IntRange?` 供两处共用，避免第三份 RIFF 解析。
- **附带**：`AsrPort` KDoc 写「16 位 PCM WAV（**或 Ogg Opus**）」，但实现只认 RIFF/WAVE，Ogg 会直接落到「录音格式有问题」。要么改 KDoc 去掉 Ogg，要么在 `parseWavInfo` 前显式判断并给专属文案。

**2. 双声道 + 头部 `sampleRate > Int.MAX_VALUE` → `transcribe` 抛出 `IllegalArgumentException`，违反「任何异常都不向调用方抛出」**

`parseWavInfo` 用 Long 校验 `sampleRate <= 0`（`0xFFFFFFFF` 能过），随后 `deinterleaveLeft` 调 `WavHeader.buildWavHeader(sampleRate = info.sampleRate.toInt())` → `toInt()` 变 `-1` → `WavHeader` 的 `require(sampleRate > 0)` 抛异常，而该调用**不在任何 try 内**。

**探针实测**：

```
PROBE1_VIOLATION: transcribe 抛出 java.lang.IllegalArgumentException: 采样率必须为正，实际 -1
```

- **影响**：畸形双声道 WAV（52 字节即可构造）会让 `SttManager.transcribe` 把异常抛到 UI 协程 → 崩溃或未捕获异常。正常录音不触发，但契约是硬要求（计划书「任何异常都不许抛出，一律转 Failure」）。
- **修法（任选，2 行）**：`parseWavInfo` 加 `if (sampleRate > Int.MAX_VALUE) return null`；或 `transcribe` 里 `runCatching { deinterleaveLeft(...) }.getOrNull()`；或 `deinterleaveLeft` 的参数改用 `info.sampleRate.toIntOrNull` 式校验后再建头。

#### ⚠️ 需改进（不阻塞验收）

3. **401/403 文案误导**：`httpFailure` 的 `else -> BAD_RESPONSE_REASON`（"识别服务异常，请稍后重试"）会接住 401/403 —— 实际是 **API Key 无效或权限不足**，用户按"服务异常"去重试永远好不了。建议加 `401, 403 -> "API Key 无效或无权限，请检查设置"`。
4. **`apiKey` 检查排在最后**：无 key 且 wav 超长时先报「录音太长」。建议把 `apiKeyProvider()` 检查提到最前（连 WAV 都不必解析）。
5. **`durationMs` 用声明值，不校验 `dataSize` 与实际字节**：注释已说明动机（防超小时长绕过），代价是「声明小、实际大」的头会让超长音频通过。8 MiB 上限兜底，可接受，建议在 KDoc 里补一句该取舍。
6. **`SttManager` 不校验空数组**：空 `ByteArray` 会进 `asr.transcribe`，目前由 `DeepSeekAsrClient` 兜（"没录到声音"）。若将来换 `AsrPort` 实现会漏。可在 `SttManager` 加一行前置判断。

#### 📊 数字核对

* DeepSeek 报 **639 / 80 suite**，审查线实测 **647 / 81 suite**（`failures=0 errors=0 skipped=0`）。
* 差异 **+8 / +1 suite = `FakeReminderDaoTest`(8 例，18:22 落地)** —— 并行线在 DeepSeek 跑完之后才提交，与本任务无关。
* **V2.3.3 自身 25 条（22 + 3）逐条核对无虚报**，且 `--tests '*TempProbeTest*'` 那次过滤运行已确认探针能被单独执行、失败信息可读。

#### 结论

**功能实现质量高（25 条测试扎实、协议逐字对齐、三条遗留全处理），但 2 项 🔴 必修未过**（硬编码 44 字节头与 V2.3.1 契约冲突；畸形头可抛异常）。修完这 2 项 + 补 3 条回归（LIST chunk、超大 sampleRate、401 文案）即可转 ✅。**V2.3.3 暂记 ⚠️，不算验收通过。**

---

### V2.3.3 必修项修复复核（逻辑线 2026-11-20 18:3x，转 ✅）

* ✅ **🔴1 硬编码 44 字节头**：`parseWavInfo` 改为按 RIFF chunk 链表扫描定位 `fmt` / `data`（奇数长度补 1 字节 pad，与 `PcmUtils.stripWavHeader` 同规则；`PcmUtils` 未改动），`deinterleaveLeft` 改用 `dataStart` 起点 + `available` 长度抽帧。回归：`list_chunk_does_not_corrupt_duration_parsing`（LIST 使 data 起点变 46，正确解析并发请求）、`list_chunk_with_overlong_declared_data_is_still_blocked`（120s 拦截 `requestCount == 0`）。
* ✅ **🔴2 畸形 sampleRate 抛异常**：`parseWavInfo` 拒绝 `sampleRate > Int.MAX_VALUE`，同时 `transcribe` 整体 `runCatching` 兜底，解析期任何异常都转 `Failure`。回归：`sample_rate_beyond_int_range_is_rejected_without_throw`（`0xFFFFFFFF` 双声道 → Failure，不抛）。
* ✅ 改进 3：`401/403 → "API Key 无效，请在设置里检查"`，各加一条回归。
* ✅ 改进 4：`apiKeyProvider()` 提到最前；无 key + 超长先报 key 无效，回归 `missing_api_key_is_reported_before_wav_checks`。
* ✅ 改进 5：`transcribeInternal` 注释写明「时长用 data chunk 声明值防极小字节绕过；单声道仍原样上传真实字节」的取舍。
* ✅ 改进 6：`SttManager` 加空数组前置拦截，回归 `empty_wav_is_rejected_without_calling_asr`。
* ✅ 附带：类 KDoc 显式声明只接受 RIFF/WAVE 线性 PCM16，Ogg 转「录音格式有问题」，把 `AsrPort` 注释的落差写明（未改 V2.3.1 任何文件）。
* 📊 全量 **81 suite / 654 tests / 0 failures / 0 errors / 0 skipped**；V2.3.3 自身 **32 条**（`DeepSeekAsrClientTest` 28 + `SttManagerTest` 4），较审查时 +7。
* **结论：2 项 🔴 + 4 项改进 + 1 项附带全部闭环，V2.3.3 转 ✅。**

---

### V2.3.3 审查线独立复核（2026-11-20 18:4x，654 全绿）

**方法**：重读 `DeepSeekAsrClient.kt`(270 行) / `SttManager.kt`(21 行) + 新增 5 条回归测试体；重跑全量 XML 解析。

**✅ 两项 🔴 修复属实（不是打补丁，是换实现）**

* **🔴1 已修**：`parseWavInfo` 变成真正的 RIFF chunk 链表扫描（`pos` 从 12 起、`size and 1L` 奇字节 pad、`next <= pos` 防死循环、`data` 命中即 break、`fmt`/`data` 双定位后才校验），四个 fmt 字段偏移 `b/b+2/b+4/b+14` 正确；`deinterleaveLeft` 起点改 `info.dataStart`、长度改 `info.available`（`minOf(声明 dataSize, 实际可读)` 防越界）。**同包 `PcmUtils` 未被改动**，符合 V2.3.1 契约。
* **🔴2 已修**：`parseWavInfo` 加 `sampleRate > Int.MAX_VALUE.toLong()` 拒绝，`WavInfo.sampleRate` 直接存 `Int`（不再有 `toInt()` 转换点），`transcribe` 外层 `runCatching` 兜底。`sample_rate_beyond_int_range_is_rejected_without_throw` **在修复前必抛 IAE、修复后必返回 Failure，是有效回归**（我上一轮探针实测过同一构造）。
* **改进 3–6 + 附带**：401/403 文案、key 前置（`missing_api_key_is_reported_before_wav_checks` 用 8 MiB+1 输入断言先报 key）、时长取舍注释、`SttManager` 空数组、KDoc 声明只收 RIFF/WAVE PCM16 —— 逐条属实。

**📊 数字核对**：实测 **81 suite / 654 tests / 0 failures / 0 errors / 0 skipped**，与提交一致；V2.3.3 自身 32 条（28 + 4）无虚报。

**⚠️ 新发现（本轮复核，1 项，交 V2.3.4 处理）**

1. **`runCatching` 会吞掉 `CancellationException`**（`DeepSeekAsrClient.transcribe` L62–64）：
   ```kotlin
   override suspend fun transcribe(wav: ByteArray): AsrResult = runCatching {
       transcribeInternal(wav)
   }.getOrDefault(AsrResult.Failure(BAD_RESPONSE_REASON))
   ```
   `runCatching` 捕获的是 `Throwable`，包含 `CancellationException`。V2.3.4 的 UI 若在识别中用 `Job.cancel()` 取消（用户上划取消 / 离开页面），取消会被吞成 `Failure("识别服务异常，请稍后重试")`，**协程取消语义被破坏**，UI 会弹一条假错误而不是静默取消。
   修法（3 行）：
   ```kotlin
   }.getOrElse { t ->
       if (t is kotlinx.coroutines.CancellationException) throw t
       AsrResult.Failure(BAD_RESPONSE_REASON)
   }
   ```
   **不阻塞 V2.3.3 验收**（当前无调用方做取消，单测也测不出），但 V2.3.4 落地取消功能前必须修。

**⚠️ 次级（记录，不阻塞）**

2. **`list_chunk_does_not_corrupt_duration_parsing` 断言偏弱**：只断言 `Success` + `requestCount == 1`。修复前 `dataSize` 被读成 5（≈0 ms 时长）**同样会成功发请求**，所以这条测试区分不了新旧实现；真正有区分力的是 `list_chunk_with_overlong_declared_data_is_still_blocked`（修复前 requestCount==1 会失败）。建议补一句「上传体 dataSize 字段 == 3200」的断言。另：测试注释写「data 起点变 46」，实际构造是 **58**（12 + fmt 24 + LIST 14 + data 头 8）。
3. **`bitsPerSample != 16` 检查排在时长计算之后**：8 位超长 WAV 会先报「录音太长」而非「格式有问题」，用户按提示分段仍用不了。建议把位深检查提到时长之前（1 行位置调整）。
4. **`runCatching` 兜底文案**：解析期本地异常落到 `BAD_RESPONSE_REASON`（"识别服务异常"），语义上更像本地格式问题；影响极小，可留给下次。

**结论**：2 项 🔴 真修（换实现而非绕过）、4 项改进 + 附带全闭环、数字无虚报 → **V2.3.3 转 ✅**。1 项 `CancellationException`  ⚠️ 明确交接给 V2.3.4（写进计划书 §V2.3.4 的取消契约）。

---

### V2.3.4 语音输入 UI（UI 线，2026-11-20 18:4x 完成）

**新增**：`ui/permission/RecordPermission.kt`、`ui/speech/VoiceInputController.kt`、`ui/speech/VoiceInputOverlay.kt`、`test/ui/speech/VoiceBarLevelTest.kt`
**修改**：`ui/chat/ChatScreen.kt`（`sttManager: SttManager? = null` 新参数 + 长按手势 + 浮层）、`ui/MainActivity.kt`（传 `app.sttManager`，`onVoiceClick` 清空为兜底）

**六条硬契约落点**
1. `onAutoStop` 里只 `scope.launch`，`stop()` 在 `withContext(Dispatchers.IO)`：不对录制线程自我 join。
2. 每个 session 一个 `AtomicBoolean`，`finish`/`cancel` 谁先到谁收尾，后到直接 return：`stop()` 非幂等不再误报空录音。
3. `voiceBarLevel()` 开方拉伸 + 8% 地板：3% → 0.24、9% → 0.36（5 条 JVM 单测钉住）。
4. `stop()` 只在 IO 线程；`release()`/`cancel()` 的丢弃路径用独立 Thread，页面销毁后麦克风必释放。
5. 空数组 → Toast + 不 transcribe + 不发消息。
6. `AsrResult.Failure.reason` 原样 Toast，不写死「识别失败」。

**与推荐结构的偏差（有意）**：controller 自持 `SupervisorJob + Dispatchers.Main.immediate` 作用域而非外部传入 —— `rememberCoroutineScope` 在界面不可见时派发会等帧，120 秒收尾可能被拖住；浮层用 `stateProvider` lambda 取状态，避免 60 ms 一次的音量采样把整块消息列表拖着重绘。

**跨线发现（交 code 线，非本次改动）**
1. **120 秒自动停必然被判「录音太长」**：`AudioRecorder` 到点后还会补一次阻塞读（≈128 ms），产出时长 120_000~120_256 ms，`DeepSeekAsrClient.MAX_DURATION_MS` 是 120_000 → 走完「录音已满 2 分钟」后紧跟一条「录音太长（超过 2 分钟），请分段说」。修法在数据层：到点丢弃超限帧，或客户端的本地闸门留 1~2 s 余量。
2. V2.3.3 审查交接的 `runCatching` 吞 `CancellationException`：UI 侧已在 transcribe 前后各拦一次 `released`，页面销毁不再弹假错误；数据层修法仍未做。

**📊 编译/测试**：`:app:compileDebugKotlin` BUILD SUCCESSFUL；全量 **83 suite / 671 tests / 0 failures**（基线 647 未破，含本任务新增 5 条）。真机 8 条验收待用户执行（设备 `<DEVICE_SERIAL>` 已连，用户选择自行安装测试）。

---

### V2.3.4 审查报告（审查线 2026-11-20 18:5x，671/83 全绿）

**方法**：读 4 个新文件（`RecordPermission` 142 行 / `VoiceInputController` 302 行 / `VoiceInputOverlay` 195 行 / `VoiceBarLevelTest` 54 行）+ `ChatScreen` 手势段（L109–174）与按钮挂载（L520–542）+ `MainActivity:261` → 重跑全量 XML（**671 / 83 / 0 / 0 / 0**，与提交一致）。

#### ✅ 通过

* **状态机三态互斥**：`Idle / Recording(volume, cancelZone) / Transcribing`，`start()` 三重拒绝（`released` / `state !is Idle` / `closing`），`Transcribing` 期间再长按被手势层拦掉并 Toast「正在识别，请稍候」——两个 `AudioRecord` 抢麦的路径全堵住
* **六条硬契约逐条落地**（与 UI 线自述一致，我逐条对码核实）：
  1. `onAutoStop` 只 `scope.launch { finishInternal(auto=true) }`，`stop()` 在 `withContext(Dispatchers.IO)` → **不对录制线程自我 join**（这正是 V2.3.2 复测提出的坑，规避正确）
  2. 每 session 一个 `AtomicBoolean`，`finish`/`cancel`/`release` 谁先到谁收尾 → **`stop()` 非幂等不再误报「没录到声音」**
  3. `voiceBarLevel()` 开方拉伸 + 8% 地板，`VoiceBarLevelTest` 5 条钉死（0→0.08、3→0.2394、9→0.3560、100→1、单调性全覆盖）
  4. `stop()` 只在 IO 线程；`cancel()`/`release()` 的丢弃路径用**独立 daemon Thread**（不是协程，避免 scope 被 cancel 后 `join` 中途中断导致麦克风被永久占住）—— 这个细节做得比合同要求还稳
  5. 空数组 → `finishSession(Idle)` + `onError(EMPTY_RECORDING_REASON)`，**不进 transcribe、不发消息**
  6. `AsrResult.Failure.reason` 原样透传，未改写
* **手势两个真坑处理正确**（UI 线自报，我核实）：
  - `positionChange()` **先取后 consume**（L163–164）——若先 consume 恒返回 `Zero`，上划取消永不触发
  - `withTimeoutOrNull` 超时返回 `null` 表示「按住够久」，`releasedEarly != null` 才 return（L115–130），语义没写反
  - 指针被系统吞掉（`change == null`）→ `cancel()` 而非 `finish()`，**绝不误发**
  - `catch (CancellationException) { controller.cancel(); throw e }` —— 取消不吞，正确
* **权限不自动弹**：只在长按按下时 `request()`；`canPrompt()` 判「系统已不再弹框」→ Toast 引导 + 跳应用详情页；`ON_RESUME` 重读（从设置页授权回来立即生效）；`request()` 里用 `isGranted(context)` **现查**而非读 remember 快照（注释写明理由：授权后不保证重组，读快照会永久 false）—— 这个坑躲对了
* **重组隔离**：浮层用 `stateProvider: () -> VoiceState` lambda，读完才在浮层作用域里重组；`waveform` 是 `SnapshotStateList`，`getOrNull` 逐柱读快照。**60 ms 一次的音量采样不会拖动整块消息列表**，这是正确的性能设计
* **`onVoiceClick` 保留为兜底**：`sttManager == null` 时按钮回退到旧 click，`RoundIconButton(onClick = null)` 只渲染不挂 click；`MainActivity` 已传 `app.sttManager`，那句 TODO 清空
* **取消不上屏**：`cancel()` 只 `discard()`，不调 `transcribe`、不调 `onResult` ✅ 符合验收

#### 🔴 必修 1 项（跨线，缺陷在数据层 `AudioRecorder`，非 V2.3.4；UI 侧已正确上报）

**120 秒自动停产出的音频必然被本地闸门拦成「录音太长」，用户录满 2 分钟一无所获**

UI 线报的现象属实，我补精确账：

* `readLoop` 每批读 2048 帧 = **128 ms**；`120000 / 128 = 937.5` → 第 938 批结束时 `elapsed = 120_064 ms` 触发 break
* break 后还有一次「残余读」（`AudioRecorder.kt:181-184`）→ 第 939 批 → 合计 **120_192 ms**（不是 120_000）
* `pcm = 939 × 4096 = 3_846_144` 字节 → `durationMs = 3_846_144 × 1000 / 32000 = 120_192 ms`
* `DeepSeekAsrClient.MAX_DURATION_MS = 120_000` → **120_192 > 120_000 → 拦**

**这不是偶发，是确定性**：只要靠「自动停」结束，时长必然 > 120_000 ms。用户看到的是「录音已满 2 分钟」紧接着「录音太长（超过 2 分钟），请分段说」，两分钟的说话被直接丢弃。

**修正 UI 线的一个说法**：不是「白传 3.8 MB」——本地闸门在发请求前就 return，`requestCount == 0`，**不产生流量也不计费**，是**白录**。

**修法（数据层，二选一，推荐 a）**：
- **(a) `AudioRecorder.stop()` 里按 `maxDurationMs` 截断**：
  ```kotlin
  val maxBytes = maxDurationMs.toLong() * sampleRate * 2L / 1000L   // 120s → 3_840_000
  val pcmBytes = if (raw.size > maxBytes) raw.copyOf(maxBytes.toInt()) else raw
  ```
  上传正好 120.000 s，API 上限内最安全，且不用动闸门。
- (b) `DeepSeekAsrClient` 闸门留 1–2 s 余量（如 `MAX_DURATION_MS = 121_000`）。**不建议**：等于允许超限音频上云，云端可能 413。

**真机验收影响**：第 6 条「连按 120 秒」在修 (a) 之前**必然显示失败**，用户可先跳过该条。

#### ⚠️ 需改进 4 项（不阻塞）

1. **`discard()` 的 `closing` 只挡 `start()`，静默无提示**：取消后立刻再长按 → `start()` 返回 false，手势层 `return@awaitEachGesture` 时**不 Toast**，用户以为按钮坏了。建议 `start()` 区分「closing 中」与「初始化失败」两种 false，前者提示「稍等，正在释放麦克风」。
2. **`voiceBarLevel` 的 KDoc 引用了不存在的常量**：注释写「与 `VoiceInputController` 的 `BAR_FLOOR` 对齐」，但 `VoiceInputController.companion` 里**没有 `BAR_FLOOR`**（只有 `WAVEFORM_BARS` / `POLL_INTERVAL_MS` / 三个 reason）。是注释陈旧，建议改成「本文件私有常量 `BAR_FLOOR_CONST = 0.08f`，波形 8% 地板」。
3. **授权后需重新长按，无提示**：长按 → 弹权限框 → 用户允许 → 本次手势已 `return`，麦克风不会立刻开。建议授权回调里 Toast「已授权，请再按住说话」。
4. **`cancel()` / `release()` 里直接写 `state = VoiceState.Idle`**（不经 `finishSession`）：`release()` 已置 `released = true` 前置返回，实际安全；但 `cancel()` 没查 `released`，若将来有「销毁后才回调 cancel」的路径会写已废的 state。建议 `cancel()` 里也走 `if (!released)`。

#### 结论

**代码质量高于合同要求**（独立 Thread 丢弃、`stateProvider` 重组隔离、`positionChange` 顺序、`isGranted` 现查都是踩过坑才写得出的）。V2.3.4 自身**无 🔴**，代码侧通过。

**但 V2.3.4 的验收项 6 依赖数据层修 (a) 才能过**——这属于 V2.3.2 的遗留缺陷（`AudioRecorder` 补读超出 `maxDurationMs`），**建议立刻交逻辑线修 3 行**，否则真机第 6 条必红。

进度：V2.3.4 记 **✅ 代码通过 / 真机 8 条待验**；数据层缺陷单列一条待修项。

---

### 120s 截断修复复核（审查线 2026-11-20 18:5x，**677 / 83 全绿**）

**✅ 修复正确（闭环）**

* **纯函数 `truncatePcmToMaxDuration`**：`maxDurationMs.toLong() * sampleRate.toLong() * 2L / 1000L` —— **Long 运算正确**（`120000 × 16000 × 2 = 3.84e9` 已超 `Int.MAX_VALUE`，若用 Int 会溢出成负数，这里躲过了）
* **先向下取偶** `evenMax = maxBytes - maxBytes % 2` —— 16 位样本边界守住，不会切出半个样本
* **调用点正确**：`pcmBytes` 取出、空值早退**之后**、`buildWavHeader` **之前**（`AudioRecorder.kt:246`），且 WAV 头用 `trimmed.size` ✅
* **闸门未改**：`DeepSeekAsrClient.MAX_DURATION_MS` 仍 `120_000`，`durationMs = 3_840_000 × 1000 / 32_000 = 120_000`，`120_000 > 120_000` 为 false → **放行** ✅ 严格上限没被破坏
* **最有价值的测试**：`truncate_fixes_the_939_batch_auto_stop_overrun` 用 ASR 同一公式反算 `trimmed.size * 1000 / (16000 * 1 * 16/8) == 120_000L` —— 直接验证「闸门不再拦」，不是只测长度
* 其余 5 条：未超限 `assertSame`（契约写明"原样返回同一引用"）、精确截到 3,840,000、奇数取偶（1500 Hz/1 ms → 3→2）、极小上限返回空、两条 `assertThrows`
* **实测**：`suites=83 tests=677 failures=0 errors=0 skipped=0`，`WavHeaderTest` 10→16，与提交一致

**⚠️ 新增 1 项（轻微，不阻塞）**

* **`trimmed` 为空时会产出 44 字节空 WAV**：`truncate` 在极小 `sampleRate`（使 `maxBytes < 2`）下返回 `ByteArray(0)`，随后 `WavHeader.buildWavHeader(0) + ByteArray(0)` = **44 字节空 WAV**，与 `stop()` 上一条注释「不把'什么都没录到'伪装成 44 字节空 WAV」自相矛盾。实机不可达（`AudioRecord` 最低 8 kHz，`maxBytes` 远大于 2），但语义上应补一行：`if (trimmed.isEmpty()) return ByteArray(0)`。

**关于测试条数**：我上轮建议 7 条，实做 6 条，缺的 `custom_sample_rate(48000)` 已被 `1500 Hz` / `100 Hz` 两条覆盖（若写死 16000，那两条会直接失败）—— **不算缺口**。

**结论**：120s 缺陷**真修且验证到位**，V2.3.4 真机验收第 6 条现在应能通过（音频截到恰好 120.000 s，识别正常，`onNotice("录音已满 2 分钟")` 单独提示）。剩余 `runCatching` 吞 `CancellationException` 一项仍未做（V2.3.3 遗留，UI 侧已用 `released` 双拦规避，非阻塞）。

---

### V2.3.5 设置页 STT 配置（UI 线，2026-11-20 20:1x）

**阶段 0**：动手时 `ConfigRepository.getSttEnabled` 尚不存在 → 先做阶段 1/2/4；期间逻辑线落地（`ConfigRepository.kt:125-127` 双向读写 + `CompanionApp.kt:148` `{ configRepository.getSttEnabled() }`），随即补齐阶段 3 + 4.4，一次编译通过。

**改动**：`ui/settings/SettingsScreen.kt`（`SettingsForm.sttEnabled` 默认 true，L49；语音总开关后加 SwitchRow + 计费说明，L273）、`ui/chat/ChatScreen.kt`（新参数 L71；controller remember key 带 sttEnabled 且关掉不建 L96；InputBar 参数 L446 + `else if (sttEnabled)` L541）、`ui/MainActivity.kt`（initial L111 / persist L162 / 聊天分支 L238 + 传参 L269）。本任务 **0 新增测试**。

**与任务书的三处偏差（有意）**
1. **4.4 原写的 `sttEnabled = form.sttEnabled` 编译不过**：`form` 只存在于 `"settings"` 分支作用域，`ChatScreen` 在 `else` 分支。改为在 else 分支 `remember { app.configRepository.getSttEnabled() }`——离开再回聊天页时该分支整块重进组合，`remember` 自然重读，不需要额外 `LaunchedEffect`。
2. **`sttEnabled` 必须再传进 `InputBar`**：麦克风/发送的 `if (canSend) … else …` 分支在 `InputBar` 内部而不在 `ChatScreen` 正文，故给 `InputBar` 加同名参数并透传。
3. 开关位置照任务书插在「语音总开关」之后、TTS 语速 slider 之前：两个总开关相邻、TTS 细节在下，语义上是「总开关组 → 细节」。

**📊 编译/测试**：`compileDebugKotlin` BUILD SUCCESSFUL；全量 **85 suite / 694 tests / 0 failures**。677→694（+2 suite）是逻辑线 V2.3.4 修复与 V2.3.5 数据层的用例，非本轮新增；中途一次编译撞上逻辑线正在改 `CompanionApp`（`getSttEnabled` 未落地）报 2 个错，其落地后立即转绿。


#### V2.3.5 审查结论（审查线 2026-11-20 20:1x，**694 / 85 全绿**）

**逐条核实（全部实读源码，非采纳清单）**

| 断言 | 实测 | 判定 |
|---|---|---|
| `SettingsForm.sttEnabled` 默认 true（L49） | `val sttEnabled: Boolean = true,` 紧跟 `ttsSystemEngine` | ✅ |
| 开关 + 计费说明（L273 / L276-280） | `SwitchRow("语音输入（长按说话）", form.sttEnabled)` + bodySmall「0.39 元/小时」 | ✅ |
| **开关不在 `if (form.ttsEnabled)` 内** | `if (form.ttsEnabled) {` 在 **L282**，即 STT 块之后 | ✅ 关键项，避开了「关朗读连带藏 STT」 |
| `ChatScreen` 新参数（L71） | `sttEnabled: Boolean = true,` | ✅ |
| **remember key 含 `sttEnabled`（L96）** | `remember(sttManager, sttEnabled, density)`，`if (!sttEnabled) null else sttManager?.let{}` | ✅ 硬要求①达成 |
| **关掉真不建 controller** | L117 `voiceController?.let{}` ⇒ controller 为 null ⇒ `voiceGestureModifier` 为 null ⇒ 全身无 pointerInput | ✅ 按钮藏了手势也不会挂着 |
| `InputBar` 透传（L446 / L541） | `else if (sttEnabled) {` 拆的是后半个 `else`，L525 `if (canSend)` 发送分支原样在前 | ✅ 有内容时仍能发送（关 STT 不影响发消息） |
| 布局不塌 | L522 `Spacer(weight(1f))` 在按钮前，「+」仍是 Row 起点 | ✅ 只是空态右侧留白（不可避免） |
| `MainActivity` initial / persist（L111 / L162） | 两处都在，位置正确 | ✅ 读写闭环 |
| `ConfigRepository`（L125-127 / L258） | `getBoolean(KEY_STT_ENABLED, true)` / `putBoolean`；`KEY_STT_ENABLED = "stt_enabled"` | ✅ 默认 true 双层兜底 |
| `CompanionApp:148` | `SttManager(deepSeekAsrClient, { configRepository.getSttEnabled() })` | ✅ lambda 现读、未缓存成 val |
| 测试数 | XML 解析 `suites=85 tests=694 failures=0 errors=0 skipped=0` | ✅ 与提交一致，本任务 0 新增 |

**三处偏差裁定**

1. **`sttEnabled = form.sttEnabled` 编译不过 → 改 `remember { getSttEnabled() }`（L238）**：**接受**。`form` 确实只在 `"settings"` 分支作用域内；`when (screen)` 的分支切换会 dispose 旧 group、重新进入组合，`remember` 随之重算——编译器的 group key 语义保证这一点。时序也成立：`onSave` 里 `persist(form)` → `apply()` 同步更新内存 map → `screen = "chat"` → 重组时 `getBoolean` 读到新值。
   ⚠️ **但这是隐式契约**：若将来把 `when(screen)` 换成 `AnimatedContent` / `Crossfade`（保留子组合状态），开关会延迟一期生效。L236-237 注释已写明前提，可接受；建议后续做 V2.3.6 若动主界面结构时回头验一遍这条。
   ⚠️ 另一后果：**不点「保存」直接返回时聊天页不联动**（`onBack` 只 `screen = "chat"`，不写 pref）——但这恰好是正确语义（未保存 = 未生效），且下次进设置页 `form` 重读 pref 显示旧的，前后一致，无漂移。

2. **`sttEnabled` 必须再传进 `InputBar`**：**接受，且提交者的判断是对的**。`if (canSend) … else …` 在 `InputBar` 内部（L525/L541），不在 `ChatScreen` 正文。任务书原文「L530-542」本就是 `InputBar` 体内的行号，是我在提示词里没点明所在函数，属**提示词表述不清**，不是实现偏差。

3. **开关插在「语音总开关」与语速 Slider 之间**：**⚠️ 非阻塞，建议调整**。提交者的理由（两个总开关相邻）部分成立，但代价是 `if (form.ttsEnabled) { 语速 + 引擎 }`（L282）被 STT 块隔开——语速/引擎是「朗读」的直接子项，现在视觉上像是「语音输入」的配置。建议把 L271-280 整块移到 `if (form.ttsEnabled) { … }` **闭括号之后**（TTS 子块结束、下一个功能块之前）。纯顺序调整，不动逻辑，不急。

**无 🔴**。真机 6 条仍待用户；第 5 条（无活跃录音）提交者给的替代命令 `adb shell dumpsys audio | findstr /i record` 比我的 `dumpsys media.audio_flinger` 更可靠，采其建议。

**遗留（非本任务）**：`DeepSeekAsrClient.transcribe` 顶层 `runCatching` 仍吞 `CancellationException`（V2.3.3 遗留，UI 侧 `released` 双拦规避）；`AudioRecorder` 极端情况下 `trimmed` 为空会发 44 字节空 WAV（真机采样率下不可达）。

#### V2.3.5 逻辑线审查（审查线 2026-11-20 20:2x，**694 / 85 全绿**）

**审查方法**：读 5 个改动文件全文 + `ChatScreen` / `MainActivity` 调用链 + `VoiceInputController` 调用点 + `FakeReminderDao` 签名 → 用脚本独立聚合 XML 复核测试数字（不采信提交的报数）。

**逐条核实**

| 提交断言 | 实测 | 判定 |
|---|---|---|
| `KEY_STT_ENABLED = "stt_enabled"`（L258） | 常量区 `private const val KEY_STT_ENABLED = "stt_enabled"`，紧随 `KEY_TTS_ENABLED` | ✅ 与计划书 §V2.3.5 / 附录 B5 的跨版本契约逐字一致 |
| `getSttEnabled()` 默认 true（L125） | `prefs.getBoolean(KEY_STT_ENABLED, true)` | ✅ 与 TTS 写法对称 |
| `setSttEnabled` 写盘（L126-127） | `prefs.edit().putBoolean(...).apply()` | ✅ |
| `CompanionApp:147-149` lambda 现读 | `SttManager(deepSeekAsrClient, { configRepository.getSttEnabled() })`；旧 `TODO(V2.3.5)` 已 **0 命中** | ✅ 未缓存成 val |
| `SttManager.isEnabled()`（L21） | `fun isEnabled(): Boolean = enabled()` | ✅ 现查不缓存 |
| 新增测试 | `isEnabled_reflects_lambda_each_call`：同一实例 false→true 翻转 | ✅ 真断言，非空壳 |
| `FollowupPortImplTest.fixture` 改 suspend（L33） | `private suspend fun fixture(`；`FakeReminderDao.insert` 确为 `override suspend fun`（该文件 mtime 18:22，缺陷自 V2.2.2 起就在）；6 个调用点全在 `runBlocking` 内 | ✅ 修复正确，且**是本轮必要项**（不修则全量测试源码编译不过） |
| 测试数字 | 独立聚合 XML：`suites=85 tests=694 failures=0 errors=0 skipped=0`；XML 最新 20:15:00 > 本轮源码最大 mtime 20:13:43 | ✅ 数字属实、非旧快照 |

**⚠️ 需要改进（无阻塞，V2.3.6 前处理）**

1. **`isEnabled()` 目前是生产死代码**：全仓只有 `SttManagerTest` 调它。UI 线实际走的门是 `ChatScreen.sttEnabled: Boolean`（`MainActivity:238` 直接 `getSttEnabled()`），**没有** `sttManager?.isEnabled()`。两条路读同一个 pref、行为等价，但「交接契约给的方法没人用」会让下一个接线者选错门。
   建议二选一（都不改行为）：① `MainActivity:238` 改 `remember { app.sttManager.isEnabled() }` —— 开关读取点收回到 `SttManager`，`ChatScreen` 不必再知道 pref 存在；② 删 `isEnabled()`，契约改成「传 Boolean」。
2. **门禁现在有三层，逻辑层这道不能删**：`transcribe()` 内部 `if (!enabled())` 是唯一「入口无关」的兜底。**V2.3.6 的通知栏快捷入口（`VoiceInputActivity`）必须自己判开关**，否则它就是绕过点——`VoiceInputActivity` 目前与 `ChatScreen` 完全独立，不吃 `sttEnabled`。
3. **提交清单漏报 `ui/` 改动**：声明"未触碰 ui/"，但 `ui/MainActivity.kt`(20:13:32)、`ui/chat/ChatScreen.kt`(20:12:04)、`ui/settings/SettingsScreen.kt`(20:11:35) 在本轮时间窗内都含 V2.3.5 内容。判定：这三处我在 20:1x 已单独审过（UI 线交付），**行为无问题**；但清单与实际不符，下次并列清楚归属，避免误判串线。

**📝 备注（不记问题）**

* `enabled()` 在 `VoiceInputController` 的 `Dispatchers.Main.immediate` 上被调（`transcribe` 入口），`prefs.getBoolean` 命中内存缓存、无磁盘 IO；但若将来把 `ConfigRepository` 改成懒加载或 `commit()`，这里会变主线程 IO，届时把 `transcribe` 整体挪进 `withContext(IO)`（`DeepSeekAsrClient` 内部已切 IO，只差这一行）。

**🔴 必修：无。**

**结论**：V2.3.5 逻辑线 **✅ 通过**。键名契约、默认值、lambda 现读、单测均属实，并顺手修掉 V2.2.2 遗留的**测试源码编译阻断**。真机 6 条仍待用户。

---

### V2.3.6 审查报告（审查线 2026-11-20 20:4x，**755 / 93 全绿**，判 **1 项 🔴**）

**审查方法**：8 个改动文件全文实读（service 3 个 + `VoiceInputActivity` 207 行 + manifest + themes + `ConfigRepository` + `AppStateEntity`）+ UI 三处调用链（`MainActivity.onResume`/`resumeTick`、`ChatViewModel.consumeVoicePendingText`、`ChatScreen.LaunchedEffect`）+ 3 个新测试全文 + XML 独立聚合 + 逐 suite 数字对账。

#### ✅ 通过的部分

| 断言 | 实测 |
|---|---|
| `NOTIFICATION_ID = Int.MAX_VALUE` 不撞 id | 核对 `ProactiveDeliveryRunner:156` 公式 `(msg.id % Int.MAX_VALUE).toInt()` 值域 `[1, MAX_VALUE-1]` → **取不到 MAX_VALUE**，结论成立（但测试写法有缺陷，见 ⚠️4） |
| requestCode 用负数隔离 | `-1`/`-2`；`AlarmCodes` 从 1 起、notifier `+100_000`，全为正 → 无交集 ✅ |
| 独立渠道 `quick_access` IMPORTANCE_LOW + 静音/无震动/无角标 | `ensureChannel` 逐项属实，**未复用 `proactive_message`** ✅ |
| `startForeground` 抢在 5s 窗口 | `onCreate` 与 `onStartCommand` 都先 `startAsForeground()`，中间无 IO ✅ |
| `ServiceCompat.startForeground(..., SPECIAL_USE)` | 34+ 用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，<34 传 0（29–33 不校验 type，安全）✅ |
| manifest 两权限 + `specialUse` + `<property>` + `exported=false` | 逐字一致；`VoiceInputActivity` 带 `excludeFromRecents`/`taskAffinity=""`/`exported=false`，**没有** `noHistory`（符合契约）✅ |
| `Theme.Companion.Transparent` | 透明 + `windowIsTranslucent` + dim + `windowIsFloating=false` ✅ |
| **Application 里不启动 FGS**（验收 3） | `CompanionApp.kt` 对 `QuickAccess|startForeground` **0 命中**；启动点只有 `MainActivity.onResume` + settings `persist` ✅ |
| 关闭即撤通知 | `sync` 关分支 = `stopService` + `NotificationManagerCompat.cancel`；`onDestroy` 再 cancel 一次 ✅ |
| V2.3.5 绕过点已堵 | `onCreate` 第一件事 `if (!app.sttManager.isEnabled()) → Toast + finish`，**在 setContent 之前**，不会闪一下浮层 ✅ |
| `AppStateEntity(KEY_VOICE_PENDING_TEXT, text)` 参数序 | 实体主构造是 `(key, value)` → 顺序正确，无静默错位 ✅ |
| 回填只填不发 + 读即删 | `consumeVoicePendingText` 先 `get` 再 `delete` 再投 `voicePending`；`ChatScreen` 只赋 `messageInput`，**无 send 调用** ✅ |
| `resumeTick` 接线闭合 | `MainScreen(resumeTick = resumeTick)` 真的传进去了（报告说这处曾漏传，**现文件是对的**）✅ |
| 测试数字 | 我的聚合：`suites=93 tests=755 failures=0 errors=0 skipped=0`；XML 最新 **20:35:03** > 源码最大 mtime **20:28:05** → 成绩晚于改动 ✅ |
| 并发基线说法成立 | 逐 suite 对账：694 + 本任务 12（5+4+3）+ 并行线 49（`FollowupTaskExtractor`10 `FollowupExtractionParser`9 `FollowupIntent`9 `RootPermissionChecker`11 `RootProbe`8，`BootRestorePlanner` 8→10）= **755**，精确吻合，无虚报 ✅ |

#### 🔴 必修 1 项

**`VoiceInputActivity` 的手工路径根本产生不了识别结果 —— 文案与行为直接矛盾，验收第 2 条必红。**

`onOverlayClick()`（L121-137）的实际语义：

| 状态 | 点击后行为 |
|---|---|
| 未录音 | `recorder.start()` 开始录音 |
| **录音中** | `cancelled = true` → `recorder.stop()` → **结果丢弃** → `finish()` |

而屏幕文案（L114）写的是「正在聆听… **再点一次结束**」。`handleWav()` 的**唯一调用点**是 L101（`recorder.onAutoStop`，即 120 秒自动停）。

⇒ 用户点一下开始、再点一下（照文案想「结束并识别」）→ **什么都不会发生，直接被丢弃**。唯一能出结果的方式是**一直等到 120 秒自动停**。这条路径上「点通知栏 → 说话 → 识别 → 主界面打开且输入框已填好」不可能通过。

**修法（择一，都只改这一个文件）**：
1. **推荐**：录音中点击 = 正常收尾 —— `if (!stopGuard.compareAndSet(false,true)) return`；`busy = true`；`ioScope.launch { val wav = recorder.stop(); withContext(Main){ recording.value = false }; handleWav(wav) }`；**取消改走返回键**（`onBackPressedDispatcher`）+ 遮罩长按，并在录音中把文案改成「再点一次结束并识别」保持不变、另加一行小字「返回键取消」。
2. 或保留「点击=取消」，但必须①文案改成「正在聆听… 点一下取消」②另设一个明确的「结束并识别」按钮。

同时建议：录音中 `onPause` 仍按现在丢弃（已文档化，不动）。

#### ⚠️ 需要改进（5 项，不阻塞本次判决）

1. **`START_STICKY` + `onStartCommand` 不复查开关**：进程被系统内存压力杀掉后，系统会重启服务 → `startAsForeground()` 无条件执行 → **开关关着的用户会突然看到常驻通知复活**（直到下次打开 App 由 `onResume.sync` 自愈）。建议 `onStartCommand` 里加 `if (!QuickAccessEntrance.shouldRun(...)) { stopSelf(); return START_NOT_STICKY }`（3 行）。
2. **`stopWithTask` 默认 true 的语义没人确认**：用户从最近任务划掉 App → 服务被停、通知消失。对「常驻入口」来说这可能**不符合预期**（划掉后通知还在、点一下冷启动更符合直觉）。这是一个产品决策，需要写进计划书而不是让实现者默认定；同时要记住 Android 对「划掉后自动拉起」的容忍度很低，别为此加 JobScheduler 兜底。
3. **`ioScope` 未在 `onDestroy` 取消**：`VoiceInputActivity` 销毁后协程仍会跑完 `transcribe`（结果因 `cancelled` 被丢弃，无副作用，但属于协程泄漏）。`onDestroy` 加 `ioScope.cancel()` 即可。
4. **「改公式单测立刻红」这句不成立**：`QuickAccessNotificationSpecTest.notificationId_neverCollidesWithProactiveDerivedIds` **复刻**了 `msg.id % Int.MAX_VALUE` 这个公式，而不是调用生产代码 —— 有人改 `ProactiveDeliveryRunner:156` 它不会红。真要做成守门测试，得把公式抽成共享函数（`ProactiveNotificationIds.of(msgId: Long): Int`）让两侧同源。当前断言仍有价值（钉住常量与值域），但不能按「守护公式」的说法写进结论。
5. **报告把 `MainScreen(resumeTick)` 的补传记成「lead 修」——我没有改过任何代码**。本会话我只写了文档（`陪聊APP项目规则.md`）与提示词，源码零改动。归因错误会污染审计链，下次请写「实现者自己补的」或留空。另外该处现已正确传入，**不是缺陷**。

#### 📝 备注

* **我给的提示词 §3.6 第 6 条写错了**：写成「`WavHeader.buildWavHeader` 拼头」，实际 `AudioRecorder.stop()` **已返回完整 WAV（44 字节头 + 截断后 PCM）**（`AudioRecorder.kt` stop 末尾 `buildWavHeader(...) + trimmed`）。交付者的偏差 #4 是**正确实现**，不是偏离。这条要写进计划书避免下次又照抄错。
* `NotificationCompat.Builder` 未设 `.setPriority(PRIORITY_LOW)`：channel 已 `IMPORTANCE_LOW`、minSdk26 → 无实际影响，不记问题。
* 每次 `onResume` 都 `startForegroundService` + 重新 `notify`：固定 id 幂等、`setShowWhen(false)` 无感，只是理论上重复一次 binder 调用。交付者已在偏差 #6 自述，**接受**。
* `resumeTick` 用 `mutableIntStateOf` 放在 Activity 字段、每次 onResume `++` → 会让 `MainScreen` 体重组一次（读该值的 scope）。成本可忽略，但若担心可换成 `Channel`/`SharedFlow` 一次性事件。**不改**。

#### 真机 6 条（未跑，无设备）

`adb devices` 空 —— 与 B1 同一个前提（**插 USB** 才可用）。接机后按提示词 §6 的 3 条命令 + 6 条人工验收跑。

---

### V2.3.6 复核（审查线 2026-11-20 21:1x，**781 / 96 全绿**）

#### ✅ 原 🔴 已闭环

* `onOverlayClick()` 现为「录音中点击 = `finishRecording()`」（`VoiceInputActivity.kt:139-141`），与 120s 自动停**共用** `handleWav` 入口；`stopGuard.compareAndSet` 保证只 stop 一次。文案「再点一次结束」与行为一致 —— 我上一轮判 🔴 的那条成立，现修复正确。
* 关于「mtime 20:43:29 说明上一轮已改」：文件**现在**是 `20:58:08`（本轮又改过），20:43 那个时间戳已查不到，**既无法证实也无法反驳**；本复核只认代码现状 —— 现状是对的。

#### ✅ `NEW_TASK` 加固（跨线根因假设）

`QuickAccessService.buildNotification()`（L104-117）`voiceIntent` 已补 `FLAG_ACTIVITY_NEW_TASK or CLEAR_TOP or SINGLE_TOP`，与 `tapIntent` 一致；配合 `taskAffinity=""` + `excludeFromRecents` 的独立 task 契约，这是对的，也必须这么写。

**但「这就是根因」目前只是最可能的解释，不是已证事实**：`PendingIntent` 由系统侧发起时，部分版本/ROM 会隐式补 `NEW_TASK`，所以旧代码可能只是**偶发**被开进别处；唯一能定论的是真机第 2 条（点通知栏 → 说话 → 识别 → 输入框已填）。别把这条写进「已验证」栏。

#### ✅ `recording.value = recorder.isRecording`（不是防幻觉，是真防住了）

核过 `AudioRecorder.start()`（L131-178）：`getMinBufferSize<=0`、`state != INITIALIZED`、`recordingState != RECORDING`、`catch (Throwable)` **四条失败路径全部在 `start()` 返回前同步置 `recording = false`**，而 `Session.recording` 默认 `true` ⇒ 返回后立即读 `isRecording` 可信。原写法（盲目置 true）确实会造成「界面说在听、其实没录」。

#### ⚠️ 遗留 1 项（本轮新发现，我方上一轮的判词也不够准）

**`onPause` 在「已停录、正在识别」阶段仍无条件 `cancelled = true`**（L230-243）。我上一轮写的是「录音中 `onPause` 仍按现在丢弃（已文档化，不动）」——那条只覆盖「还在录」，**没覆盖「已 stop、等网络那 1~2 秒」**。现在 `busy` 已置位但 `recording=false`，此时任何真实 pause（息屏/来电/系统弹框/别的 App 抢前台）会让 `handleWav` 判 `cancelled` 直接 `finish()`，**白丢一次已经录完的语音**。

```kotlin
    override fun onPause() {
        super.onPause()
        // 已停录、正在识别/收尾：结果该落地，不该被一次 pause 抹掉。
        // 已经发出去了的工程不用界面承接——put 进 app_state 后，MainActivity.onResume 会消费掉。
        if (!recording.value && busy) return
        cancelled = true
        if (recording.value && stopGuard.compareAndSet(false, true)) {
            ioScope.launch { recorder.stop(); finishOnMain() }
        } else {
            finish()
        }
    }
```
（`busy=true` 后到 `finish()` 之间没有复位点，`!recording && busy` 恰好等于「stop 已开始、结果未落地」这个窗口。若担心 activity 卡住，可再加 `busy` 的兜底 `withTimeout`——本项目 1~2s 的 ASR 不需要。）

#### 数字（不采信报数，独立聚合）

* 21:05 快照：`suites=94 tests=762 failures=0 errors=0 skipped=0`（你报的 `755/93` 是 20:35 的值，21:05 已涨到 762 —— 语音线又加了 7 条）
* 21:12 快照：`suites=96 tests=781 failures=0 errors=0 skipped=0`，`BUILD SUCCESSFUL`
* 21:07 我遇到一次 `BUILD FAILED`：那是 **V2.2.4 线正在写** `ReminderAlarmReceiver.kt`(21:07:43) / `CompanionApp.kt`(21:07:53) / `FakeAlarmScheduling.kt`(21:08:08) 的中间态，**与 V2.3.6 无关**，同一命令 21:08 复跑即恢复。并发写盘期间的单次失败不能当结论。

---

### V2.2.4 追问循环调度（逻辑线 2026-11-20 21:5x，**800 / 98 全绿**，真机未验）

#### 交付

* `FollowupChainRunner`（`data/reminder/`）：`tickFollowup` / `tickTimeout`，顺序固定「判上限 → 排 TIMEOUT → 计数 → 入队」；`FOLLOWUP` 投 `PendingMessageType.FOLLOW_UP` + `PendingMessageContent.Followup`，`TIMEOUT` 只 `port.advance()`。
* `PendingMessageContent.Followup(reminderId, attempt, task)` + `FU_PREFIX = "followup:"` + `followupOrNull()`；非法输入回落 `PlainText`。
* `ReminderAlarmReceiver` 两分支接 runner（TIMEOUT 不触发送达）；`CompanionApp` 装配 `followupChainRunner` / `followupBootRestorer`。
* `FollowupAlarms.FOLLOWUP_TIMEOUT_MS = 30 * 60_000L` 改为公开常量。

#### 审查闭环

* **R1（每日额度）**：`ProactiveDeliveryRunner` 只在 `category != INSTRUCTION && != FOLLOWUP` 时 `onDelivered()`。**刻意不按 `== SPONTANEOUS`**：`ProactiveGateRules` 第 2.5 步用同一个计数卡节日/环境/熬夜，按 SPONTANEOUS 会把它们的每日上限一起关掉。
* **S1**：`KNOWN_TAGS` 补 `followup:`；`isUnknownTagContent` 提成 `internal companion fun` 以便直测（漏配从此能被测试抓住）。
* **S2**：追问 prompt 去掉 `attempt`（既与「不要提第几次」矛盾，又会在勿扰顺延时过期）。
* **S3 修正（第一版写错，已根治）**：过期追问行**不重放追问，但必须有闹钟**（`BootFollowupRestorer`，纯 JVM 10 条测试）。第一版只 `filterNot` 掉 → 该行永久 pending、`anyPendingFollowup()` 恒真 → 新链被静默挡住（比重复追问更糟）。
* **R1 第二轮（force-stop 路径）**：两条恢复路径现在都接 `BootFollowupRestorer`：
  * `restoreAlarmsAfterBoot`（重启/升级）→ `splitAndRestoreTimeouts` + `BootRestorePlanner`；
  * `restorePendingRemindersLight`（每次启动，专治 force-stop / ROM 清后台）→ 先 `splitAndRestoreTimeouts`，再 `scheduleOverdueFollowups` 给「仍留在 pending 里的过期追问」补 FOLLOWUP 钟，最后才过滤 `triggerTime > now`。
* **P1（第三轮，判据放宽）**：`scheduleOverdueFollowups` 认的是「仍在 pending 里的过期 followup」而不只是 `attemptCount == 0` —— 因为补 TIMEOUT **失败退回**的行也是过期且 `attemptCount > 0`，只认 0 次会让它在 light 路径被 `triggerTime > now` 滤掉、第三次变成零闹钟（链永久停摆）。代价：失败时可能重复一条追问，已接受（比死链轻）。
* **P2（第三轮，无内部时钟）**：`splitAndRestoreTimeouts(rows, at)` 与 `scheduleOverdueFollowups(rows, at, …)` 的 `at` **没有默认值**；两处调用点都传同一个 `now`。将来新增调用点忘传会编译失败，而不是悄悄再取一次时间（那会让边界行一边判过期一边判未来）。
* **P3（第三轮，返回值语义）**：`restorePendingRemindersLight()` 的返回值**只统计未来行**；补 TIMEOUT / 补 FOLLOWUP 的条数在 `BootRestore` 日志里单独打（`轻量重排：未来行 N 条｜补 TIMEOUT 钟 X 条｜补过期追问 FOLLOWUP 钟 Y 条`），不要拿返回值相加。
* **两分支语义（S1）**：`attemptCount > 0` 且过期 → 补 **TIMEOUT** 钟（等回复）；`attemptCount == 0` 且过期 → 留在 pending，由 planner 重放成 `now+5s` 的**第 1 条追问**（不再白等 30min + interval）。
* **TIMEOUT 用绝对时刻 `triggerTime + FOLLOWUP_TIMEOUT_MS`（不是 `now + 30min`）**：重复补钟**幂等**，不会被频繁冷启动越推越晚；若该窗口早已过完，`AlarmManager` 对过去的 `triggerAtMillis` **立即投递** → 链立刻往前走一步（`advance` → 下一条按 interval 发）。
* **补钟失败 → 该行退回 pending（可能重复一条追问）**：失败后既不重放也没有钟 = 链永久死掉、`anyPendingFollowup()` 恒真，比重复一次更糟。**两条路径的下一跳不同**：boot 由 `BootRestorePlanner` 重放成 `now+5s`；light 由 `scheduleOverdueFollowups` 补 FOLLOWUP 钟（P1 的判据放宽正是为它兜底）。`AlarmScheduler` 自己 catch `SecurityException` 并回落，实际极难触发。

#### 已知缺口（留给单独一轮，未修）

1. **「人在用 App 就别催」的产品语义没实现**：现在补钟用绝对时刻，用户频繁冷启动**不会**把追问越推越晚（刻意如此，见上）。若产品上想要「刚开过 App 就延后催」，应改成「补钟时刻 = max(原窗口截止, now + 最短等待)」，属调参/产品决策，不是恢复链缺陷。
2. **勿扰顺延期间序号会变旧**：prompt 已不带序号，当前无影响；将来若要在正文里显示「第几次」，必须送达时回查 `attemptCount`，不能用作入队时的快照。
3. **`advance()` 非原子**（V2.2.2 既有）：`cancelTimeout → updateTriggerTime → scheduleFollowup` 若最后一步抛，旧 TIMEOUT 已取消、新 FOLLOWUP 没排 → 链断。`AlarmScheduler` 已 catch `SecurityException` 并回落，实际极难触发；本轮只记录不改。
4. ~~**V2.2.5 必须补**：`complete()` / `abandon()` 要清掉队列里该 `reminderId` 的 pending 追问~~ → **已闭环（V2.2.5）**：新增 `PendingMessageQueue.cancelPendingFollowups(id)`（DAO `cancelPendingByContentPrefix`，`LIKE :prefix || '%'` + `state='pending'`），前缀 `"followup:<id>:"`**结尾冒号不可省**（`id=4` 会误伤 `42`）；由 `ReminderActionHandler` 的 done 分支调用，有 `cancels_only_rows_of_target_reminder` 与变异探针守卫。⚠️ 仅在 `ReminderActionHandler`（点通知按钮）里调；`MoodService` 侧的 `complete()/abandon()`（模型判 completed/abandoned）**仍不清队列** —— 见下方缺口 5。

#### V2.2.5 接缝

`PendingMessageContent.followupOrNull(raw): Followup?`（= `decode(raw) as? Followup`），`Followup(reminderId, attempt, task)`；`ProactiveDeliveryRunner` 里用它取 `reminderId` 挂「已完成 / 再等等」两个按钮。

#### V2.2.5 审查明细（审查线 2026-11-20 22:1x，818 全绿 / 101 suite）

**✅ 通过（逐条对照 §V2.2.5 + §3.9）**

| 契约 | 落地 | 证据 |
|---|---|---|
| §3.9 六字段 | `ProactiveNotification` 只加 `followupReminderId: Long? = null`，未动其它 | `ProactiveDeliveryPorts.kt` |
| 非追问不带追问按钮 | `planFor(followupReminderId == null) → emptyList()` | `no_ids_produces_no_action` |
| 指令仍单「已完成」 | `planFor(memoryId != null, followup = null)` → 1 个 DONE | `memory_id_produces_single_done` + `instruction_content_carries_memory_id_and_no_followup_id` |
| 追问双按钮 | DONE + POSTPONE 两个 action | `followup_id_produces_done_and_postpone` + `followup_content_carries_reminder_id_to_notification` |
| `done` / `postpone` 各只触发对应分支 | handler 单点分流 | `postpone_only_advances` / `postpone_never_deletes_memory` / `done_without_ids_is_ignored` |
| 类名/manifest 不改 | `ReminderDoneReceiver` 原样，只加两个 extra 常量 | `AndroidManifest.xml` L94-96 未动 |
| 图标规格 | 24dp / viewport 24 / `#FFFFFFFF` / 各自 pathData，未复用 `ic_notification` | `ic_check.xml` / `ic_pause.xml` |

**✅ 我额外核的三件事（不在计划书里，但决定这功能真不真能用）**

1. **「点完成后还会不会再响」的两条路都堵了**：`complete()` 管住「未来不再排钟」，`cancelPendingFollowups()` 管住「已入队的那条」。后者是本任务的核心，`FakePendingMessageDao` 用 `startsWith` 对齐 `LIKE :prefix || '%'` 且**不替调用方补尾冒号** —— 否则 §10.3 第 1 条永远绿。我亲自把尾冒号删掉跑了一次：`cancels_only_rows_of_target_reminder` 精确变红（3 tests / 1 failed），还原后复绿。
2. **顺序不可对调**：`complete()` → `cancelPendingFollowups()`。我把两行对调跑了一次：`complete_runs_before_queue_cleanup` 精确变红（7 tests / 1 failed），还原后复绿。理由成立 —— 反过来若 `complete` 失败，链仍 pending 且闹钟还在 → 下次到点又入队 → 无限追问。
3. **`PendingIntent` 覆写风险真的被挡住了**：`getBroadcast` 的复用判据 `(requestCode, filterEquals, flags)` 里 `filterEquals` **不看 extras**，同一 requestCode + `FLAG_UPDATE_CURRENT` 会让第二个按钮覆写第一个的 extras（点「已完成」实际跑 postpone）。实现给了三个独立 offset（100k/300k/400k）+ 不同 `Intent.action` 字符串，双保险；`request_codes_are_unique_within_one_notification` 与 `request_codes_stay_distinct_for_large_ids` 两条守卫。

**✅ 兼容性**（我最担心的静默失效点）

- 旧通知（V2.2.5 之前发出）的「已完成」PendingIntent **没有** `EXTRA_ACTION` → `handle(action = null)` 走 done 分支 → `memoryId > 0` 删记忆、`reminderId == 0` 不碰追问端口。`null_action_is_treated_as_done_for_old_notifications` 钉住。若写成「null 就 return」，升级后用户点旧通知**完全无反应**且无日志。
- 未知 action 字符串 → 返回 false、零动作（不静默当 done），`unknown_action_is_ignored` 钉住。

**⚠️ 三条观察（不阻断，已记录）**

1. **`addAction` 的图标在 API 24+ 不显示**：`NotificationCompat.Builder.addAction(icon, title, pi)` 的 icon 自 Android 7 起被系统通知栏忽略，真机看到的是**纯文字按钮**。两个 vector 已按要求新增、`iconOf` 映射也对，但**没有视觉效果** —— 真机验收时别以为图标丢了。计划书 §V2.2.5 的前置步骤（L953 / §3.9 L441）在这点上属无效工作，后续若要图形按钮得用 `NotificationCompat.Action.Builder` + `RemoteInput` 或自定义 `RemoteViews`。
2. **跨通知 requestCode 理论上可能撞**：`action` 字符串由 `kind` 决定（`REMINDER_done` / `REMINDER_postpone`），**不含 notificationId**；`filterEquals` 又不看 extras。因此当 `msg.id` 恰好相差 200_000 且一条是追问、另一条是指令，两条通知的「已完成」会共用同一个 requestCode + 同一个 action → 后建的覆写前者 → 点旧通知会**删错记忆 / 完成错的链**。实际情况：`msg.id` 全局自增、每天几十条，差 20 万需数年，且两条通知得同时在栏里 —— 概率极低，**本轮不改**。彻底消除只需在 `setAction` 后拼上 `notificationId`（一行），或把 id 编进 requestCode 高位。
3. **「点完成」与「钟到点」的毫秒级竞态**（V2.2.4 归属，本任务使其可感知）：`FollowupChainRunner.tickFollowup` 在 L64 判 `state == PENDING`，到 L90 才 `enqueue`，中间夹着 `scheduleTimeout` + `incrementAttempt` 两次 IO。若用户在这一窗口点「已完成」，会走完 `complete()` + `cancelPendingFollowups()`（队列此时还空），随后那个 tick 把追问入队 → 残留 1 条，15/30 分钟后照样送达。**后果是「点了完成还收到一条」**，不是死链。要修就把 state 复查挪到 `enqueue` 紧前，或 enqueue 后立刻 `cancel`。

**🔴 未发现**（本轮零 🔴）

**真机未验（3 条，待设备）**

1. 追问通知上出现「已完成 / 再等等」两个按钮；指令提醒仍只有一个「已完成」。
2. 点「已完成」后 `dumpsys alarm` 里该 reminder 的三个 code 全消失，且 15/30 分钟内不再有新追问通知。
3. 点「再等等」后按 `followupInterval`（默认 15 分钟）再来一条，且 `attemptCount` 未增加。

**另记（V2.2.2 遗留，与本任务相关）**：`MoodService` 判定 `completed/abandoned` 时调的是 `FollowupPort.complete()/abandon()`，**不清队列**。用户在**对话里**说「我做完了」→ 链停了，但已入队的那条追问仍会在 15 分钟后送达。本任务只解决了「点通知按钮」这条路。修法同一行（`complete()`/`abandon()` 内部调 `cancelPendingFollowups`），但需要给 `FollowupPortImpl` 注入 `PendingMessageQueue`，属 V2.2.2 的改动 —— 记为下一个待办，不要算进 V2.2.5。**这是本轮我认为最值得优先处理的一条残留。**
---

## 1.2.1（2026-11-20 22:2x）追问链收口：从「代码全绿但永不触发」到真能跑

**背景**：进度表显示「第二版 22/22 代码完成、818 全绿」，但按代码核实（不按文档）发现**追问链在真机上完全不存在** ——
`FollowupTaskExtractor` 写好了、10 条单测全绿、`CompanionApp` 也 `by lazy` 出来了，**全项目零调用**。
`reminders` 表永远建不出 followup 行，于是 V2.2.2/2.2.3/2.2.4/2.2.5 四个任务的功能一条都不会发生。

### 改动（4 处代码 + 5 条新测试，全绿 823 / 101 suite）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ui/chat/ChatViewModel.kt` | `generateAssistant` 主对话成功后 `followupTaskExtractor.extractAndSchedule(effectiveConfig, recentForFollowup)`（fire-and-forget，`runCatching`，失败只打 `W/Followup`）；新增常量 `FOLLOWUP_RECENT_MESSAGES = 8` |
| 2 | `data/reminder/FollowupPortImpl.kt` | 构造新增**必填** `queue: PendingMessageQueue`；`complete()` / `abandon()` 改为 `updateState` → `cancelAll` → `cancelPendingFollowups` |
| 3 | `data/reminder/FollowupChainRunner.kt` | `tickFollowup` 入队后立刻复查 `dao.getById`；链已结束则 `cancelPendingFollowups` 撤回本条，返回 false |
| 4 | `data/proactive/AndroidProactiveNotifier.kt` | `setAction` 拼上 `":" + notificationId`，跨通知 requestCode 碰撞不再互覆写 |
| 5 | `ui/speech/VoiceInputActivity.kt` | `onPause` 先判 `transcribing = !recording.value && busy`，为真则 `super.onPause()` 后直接 return |
| 6 | `CompanionApp.kt` | `followupPort` 装配补 `queue = pendingMessageQueue` |

### 新测试（5 条，全部钉行为而非钉实现）

- `FollowupPortImplTest.complete_cancels_only_queued_followups_of_this_chain` —— 42 不能杀 `followup:420:…`（尾冒号守卫，用真实编码的队列行）
- `FollowupPortImplTest.abandon_cancels_queued_followups_of_this_chain`
- `FollowupPortImplTest.complete_writes_state_then_cancels_alarms_then_clears_queue` —— 三方共用 order 流水，断言 `updateState → cancel:×3 → cancel(队列)`
- `FollowupPortImplTest.complete_does_not_clear_queue_when_state_write_fails` —— 落库抛异常时队列必须原样（否则「链仍 pending + 闹钟仍在 + 队列已空」= 下次到点又入队，无限追问）
- `FollowupChainRunnerTest.tick_withdraws_just_enqueued_followup_when_chain_ends_mid_flight` —— DAO 第 2 次 `getById` 翻成 DONE，精确模拟「点完成」与「钟到点」的毫秒级窗口

### 坑（这轮踩到 / 差点踩到）

1. **`FollowupPortImplTest` 的顺序断言被播种数据污染**：断言 `updateState` 打头，实际首项是播种时那次 `enqueue`。断言要用完整期望列表，不能只挑三段。
2. **给共用 fake 加钩子会连坐**：`FakeReminderDao` 被 V2.2.1/V2.2.4 多个用例共用，往它的 `updateState` 里加 order 流水会改动别人钉死的序列。改用**接口委托**（`class Hooked : ReminderDao by base`）只包一层，零影响其它用例。
3. **`busy` 在本项目里是「一次性」标志**（`finishRecording` 置 true 后不再复位）：所以 `onPause` 里 `!recording && busy` 恰好只覆盖「已经 stop、正在转写」这一段，不会误伤「还没开始录就退出」那条（那时 `busy == false` 仍走 `finish()`）。
4. **不要在 `tickFollowup` 里改成「enqueue 前复查」**：那只是把窗口挪小、仍然漏。必须**入队后再查一次**并撤回，才能与 `complete()` 自带的清理两头对接（它先清我后写 → 我撤；我先写它后清 → 它扫到）。

### 版本

- `versionCode 23` / `versionName 1.2.1`；APK `dist\陪聊-companion-v1.2.1.apk`（135.5 MB，SHA-256 `09A613BC3127488C6175415251B0447A084C26BD8BB9676364D57026816C918E`）
- 单测 **823 / 101 suite / 0 失败**
- **未装机**：当时 `adb devices` 为空（线未插），属正常

---

## 1.2.2（2026-11-20 22:4x）追问 start 提示支持相对时长 + 「今晚8点」解析修复

**触发**：真机首测追问链时用户说「1分钟后开始，每5分钟问一次」，结果 5 分钟才响。
拉 DB 看到 `triggerTime = now + 5min`（不是 +1min），`start` 字段被**静默忽略**。

**根因**：`FollowupTaskExtractor.resolveTriggerTime` 只调 `TimeAnchorConverter.parseComponents`，
而它**只认「日期 + 钟点」**；「1分钟后」这种相对时长没有钟点 → 返回 null → 悄悄退回 `now + intervalMinutes`。
（提醒链 `ReminderParser` 早在任务 3.7 就有「N分钟后」的正则，但那段是 `private`，followup 用不到。）

### 改动

| # | 文件 | 改动 |
|---|---|---|
| 1 | `domain/alarm/ReminderParser.kt` | 把「文本 → 相对时长」公开：`relativeOffsetMillis(text)`（纯函数，无时间基准）+ `relativeTriggerAt(text, nowMillis)`；内部抽 `matchToMillis`，原 `parseRelativeOffset` 改为复用它（行为零变化） |
| 2 | `data/reminder/FollowupTaskExtractor.kt` | `resolveTriggerTime` 三级优先：**① 相对时长**（`ReminderParser.relativeTriggerAt`）→ ② 绝对时刻（原有 parseComponents 分支）→ ③ `now + interval` 兜底 |
| 3 | `domain/reminder/FollowupPrompt.kt` | `start` 字段说明改成「两种写法都接受」：相对（1分钟后/半小时后/2个小时后）+ 绝对（今晚8点/明天早上8点/7点30分），并禁止输出「不知道啥时候」这类无意义值 |
| 4 | `domain/memory/TimeAnchorConverter.kt` | **顺手修掉一个真 BUG**：`findTime` 的前缀候选里没有「今晚/明晚/今早/明早」，`今晚8点` 只能从「8点」处命中 → 前缀为空 → 不 +12 → **解析成早上 8 点**（若已过点则被判无效）。而 `FollowupPrompt` 的示例恰恰写的「今晚8点」，等于把用户往坑里带。补前缀 + `pm` 集合 + `DAY_WORDS` 加 `明晚|明早 → +1`、`昨晚 → -1` |

### 新测试（7 条，830 全绿 / 101 suite）

- `FollowupTaskExtractorTest`：`1分钟后` → `now+60s`（**不是** `now+interval`，这条就是本次事故的回归守卫）；`半小时后`；`3个小时后`；`今晚7点30分` → **19:30**（相对解析不得吞掉绝对时刻）
- `ReminderParserTest`：`relativeOffsetMillis` 覆盖 1分/半时/2小时/3个小时/十分钟 + 无时长返回 null（今晚7点30分、明天早上8点、不知道啥时候、0分钟后）+ `relativeTriggerAt` 加基准

### 版本与产物

- `versionCode 24` / `versionName 1.2.2`；`dist\陪聊-companion-v1.2.2.apk`（135.5 MB，SHA-256 `E0C6406C9574CBBEC15C70930DB68B8EC64A187DD1F1E21F9BCABDE7CF3ACA30`）
- 已装机 22:43:49（`-r` 保留数据），冷启动 1230ms、无 FATAL
- **真机旁证**：1.2.1 那轮用户点了「再等等」→ 22:38:35 撤 TIMEOUT 并 +5min；22:43:35 发出第 2 条（`attemptCount=2`）；22:43:45 点「已完成」→ 三个钟全撤、`state=done`。**V2.2.5 两个按钮在真机上首次实测通过**

---

## 1.2.4（2026-11-20 23:0x）顶栏铃铛一键勿扰：从 TODO 空壳到真能生效

**触发**：用户实测「右上角那个铃铛就是个摆设，点了不能开启勿扰模式」。
核实：`MainActivity` 里就是 `onDndClick = { /* TODO: 勿扰模式（任务 3/7） */ }` —— v1 起从未接线。

### 语义（必须定死，否则「点了没用」会换个形式复发）

现有勿扰是**区间制**（`dnd_enabled` + 起止分钟，默认 23:00–07:00）。白天点「开启」如果只翻开关，
因为此刻不在区间内，**什么都不会发生** —— 等于还是没用。所以定为：

| 场景 | 行为 |
|---|---|
| 区间**外**点开启 | 把 `dnd_start_minute` 改成**当前分钟**（`end` 不动，自动跨午夜）→ 立刻静音到用户配的终点；同时把原起点写进快照 `dnd_saved_start_minute` |
| 区间**内**点开启 | **只翻开关**，不改起点（改起点会把区间拉长） |
| 点关闭 | 关开关；有快照就**还原**原起点并清掉快照 |
| 用户从设置页保存作息 | `clearDndSavedStart()` —— 用户自己定过作息，铃铛的临时快照作废 |

### 改动

| # | 文件 | 改动 |
|---|---|---|
| 1 | `domain/settings/QuickDnd.kt`（新） | `plan(...)` 纯函数返回 `Result(enabled, startMinute?, savedStartMinute?, clearSavedStart, toast)`；区间判定**复用** `ProactiveGateRules.inQuietHours`（跨午夜规则只有一份） |
| 2 | `data/prefs/ConfigRepository.kt` | 新增快照三件套 `getDndSavedStartMinute / setDndSavedStartMinute / clearDndSavedStart`（key `dnd_saved_start_minute`） |
| 3 | `ui/MainActivity.kt` | `onDndClick` 真实实现（算 plan → 落库 → 更新 state → Toast）；`chatDndEnabled` 用 `mutableStateOf`（图标要立刻变）；设置页保存处清快照 |
| 4 | `ui/chat/ChatScreen.kt` | 新增 `dndEnabled` 参数；铃铛图标在 `Notifications` / `NotificationsOff` 间切换并着色（开启=primary），`contentDescription` 也随之变 |

### 新测试（9 条，842 全绿 / 102 suite）

`QuickDndTest`：区间外开→起点挪到现在+写快照+文案「明天 07:00」；「今天 23:30」分支；区间内只翻开关；凌晨 06:00 属区间内；关闭还原快照；无快照时不动区间；二次开启不覆盖快照；起点==终点的空区间要提示去设置里改；`minuteOfDay` 边界。

**写测试时踩到的坑**：拿「临时起点 == 现在」去测「二次开启不覆盖快照」是错的 ——
那种状态下 `inQuietHours` 判定为区间内（`nowMinute >= startMinute`），走的是另一条分支，
断言会莫名其妙变红。测这条必须用**不跨午夜**的窗口 + 与现在无关的起点。

### 真机实测（adb input tap）

- 点 1 次：`dnd_enabled true→false`，`dnd_start_minute` 1380 不变（当时 23:07 在区间内，走「只翻开关」分支）✓
- 点 2 次：`dnd_enabled false→true` ✓，图标 `content-desc` 在「勿扰已开启，点按关闭」/「勿扰已关闭，点按开启」间切换 ✓
- `versionCode 26 / versionName 1.2.4`，已装机 23:0x，冷启动 1404ms

---

## 1.2.6（2026-11-20 23:2x）追问链去重键：从「全局一条」放宽到「同一任务一条」

**触发**：用户 23:17 发「从现在起监督我喝水，一分钟后开始，每一分钟问一次」，到 23:21 一条提醒都没来。

**根因**：`FollowupTaskExtractor` 的廉价预检是 `dao.anyPendingFollowup()` —— **全局**任意一条 pending 链就
零成本返回。当时「背30个单词」链（id=2）还活着，于是「喝水」被**静默丢弃**：无日志、无提示，
而模型已经在对话里答应「一分钟后开始，每分钟问一次」（消息 14）。用户只能干等。
DB 佐证：`reminders` 只有 id=1/2/3，**没有喝水行**；23:17:10 那条 `setAlarmClock：追问` 是 id=2 的推进（→23:22:10）。

**改动**

| # | 文件 | 改动 |
|---|---|---|
| 1 | `data/local/dao/ReminderDao.kt` | 新增 `pendingFollowupByTask(task)`：`state='pending' AND type='followup' AND taskDesc = :task`（无新列 → **不需要迁移**） |
| 2 | `data/reminder/FollowupTaskExtractor.kt` | 删掉锁外的 `anyPendingFollowup` 预检（判重需要先知道任务名）；锁内判据改为 `pendingFollowupByTask(ex.task)`。类注释与步骤编号同步 |
| 3 | `test/.../FakeReminderDao.kt` | 实现新查询（精确匹配 `taskDesc`） |
| 4 | `FollowupTaskExtractorTest` | `SlowDao` 的挂起点改到新查询上；原「零调用」用例改成「同任务被拦（模型仍被调一次）」；新增 `different_task_creates_a_second_chain` |

**代价（已接受）**：以前「已有链 → 零成本返回」，现在每条监督类消息都会**调一次模型**。
换来的是「不再静默丢请求」。（意图闸门仍在最前，普通闲聊不调模型。）

**已知限制**：`FollowupPort.active()` 仍只返回一条，所以 `MoodService` 的「说完成/放弃」只在**最早那条链**上生效；
并行链的另一条靠 5 次上限自动收尾。多链的语义判定留待后续。

**版本**：`versionCode 28 / versionName 1.2.6`，单测 **843 / 102 suite / 0 失败**，已装机 23:2x（冷启动 1319ms）

---

## 1.2.7（2026-11-20 23:3x）多链并存的「说完成」判定：从「只认最早一条」到「按 id 逐条分派」

**背景**：1.2.6 允许不同任务各建一条链（背单词 + 喝水并存），但 `MoodService` 仍只打量 `FollowupPort.active()`
（最早那条），所以「我喝完了」只会作用在最早那条链上，另一条白催到 5 次上限。

### 改动

| # | 文件 | 改动 |
|---|---|---|
| 1 | `domain/reminder/FollowupPort.kt` | 主入口改成 `activeAll(): List<ActiveFollowup>`；`active()` 变**默认实现**（`activeAll().firstOrNull()`），老调用点不必改 |
| 2 | `data/local/dao/ReminderDao.kt` | 新增 `activeFollowups()`（同判据、去掉 `LIMIT 1`）；`activeFollowup()` 保留给单链调用点 |
| 3 | `data/reminder/FollowupPortImpl.kt` | 实现 `activeAll()`（映射 DAO 列表） |
| 4 | `domain/mood/MoodPromptBuilder.kt` | `build(recent, mood, activeFollowups)` 逐条列 `id= / 任务 / 停止条件`；`system(withFollowup=true)` 的输出格式改为 `"followups":[{"id":1,"status":"…"}]`，并明确要求 **id 原样照抄、每条都要给** |
| 5 | `domain/mood/MoodUpdate.kt` | `MoodUpdate` 新增 `followupStatuses: Map<Long, FollowupStatus>`；解析 `followups` 数组（缺 id / id<=0 / status 不认识 / 元素非对象 → 跳过该条，不影响 mood；重复 id 取第一条）；**旧 `followup_status` 仍解析** |
| 6 | `data/mood/MoodService.kt` | 按 `followupStatuses[id]` 逐条 `complete/abandon/advance`；模型漏 id → 该条 `advance`（不重排就是永久卡死）；**旧单值格式只在仅 1 条链时采信**，多链时不瞎套 |

### 新测试（9 条 → 852 / 102 suite 全绿）

- 解析：`followups` 数组 → id 映射；坏元素跳过；重复 id 取首个；旧 `followup_status` 仍可用
- 提示词：多链版本列出每个 `id=` 与「N 个待追踪的任务」；`system(withFollowup=true)` 含 `followups`/`"id"`
- 分派：两条链 → `complete:3` + `advance:8`；模型漏 id → 该条 advance；**旧格式 + 两条链 → 一律 advance（不瞎套）**；旧格式 + 一条链 → 照常 `complete`

### 版本

`versionCode 29 / versionName 1.2.7`，已装机 23:3x（冷启动 1552ms）。真机「两条链 + 说喝完了」待用户验收。

---

## 1.2.8（2026-11-20 23:4x）点「已完成」→ 顺手清掉这条提醒的短期记忆

**诉求**（用户）：「喝水提醒点击已完成就从短期记忆里删掉吧，不然短期记忆里全是已经结束了的提醒」；
追加：「监督也是一样，完成了就删掉」。

### 真机数据推翻的第一个方案

`memory_short` 里**没有** `followup:<id>:<task>` 编码，也没有 reminderId 列。实际内容（23:40 实拉）：

| id | content | 来源 |
|---|---|---|
| 1 | 用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始） | MemoryExtractor |
| 2 | 现在我要背单词，提醒我。每隔5分钟提醒一次 | handleInstruction（= taskDesc 原文） |
| 3 | 用户要求1分钟后提醒喝水，之后每隔1分钟提醒一次 | MemoryExtractor |
| 4 | 喝水，每隔1分钟提醒我一次 | handleInstruction |

两类来源措辞都对不上 taskDesc，按 id/前缀删必漏。→ 改为**内容相似度判定**。

### 改动

| # | 文件 | 改动 |
|---|---|---|
| 1 | `domain/reminder/ReminderMemoryMatcher.kt`（新） | 纯函数 `shouldClear(memoryContent, task)`：归一化后互相包含 → 删；否则**剥掉停用词**（提醒/每隔/分钟/开始/…）再判包含；仍不中则**最长公共子串 ≥2** |
| 2 | `data/reminder/FollowupPortImpl.kt` | `complete()`/`abandon()` 末尾调 `deleteFollowupMemory(id)`：取 taskDesc → `getAll()` 过滤 `shouldClear` → 逐条 `deleteById`（runCatching，清理失败不阻断停链） |
| 3 | `CompanionApp.kt` | `followupPort` 装配补 `memoryShortDao = database.memoryShortDao()` |
| 4 | `data/reminder/ReminderActionHandler.kt` | 回退上一版「按前缀删」的尝试：删记忆统一由 `FollowupPort.complete()` 做（对话里说「做完了」与点通知按钮**同源**） |
| 5 | `domain/reminder/FollowupPort.kt` | 新增 `taskOf(reminderId): String?`（供清理取任务名） |
| 6 | `MemoryShortDao` | 一度加过 `deleteByContentPrefix` 后又移除（方案变更后无用，不留死代码） |

**为什么必须剥停用词**（真机模拟踩到）：长任务名「现在我要背单词，提醒我。每隔5分钟提醒一次」与喝水记忆的公共片段只有「分钟提醒」，不剥词会误删别的主题。剥离后前者只剩「要背单词5个」：与喝水记忆无交集，与「…提醒一次背单词…」仍靠「背单词」命中。\n\n**真机数据模拟**（用实际 4 行记忆 + 历史 taskDesc 跑同一规则）：`喝水`→删[3,4]；`背30个单词`→删[1,2]；长任务名→只删[1,2]；`吃药`→[]。

### 测试（863 / 102 suite 全绿）

- `ReminderMemoryMatcherTest`(6)：原文精确/包含；提取器改写靠 LCS 命中；无关记忆（咖啡/升职/喝了热水）不误删；**长任务名不得经停用词误删别的主题**；空串；标点归一化
- `FollowupPortImplTest`：complete/abandon 各删本主题记忆、不误删无关主题；查不到 taskDesc 时不动记忆；`memoryShortDao = null` 时不崩

**未覆盖**：指令提醒（`instr:`）点「已完成」仍走既有的 `memoryId` 精确删除；由 `MemoryExtractor` 为**指令类**提醒写的改写记忆不会被这一步清掉（它没有 reminderId 可关联）。追问/监督类已覆盖（用户当前场景）。

### 版本

`versionCode 30 / versionName 1.2.8`，已装机 23:4x（冷启动 1423ms）。

---

## 1.2.9（2026-11-20 23:5x）记忆页「日志」→「日记」

用户反馈：「那个『日志』改成『日记』是不是会更准确」。判断：**记忆页**那个 tab 展示的是 `memory_daily`
（每天发生的事件流水，如「去了公司」「面试通过」），在陪聊语境里叫「日记」更贴用户认知；
**设置页的「查看执行日志 / 保活执行日志」不动** —— 那是 root 命令输出，叫日记就荒唐了。

改动（`ui/memory/MemoryScreen.kt` 四处文案）：tab「日志」→「日记」、空提示「还没有每日日志」→「还没有日记」、
对话框标题「新增每日日志 / 编辑每日日志」→「新增日记 / 编辑日记」、文件头注释同步。
DB 表名/字段名（`memory_daily`、`events`）**未改**。

`versionCode 31 / versionName 1.2.9`，863 测试全绿。
---

## 1.3.0（2026-11-21 00:0x）短期记忆容量淘汰（50/20）+ 跨午夜时钟 bug 修复

**诉求**（用户）：「短期记忆攒满 50 条就自动清理淡忘分高的 20 条不在提醒/监督的记忆，这样比较符合人脑遗忘」。
拍板参数：50 / 20、保护 24 小时内新写入、被「别提这个」拉黑的**参与**淘汰。

### 新增

| # | 文件 | 作用 |
|---|---|---|
| 1 | `domain/memory/ShortMemoryCapacity.kt`（新） | 纯函数 `plan(all, protectedTasks, nowMillis, max=50, evict=20, protectWindow=24h)`：未满不动 → 排除保护窗口 → 排除活跃任务 → 按 `fadeScore` 降序、时间升序取 20 |
| 2 | `data/memory/MemoryCapacityEnforcer.kt`（新） | 读 `memory_short` + `reminders(pending, instruction/followup)` → 调 plan → 逐条 `deleteById`；全程 runCatching + W 级日志 |
| 3 | `ui/chat/ChatViewModel.kt` | `memoryExtractor.store(ex)` 之后立刻 `enforceOnce()`（写入后触发，不做定时扫描） |
| 4 | `worker/ReminderAlarmReceiver.kt` | `handleInstruction` 投递后同样触发一次 |
| 5 | `CompanionApp.kt` | `memoryCapacityEnforcer` 装配 |

**为什么保护判定复用 `ReminderMemoryMatcher`**：与「点完成就删该任务记忆」用同一套内容相似度规则，避免两套判定互相打架（否则会出现「完成时删掉了、容量清理又当成活跃任务保护」的矛盾）。

### 顺手修掉的既有 bug（跨午夜才暴露）

`EnvironmentHookService.weatherBundle()` 原来用 `LocalDate.now(zone)` 取「今天」，而**去重键**用注入时钟
`dateKeyOf(nowMillis(), …)`。两者一旦跨午夜不一致，就会「天气按 21 号取、去重键按 20 号算」。
真机上表现为：**凌晨之后天气钩子静默失效/重复**。测试里更直接 —— 0 点后跑 `testDebugUnitTest`，
注入时间仍是 11-20 10:00，却去查 11-21 的天气 → 假数据没有那天 → 5 条用例连红。

修法：`weatherBundle()` 改用 `Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()`，
与去重键同源。测试数据（`rainyJson` / `sunnyJson`）补上 11-21 一天，覆盖「跨日后同一钩子可重发」用例。

### 测试（875 / 105 suite 全绿）

- `ShortMemoryCapacityTest`(7)：未满不删、满 50 删 20（按 fadeScore 降序）、24h 窗口保护、活跃任务保护、同分按旧优先、拉黑项参与淘汰、全被保护时零动作
- `MemoryCapacityEnforcerTest`(5)：达到阈值真的删 20、未达阈值零动作、活跃 followup 任务被保护、已 DONE 的提醒不再保护、DAO 抛异常时返回 0 且留日志

### 版本

`versionCode 32 / versionName 1.3.0`，已装机 00:07（冷启动 1418ms）。当前库里只有 4 条记忆，短期内不会触发淘汰。

---

## 1.3.1（2026-11-21 00:2x）三类记忆接进主对话（按 token 预算截断）

**背景**（用户）：「短期/长期/日记三类记忆现在都没接进主对话，这笔钱纯白花」——
记忆提取每轮都在调模型花钱，但上下文里从来没用上。

### 设计（三个决定性的点）

1. **记忆块放尾部，不进稳定前缀**：`ContextBuilder` 新增 `memoryBlock`，插在「历史之后、本轮 user 之前」。
   放进顶部 system 会让 DeepSeek 的前缀缓存整段失效 —— 省下的 token 会成倍吐回去。
2. **相关性用本地向量，不再调模型**：`MemoryContextProvider` 用 bge-small-zh（去重已在用）算余弦，
   阈值 0.45（2026-11-21 由 0.35 上调）；每轮多花的是几十 token，不是一次 API 调用（那会又变成「白花钱」）。
3. **按固定优先级砍预算**：超预算时「日记 → 短期 → 长期」整段丢弃（长期记忆最稳、最不该丢）。

### 改动

| # | 文件 | 内容 |
|---|---|---|
| 1 | `domain/memory/MemoryContextBlock.kt`（新） | 纯函数：短期过阈值取 4、长期去重取 12、日记取近 3 天；无内容或预算不足 → `null`（**一个字都不注入**）；拼装带「相关才提、别罗列」的指令 |
| 2 | `data/memory/MemoryContextProvider.kt`（新） | 读三张表 → 算短期相似度（优先用行内已存向量，缺了现场算）→ 交给纯函数。embedding 未就绪 → 短期不注入，长期/日记照常；任何异常 → null |
| 3 | `domain/ContextBuilder.kt` | 新增 `memoryBlock` 参数，插在历史与 user 之间 |
| 4 | `domain/ContextTrimmer.kt` | **行为变更**：只钉**第一条** system（人设+能力），其余 system 参与正常裁剪 —— 否则记忆块会被提到人设前面，破坏顺序与前缀缓存 |
| 5 | `ui/chat/ChatViewModel.kt` | build 前取记忆块（预算 = 窗口 × 12%）；`init` 里预热 embedding（否则首轮现场加载 1~2s 卡首字）；W 级日志 `MemoryCtx 本轮注入记忆块 N 字` |
| 6 | `CompanionApp.kt` | `memoryContextProvider` 装配（用 `embeddingServiceOrNoop()`，不触发加载） |

### 参数（常量，不放进设置页）

短期 4 条 / 阈值 0.45 / 长期 12 条 / 日记 3 天 / 总预算窗口 12%（默认 8192 → 约 980 字）。

### 测试（888 / 107 suite 全绿）

- `MemoryContextBlockTest`(9)：全空 → null；短期低于阈值/null 不注入；超 4 条截断；长期去重+上限；日记 3 天且过滤空行；**超预算按「日记→短期→长期」砍**（这条抓出一个真实 bug：用 `removeLast` 会先砍掉长期，与设计相反，已修正）；header 必带「别罗列」指令
- `MemoryContextProviderTest`(6)：相关短期注入 / 不相关不注入；embedding 未就绪时降级（长期+日记仍进）；全空 → null；空输入跳过短期；DAO 异常 → null 不抛

### 版本

`versionCode 33 / versionName 1.3.1`，已装机 00:23（冷启动 1434ms）。

---

## 1.3.2（2026-11-21 00:3x）日记 events 从 JSON 原文改为展平句子

真机发现：`memory_daily.events` 存的是 **JSON 数组字符串**（`["…","…"]`），1.3.1 原样注进了上下文 ——
模型能读，但引号逗号占预算又难看，一条日记能吃掉上百字。

改法：`MemoryContextBlock.readableDailyEvents(raw)` —— 解析 JSON 数组 → 「；」连接；
单条截断 **60 字**（防一条长流水吃光预算）；解析失败（脏数据/普通文本）→ 原样保留，绝不让注入失败。
函数改为 `public` 以便 `MemoryContextProvider` 用**展平后**的文本算相似度（与最终注入文本一致）。

测试 +3：JSON 展平、单条截断、非 JSON 原样。
`versionCode 34 / versionName 1.3.2`，891 测试全绿。
---

## 1.3.3（2026-11-21 00:4x）记忆块瘦身：提示语压缩 + 日记改为「相关才注入」

**背景**：1.3.1/1.3.2 实测每轮注入 **381 字**（≈381 token），用户问「这消耗多少」，拆开看：
提示语 69 字（18%）+ 短期 96 + 长期 80 + 日记 133（35%）。用户拍板砍第 1、2 项。

### 改动

| # | 改动 | 效果 |
|---|---|---|
| 1 | `HEADER` 从 69 字压到 **27 字**：「【我记得的事】相关就自然提一句，不相关别硬提、别罗列。」 | 每轮固定省 42 字 |
| 2 | 日记改为**按相似度过滤**（与短期同阈值 0.45）：`DailyEntry` 新增 `score`；`MemoryContextProvider` 用展平后的正文算余弦（与最终注入文本一致） | 闲聊轮次整段省 133 字 |

**埋点口径**：日记的相似度用 `readableDailyEvents` 展平后的文本算 —— 直接拿 JSON 原文算会被引号逗号稀释语义。

**降级语义（重要）**：embedding 未就绪时**短期与日记都不注入**（无法判相关性），长期照常。原先「未就绪也注日记」与其矛盾，已统一。

### 实测（用真机当前 4 短期 + 5 长期 + 1 天日记）

| 场景 | 字数 | 相比 381 |
|---|---|---|
| 改后 · 日记相关 | **339** | −42 |
| 改后 · 日记不相关（闲聊轮） | **205** | −176 |

### 测试（893 / 107 suite 全绿）

新增/更新：`daily_below_threshold_is_not_injected`、`header_is_compressed_to_save_tokens`（钉 ≤30 字且保留「相关/别罗列」语义）、provider 两条改为「日记需相关」「未就绪时日记也不注入」。

### 版本

`versionCode 35 / versionName 1.3.3`，已装机 00:4x。

---

## 1.4.2（2026-11-21 02:0x）情绪系统 P2：想你 / 长度 / 每日上限 / 通知标题

在 P1（1.4.0，情绪状态 + 主对话注入 + 颜文字）之上补齐主动消息侧。

| # | 能力 | 实现 | 关键约束 |
|---|---|---|---|
| 1 | **「想你」** | `domain/emotion/MissingEmotion.kt` 曲线：<2h 不产生；2~6h 0.30→0.50；6~24h →0.70；>=24h 封顶 0.70 | **只在主动消息路径**（`MoodService.emotionsWithAwayBoost()`，由主动消息生成的 `emotionProvider` 调用）。聊天时绝不会莫名黏人 |
| 2 | **长度随情绪** | `ProactivePromptBuilder.system(persona, mood, emotions)`：兴奋→「可以多说两句（2-3 句）」；想你→「只说一句，轻一点」；低落/烦躁/委屈→「只说一句，短一点」 | 默认「1-2 句」保持不变，旧断言零改动 |
| 3 | **每日上限微调** | `domain/proactive/EmotionProactivePolicy.limitBonus`：兴奋/想你 ≥0.6 → +1；低落/烦躁 ≥0.6 且无正面 ≥0.6 → -1；夹在 0..10 | **不碰勿扰/冷却/回复率** —— 情绪不能绕过勿扰。闸门 `configProvider` 是非 suspend，故用 `@Volatile` 缓存（MoodService 每次落库时刷新） |
| 4 | **通知标题** | `domain/proactive/EmotionNotificationTitle`：兴奋/开心「有件事想跟你说」、想你「你在忙吗」、低落「说两句」、烦躁「喂」、委屈「在吗」、心虚「那个…」；强度 <0.5 回落默认「陪你聊聊」 | 只做语气提示，不剧透、不撒娇；在送达层算（suspend 上下文），notifier 保持纯渲染 |

**测试**：+16 条（`MissingEmotionTest` 4 / `EmotionProactivePolicyTest` 5 / `EmotionNotificationTitleTest` 3 / 提示词 2 / `MoodServiceEmotionTest` +2），全量 **953 / 116 suite / 0 失败**。

**真机状态快照**（P1 生效证据）：
```
emotion_state = {"his":{"mood":"happy","intensity":0.7},
                 "hers":{"emotions":{"happy":0.7,"annoyed":0.26,"proud":0.5},
                         "trigger":"用户连夸我厉害可爱"}}
```

**版本**：`versionCode 38 / versionName 1.4.2`，已装机（冷启动 1388ms）。

---

## 1.5.0（2026-11-21 11:3x）情绪系统 P3：关系阶段 / 撒娇开关 / 情绪进长期记忆

| # | 能力 | 实现 | 关键约束 |
|---|---|---|---|
| 1 | **关系三档** | `domain/emotion/RelationshipStage.kt`：亲近 = 消息≥200 **且** 认识≥3 天；熟悉 = 消息≥30 或 第3天；否则生疏 | 只看全库统计（消息数 + 首条消息时间），**零迁移**（只加两条 SELECT） |
| 2 | **撒娇开关** | `renderEmotionBlock` 里 `allowsAffection(stage)` = 仅「亲近」。生疏/熟悉走 P1 的「语气软一点」 | 撒娇=关系奖励，不是默认行为；`EmotionPhrase.ALLOW_AFFECTION` 留作全局 kill switch（现为 true） |
| 3 | **想你门槛随关系** | `missingFloorHours`：亲近 1h / 熟悉 3h / 生疏 6h | 越亲近越早开始想，但强度上限仍 0.7 |
| 4 | **情绪进长期记忆** | `EmotionMemory`：只在亲近 + 强度≥0.6 + 有 trigger；只记 **开心/得意/想你/委屈**（烦躁/低落/心虚永不写）；`category=relationship`；**最多 3 条**、同一天同一件事只写一条；第一人称（「2026-11-21 你连夸我厉害可爱，我当时开心。」） | 长期记忆是每轮全量注入的，不限量会把上下文挤成回忆录 |
| 5 | **关系定位** | `ContextBuilder(relationshipNote=…)` 写进**稳定前缀**（几个月才变一次，几乎不影响缓存） | 生疏「保持礼貌和分寸」/ 熟悉「可以开点小玩笑」/ 亲近「可以自然地说想他、撒点娇，但别腻」 |
| 6 | **设置页** | 「你们的关系：熟悉（已聊 28 条，第 1 天）（决定她能不能撒娇）」 | 数字透明，便于观察升级 |

**测试**：+18 条（`RelationshipStageTest` 6 / `EmotionMemoryTest` 5 / `MoodServiceP3Test` 5 / `ContextBuilderTest` +2），全量 **971 / 119 suite / 0 失败**。

**真机验证**（1.5.0）：
- 设置页显示 `你们的关系：生疏（已聊 28 条，第 1 天）` —— 28 条 < 30 且未满第 2 天，判定正确
- 跨夜衰减实测：昨晚的 `happy 0.7 / proud 0.5` 到上午 11 点已衰减到 0（`她的情绪：平静`），说明 `0.5^(Δ/30min)` 在真实时间上成立

**版本**：`versionCode 39 / versionName 1.5.0`。

---

## 1.5.1（2026-11-21 11:5x）P3.1：热络度（关系会淡，但不会忘）

**背景**：用户希望"太久不聊关系会淡"。直接让「关系阶段」掉级会出现 OOC（记忆里还写着"你夸我我很开心"，嘴上却说"我们刚认识"）。所以**拆成两个量**：

| 量 | 语义 | 是否衰减 | 决定什么 |
|---|---|---|---|
| **关系深度 depth** | 聊了多久、多深（消息数 + 认识天数） | ❌ 只升不降 | 撒娇的**资格**（亲近才有） |
| **热络度 warmth** | 最近聊得勤不勤（0~1） | ✅ **14 天半衰**，下限 0.05 | 此刻多亲：撒娇浓度、要不要收着点 |

- 存 `app_state[warmth]` + `[warmth_updated_at]`，**零迁移**；读取时按 `0.5^(天数/14)` 现算（与情绪同一套思路：衰减不靠定时任务）
- 每完成一轮对话 **+0.12**（约 2~3 轮从 0.35 回到 0.6），封顶 1.0；默认 0.5（老用户升级不会一睁眼被判"生分"）
- **撒娇门槛 = 关系亲近 且 热络度 ≥0.5**（两个条件）
- 偏冷（<0.5）时注入块补一行：`【热络度】有点生分（0.35）：别太亲昵、别撒娇，先随便聊聊。`；热络时**一个字都不加**
- 设置页新增两行 + 一行备注：
  ```
  你们的关系：生疏（已聊 28 条，第 1 天）（决定她能不能撒娇）
  当前热络度：0.5（还行）
  备注：关系 = 你们聊了多久、能不能撒娇（只升不降）；热络度 = 最近聊得勤不勤（久不聊会变淡，但关系不会退）。
  ```

**为什么必须有那行备注**：两个词都像"关系"，并排放着不解释必然被问"这不矛盾吗"；而且它专门解释最容易像 bug 的现象——**亲近却不撒娇**。静态文案、零 token。

**测试**：+9 条（`WarmthTest` 6 / `MoodServiceP3Test` +3），全量 **998 / 122 suite / 0 失败**。

**真机验证**（1.5.1）：设置页四行全部正确显示（见上）。

---

> 以下 **1.3.6 / 1.3.7** 由另一条实现线于 2026-11-21 12:0x 补充（该线测试基线 **998**）。

## 1.3.6 / 1.3.7（2026-11-21 12:0x）「从昨晚到现在一条主动消息都没发」的根因与修复

用户报告：昨晚之后 12 小时没收到任何「随机关心」。查下来是**两层原因叠加**。

### 根因 1（真 bug）：周期任务被「改系统时间」冻死在 2027 年

- 2026-11-20 22:47 用户为测节日把系统时间改成 **2027-02-14 22:47**；App 恰在这时排了 2h 周期任务，
  WorkManager 的 `WorkSpec.last_enqueue_time` 就冻结在 `2027-02-14 22:47:05`。
- 22:50 时间改回 2026-11-20 → WorkManager 认为下次运行 = **2027-02-15 00:47**（3 个月后）。
- 且用 `ExistingPeriodicWorkPolicy.KEEP`（存在即忽略），**永远不会自我纠正**。
- `memory_decay_daily` 同样被冻（每日淡忘也停）。

**修复**：

| 改动 | 文件 |
|---|---|
| `WorkScheduleHealth.needsReschedule(last, now, period)`：无记录 / 时钟回拨 / 超 3 个周期 → 需重排 | `domain/work/WorkScheduleHealth.kt`（新） |
| 启动自愈：读 `proactive_scheduled_at_v2` / `memory_decay_scheduled_at`，按需重排 | `CompanionApp.ensureWorkSchedulesHealthy()` |
| 真机实测 `ExistingPeriodicWorkPolicy.REPLACE` **静默不生效**（旧 WorkSpec 原样留着），改为「`cancelUniqueWork().result.get(10s)` 同步等待 + KEEP 入队」 | `scheduleProactiveCheck(replace)` |
| 整点兜底：每小时 ENV 闹钟读 `proactive_last_run` 心跳，超 3h 就补跑一轮 | `ReminderAlarmReceiver.handleEnvCheck` |
| Worker 每次真跑写心跳 | `ProactiveCheckWorker.doWork` |

验证（workdb 含 WAL）：`proactive_check_v2_2h last=11-21 11:54:52 runs=1`，日志 `已取消旧周期任务（等待完成）` → `周期检查开始` → 立即执行。

### 根因 2（设计缺陷）：短期记忆为空 → 永远 NO_HOOK

修好排程后检查立刻跑了一轮，结果 `outcome=NO_HOOK`：`memory_short` 表 **0 条**（长期 4 条、日记 2 天都在）。
「随机关心」唯一话题源是短期记忆召回 → 记忆一空就一条都不发，而且用户分不清「没话题」还是「坏了」。

**修复（1.3.7）**：`ProactiveCheckRunner` 新增 `recallFallback`，短期为空时依次：

1. **长期记忆**（每条 **7 天内不重复**，`proactive_long_used` 记 CSV `id:ts`）
2. **纯关心**（**每天最多 1 次** + 距上条主动消息 **≥3h**，`proactive_kindness_date` / `proactive_last_delivered_at`）

选择逻辑在 `domain/proactive/ProactiveFallbackTopics.kt`（纯函数）；hook 以 `PlainText` 入队，交给 LLM 写成自然一句话。

### 测试

`WorkScheduleHealthTest`(7)、`ProactiveFallbackTopicsTest`(12)，合计 **998 / 107+ suite 全绿**。

### 版本

`1.3.6`（排程自愈）→ `1.3.7`（兜底话题），已装机。

---

## 1.9.3.1（2026-11-21 23:3x）清数据后天气一直看不到的根因与重试兜底

**触发**：用户清除应用数据后问"你能看到我这边的天气吗"，AI 回答"没拿到天气数据"。查下来不是 system prompt 丢天气能力（`ContextBuilder.CAPABILITIES` 是 `const val`，清数据清不掉），而是**天气数据根本没进注记**。

### 根因（两层叠加）

1. 清数据 → `env_weather.xml`（天气缓存）被删，且 `app_state` 的手动城市也要重设。缓存的唯一后台填充点是 `ChatViewModel.prefetchWeather()`（`init` 里跑一次）。
2. Open-Meteo 预报端点 `api.open-meteo.com/v1/forecast` 在**当前网络下间歇性超时**——同一 URL 真机 curl 实测：两次 12–20s 超时（HTTP:000）、两次 HTTP 200。原来 `prefetchWeather()` 只拉一次，那一次撞上超时 → 缓存整天为空 → 发送路径 `peek()` 只读缓存（绝不联网）→ reminder 第 3 段只有城市名 → AI 按 prompt 约定说"没拿到"。

### 修复

- `WeatherCache.loadWithRetry(point, today, attempts=2, retryDelayMs=1500)`：失败重试 1 次，仅后台预热调用。
- `prefetchWeather()` 改调 `loadWithRetry`；**发送路径零改动**（仍 `peek()` 不联网，1.1.1 的"发送卡 2ms"教训不能回退）。
- 仍失败就认了，由整点环境检查下次填——重试只解决"启动那一下抖动"，不做后台常驻轮询。

### 关键教训（写进铁律）

- **清应用数据是独立的故障注入路径**：它同时清空 `SharedPreferences`（人设、API Key、天气缓存）与 `app_state`（手动城市）。任何"只在启动时填一次"的缓存都要问一句：清数据后还有没有第二条填充路径？
- **免费天气 API 的可用性不是 100%**：单点预热必须带重试，否则一次抖动 = 一整天功能缺失，且表现为"AI 说看不到"这种极难归因的现象。

### 测试与版本

- `WeatherCacheTest` +4（首次失败第二次成功 / 两次都失败 / 无坐标不发网络 / 缓存新鲜不触发重试）。
- `RelationshipStageTest` +2（6→8：59 条 1 天 / 29 条 2 天仍生疏，另改写 30 条 1 天 / 5 条 3 天 / 200 条 3 天三条旧断言）。
- 全量 **1015 / 124 suite / 0 失败**。`versionCode 65 / versionName 1.9.3.2`。

---

## 1.9.3.2（2026-11-21 23:4x）关系阶段门槛收紧

**触发**：用户反馈"亲近和熟悉的标准太低了"，要求上调。

### 新判定（纯函数 `RelationshipStage.of(Stats)`，输入仍是全库消息数 + 认识天数）

```
亲近 = 消息 ≥200 且 天数 ≥5          （旧：200 且 3）
熟悉 = 消息 ≥60 或（天数 ≥2 且 消息 ≥30）  （旧：30 条或 2 天，无消息下限）
生疏 = 其它
```

### 关键教训

- **收紧"熟悉"时必须保留"消息单独达标"这条路**：新规则里消息 ≥60 不看天数直接熟悉，是为了避免"聊了几百条但才第 1 天"被卡在生疏（老用户/批量导入场景）。天数那条路则加了消息下限 30，防止"注册第 2 天啥也没聊就熟悉"。
- **阈值只改常量与 `of()`，不动下游**：撒娇开关、想你门槛、情绪记忆写回、稳定前缀关系定位全部复用同一函数，改一处全链路生效。常量集中：新增 `FAMILIAR_MESSAGES=60` / `FAMILIAR_DAYS=2` / `FAMILIAR_DAYS_MESSAGES=30`，`CLOSE_DAYS` 3→5。
- **别漏改注释**：`ContextBuilder` 的 `relationshipNote` 注释里写死了"30/200 条消息门槛"，已同步改成泛指；文档 §3.3 / §8 常量表 / §9 测试数 / §11 分期表四处一并更新。
- **上级测试无需改**：`MoodServiceP3Test` 的 5 组 Stats（300/5、40/2、50/2、213/5、10/1）在新阈值下档位判定结论完全不变，验证了"只动纯函数、不动装配"的低风险性。

### 测试与版本

全量 **1015 / 124 suite / 0 失败**。`versionCode 65 / versionName 1.9.3.2`。

---

## 1.9.4.0（2026-11-22 00:xx）情绪记忆拟人化：三个踩坑结论

**触发**：用户从"像真人"角度复盘——3 条上限不是问题，"删最旧 + 全量注入 + 同类并列"才是。定稿：pivotal/普通分层 + 打分淘汰 + 同类合并 + 按需注入 + boost/厌恶 + 注入冷却。

### ① "刷新时间戳"不如"加分封顶"
- 被提起一次就把 `createTime` 改成 now，看似让它新鲜，实际两个反效果：
  1. **钉死老记忆**：反复被提起的旧条目永远排最前，新经历挤不进来；且改写了"那天发生了什么"的时间锚，语义失真。
  2. **养出厌倦正反馈**：每提一次更易被再提一次，越烦它越来劲，最终变复读机。
- 结论：时间锚不动，用 `boost` 表达"最近常被想起"——+0.05/次、每日最多一次、封顶 +0.2；厌恶 -0.1、下限 -0.2。衰减只由 score 体现。

### ② 冷却必须 12h + 每日一次并存
- 只有"每天最多一次"：23:59 注入、00:01 计数重置又注入，两分钟复读两次。
- 只有"12h 冷却"：一天内仍可能 00:00 与 12:01 两次，感知仍是"一天念叨两遍"。
- 两条都卡才堵住跨零点漏洞。普通记忆 48h（>24h 自然不会同日重复），不再叠加每日限制。

### ③ 讨厌扣分，不删记忆
- 用户说"别提"否的是**主动提起**，不是**这件事发生过**。删行会导致用户随后主动问时她矢口否认，比复读更伤。
- 扣分（下限 -0.2）+ 7 天冷却让主动提起停下；第 2 次起 `coolingUntil = Long.MAX_VALUE` 永久停止。通知"别提"从短期记忆扩到长期，口径一致——两条链路都**不物理删除**。
- boost / 冷却 / 次数必须落库，否则重启后"说了没用"，用户会重复表达厌恶。

### ④ 测试 Fake DAO 的兼容性
- 给 `@Dao` interface 加 `@Query` 新方法会强制 6 个 Fake DAO 测试类全部补实现，改动面大且易漏。
- 本版改为服务层只用既有 `getAll/getById/insert/update/delete` 组合实现新逻辑，**DAO 一个方法都没加**，Fake 零改动——这是"共享文件小步改"原则的具体应用。

### 测试与版本
- 新增 `EmotionalMemoryScoreTest` 31 条 + `EmotionalMemoryServiceTest` 15 条 + `VersionConsistencyTest` 2 条（版本号守门：build.gradle 与 CHANGELOG 最新条目一致、versionCode 唯一且递增，防并发改版撞号）。全量 **1063 / 127 suite / 0 失败**。`versionCode 66 / versionName 1.9.4.0`。

---

## 1.9.4.1（2026-11-22）P4 方向②：关系阶段影响主动消息节奏 + 修容量 bug

### 需求与设计
主动消息频率原本与关系无关（固定 2h 检查 / 1h 冷却 / 每日上限 8）。用户要求"生疏少打扰、亲近更黏"。经讨论定为：**间隔从「上次用户说话」算起、随机**（生疏 3~4h / 熟悉 2~4h / 亲近 2~3h），每日上限保持 8 不变（16h 清醒 ÷ 2h 是刻意设计，用户要睡觉）。

### 关键决策（写进铁律）
- ~~**间隔必须从「上次说话」算，不是从「上次她发消息」算**：真人不会在你刚聊完后马上又"主动"找你；`lastInteractionMillis` 现成，直接复用。~~ **⚠️ 1.9.4.2 修正：这条是错的。** 只用 `lastInteractionMillis`（仅用户发消息时更新）会导致她主动发过一条后锚点不动、`now-锚点` 持续变大 → 之后每 2h 检查全部放行、随机间隔塌成"每 2h 一条"。锚点必须是 `max(用户说话, 她上次主动送达)`，详见下文 1.9.4.2 节。
- **随机用「上次说话时刻」当种子**：`Random(seed).nextInt(range)`。同一段"没说"期间等多久固定（不会每 2h 检查一次就重掷导致忽长忽短）；用户再说话 seed 变、重新掷。纯函数、可测、无需持久化随机值。
- **不碰 Worker 周期/冷却常数**：改周期要 bump `SCHEDULE_SCHEMA`（1.0.5 事故）、冷却要 < 2h 周期（1.0.3 事故），一律不动。只把 `ProactiveGateContext` 的 1h 冷却换成关系档随机 gap（默认仍是 1h，测试零改动）。
- **2h 检查粒度决定了精度上限**：目标 3~4h 实际会落在"达到后第一个 2h 检查点"，最多晚 2h。这是方案 A（不改周期、零风险）的固有代价，用户已接受。

### 顺带修复（容量 bug，1.9.4.0 遗留）
- `MemoryContextProvider.build` 里情绪候选原先追加在 long **末尾**，而 `MemoryContextBlock` 对 long 做 `distinct().take(12)` → 普通长期记忆 ≥12 条时，情绪回忆被整段静默切掉，功能无声失效。改为情绪候选排**前面**，永不因普通记忆多而被挤掉。加回归测试 `emotional_survives_when_normal_long_exceeds_limit`。

### 测试与版本
- `RelationshipStageTest` +3、`ProactiveGateRulesTest` +3、`MemoryContextProviderTest` +1。全量 **1070 / 127 suite / 0 失败**。`versionCode 67 / versionName 1.9.4.1`。

---

## 1.9.4.2（2026-11-22）修复：用户沉默时"随机主动节奏"失效（重大）

### 现象与根因
用户实测发现"随机关心"节奏不对。根因：1.9.4.1 的冷却锚点/随机种子只用了 `KEY_LAST_INTERACTION`（**仅用户发消息时写**，`ChatViewModel:361`），主动消息送达后**不更新它**。于是她主动发过一条后锚点不动、`now-锚点` 持续变大 → 之后每 2h 检查全部放行，随机间隔退化成"每 2h 一条直到每日上限"；用户沉默期间反而被每 2h 轰炸，且"每句随机 2~4h"实际只对用户说过的上一句生效一次。

### 修复
- `ProactiveGateContext` 新增 `lastProactiveAtMillis`；冷却锚点统一走 `ProactiveGateRules.cooldownAnchorMillis(ctx)` = `max(用户上次说话, 她上次主动送达)`（两者都空则跳过冷却）。随机种子同样改用这个锚点。
- `DefaultProactiveGate` 新增 `lastProactiveAtProvider`，生产接 `app_state[KEY_PROACTIVE_LAST_DELIVERED]`（每条真实自发消息送达时由 `onDelivered` 写入，`CompanionApp` 已接线）。
- 效果：她每主动发一条就**重新起算**下一个随机间隔，沉默期也能按 2~4h 随机节奏找你，不再塌成固定 2h。
- **不动** Worker 周期 / 冷却常数 / 数据库 / 发送路径。

### 修复（"关心"消息误挂"已完成"→误扣分/误删，B 方案 · 1.9.4.2）
- 根因：1.9.4.0 把长期兜底话题 id 塞进 `completeMemoryId`；点"已完成"时 `ReminderActionHandler` 先试长期、`dismissById` 对**非情绪类长期记忆**返回 false → 退回 `memoryShortDao().deleteById(longId)`，而短期/长期两表编号各自从 1 起、会撞车 → 误删一条无关短期记忆；命中情绪记忆时点"已完成"也会被当"嫌烦"扣分。
- 修复（按角色三态，不再靠编号猜类型）：`ProactiveNotification` 拆成 `completeMemoryId`（仅指令提醒的短期 → "已完成"→删短期）与 `dismissLongMemoryId`（仅长期兜底 → "别提这个"→只降权/冷却、不删行）；`NotificationActions` 增 `DISMISS` / `ACTION_DISMISS`；`ReminderActionHandler` 按 action 分流，DONE 只删短期、DISMISS 只降权长期，二者绝不互串；短期话题闲聊(Memory) 依旧无按钮。

### 追加（同版本）：系统时间变更的即时重排（1.9.4.2）
- 旧自愈（`ensureWorkSchedulesHealthy`）只在 **冷启动** 跑；进程活着时改系统时间救不回来。新增 `TimeChangeSignals`（纯函数识别 `TIME_SET` / `TIMEZONE_CHANGED`，**不含** `DATE_CHANGED`——每天零点会发，误用会天天重置排程）+ `SystemTimeChangeReceiver`（系统广播 → `CompanionApp.healSchedulesAfterTimeChange()`：WorkManager 自愈 + 环境整点闹钟强排 + 仅补排**未来**提醒，不重放过期，避免时间前跳炸出一堆补发）。
- 仍未覆盖的另一层（留下版）：`syncEnvCheckSchedule` 冷启动只看布尔"已排"标记、时钟回拨也不重排；整点闹钟本身是 RTC，理论上也怕时间跳变——本次靠"收到时间变更就强排"绕开。

### 测试与版本
- `ProactiveGateRulesTest` +5（锚点取较晚者、她刚发过→拦、超间隔→放行、无用户消息但有主动锚点→拦、双空→放行）；`TimeChangeSignalsTest` +4（时间变更 action 识别）；`NotificationActionsTest` +1 / `ReminderActionHandlerTest` +3 / `ProactiveDeliveryRunnerTest` +1（三态回执分流与长期话题）。全量 **1084 / 128 suite / 0 失败**。`versionCode 68 / versionName 1.9.4.2`。

---

## 发布签名与打包（1.9.4.1 起，分享用）

- **keystore**：`keystore/companion-release.jks`（别名 `companion`，RSA 2048，有效期 10000 天）。**务必离线备份**——丢了就无法给已安装用户推送新版（签名不一致只能卸载重装、丢数据）。
- **密码**：写在项目根 `keystore.properties`（已加进 .gitignore），build.gradle 从中读 `storeFile/storePassword/keyAlias/keyPassword`。`*.jks` 与 `keystore.properties` **绝不能进仓库**。
- **release 构建**：`.\gradlew.bat assembleRelease`。已开 R8（`minifyEnabled true`）+ 资源压缩（`shrinkResources true`）+ ABI 收窄（`arm64-v8a,armeabi-v7a`，去掉仅模拟器用的 x86/x86_64）。产物 `app/build/outputs/apk/release/app-release.apk`：**debug 142MB → release 81.4MB，dex 60MB→4.4MB**。
- **R8 keep**：`proguard-rules.pro` 保留 ONNX/Retrofit/Gson + `com.companion.chat.data.**`（覆盖 Room 实体与 Gson DTO）。代码里无 `getIdentifier`/反射动态加载，故无动态资源/反射破坏风险；但**每次大改后仍要用 release 包真机跑一遍**（启动 + 进对话 + 记忆/去重/主动消息），R8 类问题只在运行期暴露。
- **MIUI 真机安装**：`adb install` 会被 MIUI 拦（`INSTALL_FAILED_USER_RESTRICTED`）。用 root：`adb push apk /data/local/tmp/ && adb shell su -c 'pm install -r -t /data/local/tmp/app-release.apk'`。
- **换签名会清数据**：debug 签名包 → release 签名包必须卸载（丢数据）。卸载前备份：`adb shell su -c 'tar -cf /sdcard/b.tar -C /data/data/com.companion.chat .' && adb pull /sdcard/b.tar`。分享给别人的用户各自独立安装、各自填 API Key，纯本地无服务器成本。

---

## 1.10.0–1.10.2（2026-11-22）记忆去重阈值：**长 / 短必须不对称**

### 坑（真机踩到，别再改回去）
长期记忆「同一件事换说法」不去重、越攒越多。拉库算余弦取证：
- 「用户说有一家糖水店的绿豆汤很好喝」 vs 「用户喜欢一家糖水店的绿豆汤，但店名和具体街道记不清了」→ **cos=0.84**，卡在 0.85 上不合并；
- 同库无关的「用户有一只猫叫肥波」对上面两条只有 **0.36 / 0.38**（离得很远）。

另有一条更隐蔽：**手动在记忆页加的记忆是裸 `insert`**（不算向量、不去重）→ `embedding` 为空，永远无法参与去重/召回。手动加旧记忆后再聊，就多出一条重复。

### 结论（两条阈值规则，写死别再动）
- **短期合并阈值必须 ≥ 0.85，不要调低**（`MemoryDeduplicator.threshold`）。短期记忆是**可执行的任务/待办**（「7点吃药」「8点吃药」余弦≈0.9），一降就会把两件不同的事并成一件、**丢提醒/丢任务**，比"重复"危害大得多。短期真有多条，靠 `MemoryContextBlock.dedupeByTopic`（注入时同主题只留一条）+ 容量淘汰（满 50 删 20）兜，不靠降阈值。
- **长期合并阈值单独用 0.80**（`MemoryDeduplicator.longThreshold`）。长期是稳定事实，重复只是脏；0.80 能并掉 0.84 那类"换词同义"，无关项（实测 0.36）离得远，安全。
- **安全护栏**：`allergy`（过敏/忌口）不与其它类别互相合并，合并保留原类别（别把"对花生过敏"并成"不喜欢花生"）。

### 配套
- 手动加记忆（`MemoryViewModel.addShort/addLong`）改走 `MemoryDeduplicator.upsertShort/upsertLong`（算向量 + 去重），不再裸插。
- `MemoryDeduplicator.consolidatePlainLong()`：进聊天页、embedding 就绪后一次性整理**历史**普通长期记忆（合并重复 + 给旧记录补向量），幂等、只动 `intensity<=0`、不碰情绪记忆。
- 深挖类改动后仍要**拉库核真实余弦**再定阈值——拍脑袋定 0.85/0.80 都不如实测分数可靠。
