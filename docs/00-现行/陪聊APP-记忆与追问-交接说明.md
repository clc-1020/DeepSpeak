# 陪聊 APP · 记忆与追问阶段交接说明（给另一个 Agent 的现状快照）

> **状态：现行** ｜ 记忆注入、追问链、容量淘汰的现状快照。
> 本文档随代码演进，细节以代码与本目录其他现行文档为准。

> 用途：与另一个 Agent 讨论修改建议时**消除信息差**。
> 覆盖范围：从「点完成 → 删短期记忆」这一轮开始（v1.2.8），到记忆接入主对话与瘦身（v1.3.3）为止。
> 写作原则：只写**已在真机验证过的事实**；推测/未验证的显式标注「未验证」；被推翻的方案也留着，防止重复踩。

---

## 0. 一句话现状

App 现在**能同时监督多件事**，**点「已完成」会连带删掉这条提醒的短期记忆**，**短记忆满 50 条自动淘汰 20 条**，并且**主对话每轮会注入一段按预算截断的记忆块**（短期按向量相似度筛、长期全量、近 3 天日记按相似度筛）。全部单元测试 **893 条 / 107 suite / 0 失败**，最新构建 **1.3.3（versionCode 35）已装在真机上**。

---

## 1. 环境与硬约束（对方案友必须先知道）

| 项 | 值 |
|---|---|
| 真机 | `<DEVICE_SERIAL>`（Redmi <DEVICE_CODENAME> / <DEVICE_MODEL>，Android 13 / MIUI 14，1080×2400），App **debuggable** → `run-as com.companion.chat` 可用 |
| 构建 | `$env:JAVA_HOME='<JDK17_DIR>'`；`.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon` |
| 装机 | `adb install -r -t dist\陪聊-companion-vX.apk`（`-r` 保留数据）；adb 在 `C:\ai-deepseek\phone-tools\platform-tools\adb.exe` |
| **数据保护铁律** | **禁止**直接跑 `gradlew connectedDebugAndroidTest`（AGP 8.1 会在跑完后自动卸载 App，**清空数据库与全部 prefs**，本项目已因此丢过一次数据）。要跑仪器测试必须走 `.\tools\deploy.ps1 -RunTests`（自动 backup → 测试 → 重装 → restore）。备份工具 `.\tools\app-data-backup.ps1 -Backup/-Restore/-List`，快照在 `.backups\<时间戳>\` |
| 日志 | MIUI 会吞 `Log.d`，**诊断日志一律用 `Log.w`**；关键 tag：`MemoryCtx`（本轮注入字数）、`MemoryCapacity`（淘汰）、`DeepSeekAsr`（语音）、`Followup`、`ProactiveDelivery`、`BootRestore` |
| 目录归属 | `ui/` 归 **UI 线**（另一个 Agent）；本次改动只碰了 `ui/chat/ChatViewModel.kt` 与 `ui/main/MainActivity.kt` 的必要接线，其余集中在 `domain/` 与 `data/` |

---

## 2. 版本链（本阶段）

| 版本 | 主题 | 一句话 |
|---|---|---|
| 1.2.6（code 28） | 追问去重键改为「任务文本」 | 以前「已有任意一条链」就静默丢弃新请求 → 用户说「监督我喝水」石沉大海 |
| 1.2.7（code 29） | 多链语义判定 | `followups:[{id,status}]`，逐条 `complete/abandon/advance` |
| **1.2.8**（code 30） | **点完成 → 删该提醒的短期记忆** | 用户诉求，用内容相似度识别，不靠 id/编码前缀 |
| 1.2.9（code 31） | 记忆页「日志」→「日记」 | 纯文案 |
| **1.3.0**（code 32） | **短记忆容量淘汰 50/20** + 跨午夜时钟 bug | 用户要「像人脑一样遗忘」 |
| **1.3.1**（code 33） | **三类记忆接进主对话** | 用户指出「记忆没进上下文，这笔钱白花」 |
| 1.3.2（code 34） | 日记 JSON 展平 | 库里 `events` 是 JSON 数组字符串，原样注入既丑又吃预算 |
| **1.3.3**（code 35） | **记忆块瘦身** | 提示语 69→27 字；日记改成「相关才注入」 |

产物在 `dist\`；当前装机 `陪聊-companion-v1.3.3.apk`（135.6 MB）。

---

## 3. 每轮改动的根因、方案与证据

### 3.1 v1.2.6 多链并存（前置背景）

**现象**：用户已有「背30个单词」链时，说「从现在起监督我喝水」→ 到点一条提醒都没有，AI 却在对话里答应了。

**根因**：`FollowupTaskExtractor` 的廉价预检 `dao.anyPendingFollowup()` 是**全局**判据，任意一条 pending 链存在就 `Result.success(null)` 直接返回 —— 无日志、无提示。

**改法**：

- `ReminderDao` 新增 `pendingFollowupByTask(task)`（`state='pending' AND type='followup' AND taskDesc = :task`）；**无新列 → 不需要迁移**
- 删掉锁外的全局预检（判重需要先知道任务名，只能先调模型）；锁内用 `pendingFollowupByTask(ex.task)` 兜正确性
- 代价（已接受）：以前「有链 → 零成本返回」，现在每条监督类消息都会**调一次模型**

### 3.2 v1.2.7 多链语义判定

`MoodService` 原来只打量 `FollowupPort.active()`（最早那条），所以「我喝完了」会作用到错误的链上。

**改法**：

- `FollowupPort` 主入口改成 `activeAll(): List<ActiveFollowup>`；`active()` 变默认实现（`activeAll().firstOrNull()`）
- `ReminderDao.activeFollowups()`（同判据，去掉 `LIMIT 1`）
- `MoodPromptBuilder`：逐条列 `id= / 任务 / 停止条件`；输出格式改 `"followups":[{"id":1,"status":"…"}]`
- `MoodUpdate.followupStatuses: Map<Long, FollowupStatus>`；解析容错（缺 id / id<=0 / status 不认识 / 元素非对象 → 跳过该条；重复 id 取第一条）
- `MoodService`：按 `followupStatuses[id]` 逐条分派；**模型漏 id → 该条一律 `advance`**（不重排就是永久卡死）；**旧单值 `followup_status` 只在「仅 1 条链」时采信**，多链时不瞎套

### 3.3 v1.2.8 点「已完成」→ 删该提醒的短期记忆（本轮起点）

**诉求**：用户「喝水提醒点击已完成就从短期记忆里删掉，不然短期记忆里全是已结束的提醒」+「监督也是一样」。

**被真机数据推翻的第一个方案**：原以为 `memory_short.content` 存的是 `followup:<id>:<task>` 编码 → 按前缀删。实拉数据发现存的是**自由中文**，而且分两类来源：

| 记忆内容 | 来源 |
|---|---|
| `现在我要背单词，提醒我。每隔5分钟提醒一次` | `ReminderAlarmReceiver.handleInstruction`（= `reminders.taskDesc` 原文） |
| `用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）` | `MemoryExtractor` 从对话提炼，**措辞完全不同** |

且 `memory_short` **没有 reminderId 列**可关联。

**最终方案**：`domain/reminder/ReminderMemoryMatcher.shouldClear(memoryContent, task)`

1. 归一化（去空白/标点）后**互相包含** → 删
2. 否则**剥停用词**（`提醒/监督/每隔/分钟/小时/开始/一次/以后/之后/要求/现在/今天/明天/晚上/早上/已经/任务/一下/记得`）后再判一次包含
3. 仍不中 → **最长公共子串 ≥ 2** 判同主题

**为什么必须剥停用词**（真实误删）：长任务名「现在我要背单词，提醒我。每隔5分钟提醒一次」与**喝水**记忆的公共片段只有「分钟提醒」，不剥词会把喝水两条一起删掉。剥离后前者只剩「要背单词5个」，与喝水无交集，与「…提醒一次背单词…」仍靠「背单词」命中。

**接线位置（关键）**：`FollowupPortImpl.complete()` / `abandon()` 末尾调 `deleteFollowupMemory(id)` → `getAll()` 过滤 `shouldClear` → 逐条 `deleteById`。

- 放在 Port 里而不是 `ReminderActionHandler`：**对话里说「做完了」与点通知按钮走的是同一条路**，放 handler 会漏掉对话路径
- 全程 `runCatching`；`memoryShortDao = null` 时静默跳过（测试/旧装配零改动）

**真机数据模拟**（用实际 4 行记忆 + 历史 taskDesc 跑同一规则）：

| task | 会删 |
|---|---|
| `喝水` | 记忆 [3][4] |
| `背30个单词` | 记忆 [1][2] |
| `现在我要背单词，提醒我。每隔5分钟提醒一次` | 只删 [1][2]（不误删喝水） |
| `吃药` | 无 |

### 3.4 v1.3.0 短记忆容量淘汰（50/20）+ 顺手修的跨午夜 bug

**诉求**：「攒满 50 条就自动清理淡忘分高的 20 条不在提醒/监督的记忆，比较符合人脑遗忘」。拍板参数：50/20、保护 24h 内新写入、被「别提这个」拉黑的**参与**淘汰。

- `domain/memory/ShortMemoryCapacity.plan(...)`（纯函数）：未满不动 → 排除 24h 保护窗口 → 排除活跃任务（复用 `ReminderMemoryMatcher`）→ 按 `fadeScore` **降序**、同分按 `timestamp` **升序**取 20
- `data/memory/MemoryCapacityEnforcer.enforceOnce()`：读 `memory_short` + `reminders(pending, instruction/followup)` → 算 → 删 → W 级日志
- 触发点：`ChatViewModel` 记忆写入后、`ReminderAlarmReceiver.handleInstruction` 投递后（**不做定时扫描**）

**顺手修的既有 bug（跨午夜才暴露）**：`EnvironmentHookService.weatherBundle()` 用 `LocalDate.now(zone)` 取「今天」，而**去重键**用注入时钟 `dateKeyOf(nowMillis(), …)`。0 点后两者不一致 → 天气按 21 号取、键按 20 号算；真机表现为**凌晨后天气钩子静默失效/重复**。已改为同源，并给测试假数据补 11-21 一天。

> ⚠️ 遗留：**其它地方是否还有 `LocalDate.now()` 与注入时钟混用，未全量排查**，建议对方案友关注。

### 3.5 v1.3.1 三类记忆接进主对话（本阶段最重要的新能力）

**诉求**：「短期/长期/日记都没接进主对话，这笔钱纯白花」；表现 = AI 回复前挑一定数量短期+长期+近 3 天日记进上下文，相关的提一句，不相关不管。

**三个决定性设计**：

1. **记忆块放尾部，不进稳定前缀**
   `ContextBuilder` 新增 `memoryBlock`，插在「历史之后、本轮 user 之前」。放进顶部 system 会让 DeepSeek 前缀缓存整段失效 —— 省下的 token 成倍吐回去。
2. **相关性用本地向量，不再调模型**
   `MemoryContextProvider` 用 bge-small-zh（去重已在用）算余弦，阈值 0.35。现成的 `MemoryTopicSelector` 是**调模型**挑话题的，每轮调一次等于又白花钱 + 1~2s 首字延迟。
3. **按固定优先级砍预算**：超预算时「日记 → 短期 → 长期」整段丢弃（长期记忆最稳、最不该丢）

**实现落点**：

| 文件 | 职责 |
|---|---|
| `domain/memory/MemoryContextBlock.kt`（新） | 纯函数：阈值过滤、条数上限、去重、拼装、预算裁剪、**无内容返回 null** |
| `data/memory/MemoryContextProvider.kt`（新） | 读三张表 → 算相似度（优先用行内已存向量，缺了现场算）→ 交给纯函数 |
| `domain/ContextBuilder.kt` | 新增 `memoryBlock` 参数 |
| `domain/ContextTrimmer.kt` | **行为变更**：只钉**第一条** system（人设+能力），其余 system 参与正常裁剪 —— 否则记忆块会被提到人设前面，破坏顺序与前缀缓存 |
| `ui/chat/ChatViewModel.kt` | build 前取记忆块（预算 = 窗口 × 12%）；`init` 里 `prefetchEmbedding()`；W 级日志 `MemoryCtx 本轮注入记忆块 N 字` |
| `CompanionApp.kt` | `memoryContextProvider` 装配（用 `embeddingServiceOrNoop()`，**不在主线程触发模型加载**） |

**降级语义**：embedding 未就绪 → **短期与日记都不注入**（判不了相关性就不瞎猜），长期照常；任何异常 → 整块 null。

### 3.6 v1.3.2 日记 JSON 展平

`memory_daily.events` 在库里是 JSON 数组字符串（`["…","…"]`），原样注入既占预算又难看（一段上百字）。改为 `readableDailyEvents()` 解析成「；」连接的句子，单条截断 60 字；解析失败原样保留。

### 3.7 v1.3.3 记忆块瘦身（用户拍板砍 1、2 两条）

| 改动 | 效果（用真机真实数据算） |
|---|---|
| `HEADER` 69 字 → **27 字**：「【我记得的事】相关就自然提一句，不相关别硬提、别罗列。」 | 每轮固定省 42 字 |
| 日记改为**按相似度过滤**（同阈值 0.35）；`DailyEntry` 新增 `score`；相似度用**展平后**的正文算 | 闲聊轮整段省 133 字 |

| 场景 | 字数 |
|---|---|
| 1.3.2（旧） | 381 |
| 1.3.3 · 日记相关 | **339** |
| 1.3.3 · 日记不相关（闲聊） | **205** |

**未做**（当时给用户列了三条，只批准 1、2）：把长期记忆挪到稳定前缀吃缓存折扣。

---

### 3.8 功能视角速查（**从 1.2.8 起**，逐条对应「用户能看到/感受到」的变化）

> §3.3–3.7 是按「根因 → 方案 → 证据」写的；这一节按「功能 → 行为 → 文件」写，方便逐条讨论要不要改。

#### A. 提醒完成 → 清理它的记忆（1.2.8）

| 功能 | 现在的行为 | 文件 |
|---|---|---|
| 点通知「已完成」 | 追问链停 → 已入队残留取消 → **删该任务的短期记忆** | `ReminderActionHandler`（调 `FollowupPort.complete`） |
| 对话里说「做完了」 | 同上（**同一条路**，所以放 Port 而不是 handler） | `data/reminder/FollowupPortImpl.kt` |
| 「放弃 / 不干了」 | 同样删记忆（`abandon()`） | 同上 |
| 怎么认出「这是这条提醒的记忆」 | 内容相似度：互相包含 → 剥停用词再判包含 → 最长公共子串 ≥2 | `domain/reminder/ReminderMemoryMatcher.kt` |
| 查不到任务名（行已删/坏数据） | 不删、不报错，停链主流程不受影响 | 同上 |

#### B. 记忆容量（1.3.0）

| 功能 | 现在的行为 | 文件 |
|---|---|---|
| 满 50 条自动清理 | 一次性删 20 条，剩 30 | `domain/memory/ShortMemoryCapacity.kt` |
| 先删谁 | `fadeScore` 高的先走；同分则更旧的先走 | 同上 |
| 谁不能删 | ① 24 小时内新写入的 ② 正在提醒/监督中的（内容相似度判定） | 同上 + `ReminderMemoryMatcher` |
| 被「别提这个」拉黑的 | **参与**淘汰（永不召回，只占地方） | 同上 |
| 什么时候触发 | 每次**写入**记忆后（聊天提炼 / 提醒到点），**不做**定时扫描 | `ChatViewModel`、`ReminderAlarmReceiver.handleInstruction` |
| 可观测 | `W/MemoryCapacity: 短期记忆淘汰 N 条（总量 50 → 30），保护任务 M 条` | `MemoryCapacityEnforcer` |

#### C. 主对话记忆注入（1.3.1 → 1.3.3）

| 功能 | 现在的行为 | 版本 | 文件 |
|---|---|---|---|
| 每轮给模型看记忆 | 三段：**最近**（短期）/ **关于你**（长期）/ **前几天**（日记） | 1.3.1 | `MemoryContextBlock`、`MemoryContextProvider` |
| 条数上限 | 短期 4 / 长期 12 / 日记 3 天 | 1.3.1 | 同上 |
| 相关性判定 | 本地向量余弦 ≥ **0.35**（短期与日记共用）；**不调模型** | 1.3.1 / 1.3.3 | `MemoryContextProvider` |
| 预算 | 上下文窗口 × **12%**，超出按 **日记 → 短期 → 长期** 整段丢弃 | 1.3.1 | `MemoryContextBlock` |
| 没有相关内容 | 返回 `null`，**一个字都不注入** | 1.3.1 | 同上 |
| 注入位置 | 历史之后、本轮 user 之前（**保前缀缓存**） | 1.3.1 | `ContextBuilder` |
| 裁剪行为变更 | `ContextTrimmer` **只钉第一条 system**，其余参与裁剪 | 1.3.1 | `ContextTrimmer` |
| 首轮不卡 | 进聊天页后台预热向量模型 | 1.3.1 | `ChatViewModel.prefetchEmbedding()` |
| 可观测 | `W/MemoryCtx: 本轮注入记忆块 N 字` | 1.3.1 | `ChatViewModel` |
| 日记格式 | 库里是 JSON 数组 → 展平成「；」句子，单条截断 60 字 | 1.3.2 | `MemoryContextBlock.readableDailyEvents()` |
| 提示语 | 27 字：「相关就自然提一句，不相关别硬提、别罗列。」 | 1.3.3 | `MemoryContextBlock.HEADER` |
| 日记注入条件 | 从「无条件」改为「与本轮相关才注入」 | 1.3.3 | `DailyEntry.score` + Provider |

#### D. 文案（1.2.9）

| 位置 | 改前 → 改后 |
|---|---|
| 记忆页第三个 tab | 「日志」→「日记」 |
| 空列表提示 | 「还没有每日日志」→「还没有日记」 |
| 新增/编辑对话框标题 | 「新增每日日志 / 编辑每日日志」→「新增日记 / 编辑日记」 |
| 设置页「查看执行日志」「保活执行日志」 | **保持不动**（那是 root 命令输出，不是日记） |

#### E. 顺带修掉的既有缺陷（1.3.0）

| 缺陷 | 现象 | 修法 |
|---|---|---|
| 天气「今天」用真实时钟、去重键用注入时钟 | 凌晨之后天气钩子静默失效/重复（测试 0 点后连红 5 条） | `EnvironmentHookService.weatherBundle()` 改用注入时钟，与去重键同源 |

#### F. 接口 / 构造签名变化（对方改代码前必看）

| 类型 | 变化 |
|---|---|
| `FollowupPort` | 新增 `activeAll(): List<ActiveFollowup>`（`active()` 变默认实现）、`taskOf(reminderId): String?` |
| `FollowupPortImpl` 构造 | 新增 `queue: PendingMessageQueue`（1.2.8，必填）、`memoryShortDao: MemoryShortDao?`（1.2.8，可空） |
| `ReminderDao` | 新增 `pendingFollowupByTask(task)`（1.2.6）、`activeFollowups()`（1.2.7） |
| `MemoryShortDao` | 曾新增 `deleteByContentPrefix` 后**又删除**（方案变更）—— 若看到旧引用请确认已清理 |
| `MoodUpdate` | 新增 `followupStatuses: Map<Long, FollowupStatus>`（1.2.7） |
| `ContextBuilder.build` | 新增 `memoryBlock: String? = null`（1.3.1，默认值保证旧调用零改动） |
| `MemoryContextBlock.DailyEntry` | 新增 `score: Float? = null`（1.3.3） |
| DB schema | **本阶段零迁移**（没有新增列/表） |
## 4. 当前参数表（改之前先看这里）

| 常量 | 值 | 位置 |
|---|---|---|
| `ShortMemoryCapacity.DEFAULT_MAX` | 50 | `domain/memory/ShortMemoryCapacity.kt` |
| `ShortMemoryCapacity.DEFAULT_EVICT` | 20 | 同上 |
| `ShortMemoryCapacity.PROTECT_WINDOW_MS` | 24h | 同上 |
| `MemoryContextBlock.SHORT_LIMIT` | 4 条 | `domain/memory/MemoryContextBlock.kt` |
| `MemoryContextBlock.LONG_LIMIT` | 12 条 | 同上 |
| `MemoryContextBlock.DAILY_DAYS` | 3 天 | 同上 |
| `MemoryContextBlock.SIMILARITY_THRESHOLD` | 0.35 | 同上（短期与日记共用） |
| `MemoryContextBlock.EVENT_MAX_CHARS` | 60 字/条 | 同上 |
| `ChatViewModel.MEMORY_CONTEXT_RATIO` | 0.12（窗口 8192 → 983 字） | `ui/chat/ChatViewModel.kt` |
| `MemoryContextBlock` 砍预算顺序 | 日记 → 短期 → 长期 | 同上 |
| `MemroyContextBlock` 提示语 | 27 字固定 | 同上 |
| token 估算 | `ContextTrimmer` 按 **1 字 ≈ 1 token**（保守） | `domain/ContextTrimmer.kt` |

---

## 5. 已知限制与遗留（**给对方案友的重点讨论区**）

1. **阈值 0.35 偏宽**（真机实测）：用户说「你觉得我今晚会尿床吗」时，4 条「背单词/喝水」短期记忆**全部**过阈值被注入 → AI 提了「你刚才一分钟一杯的节奏」。这次正好切题，但换成别的话题可能显得东拉西扯。候选方案：阈值提到 0.45，或加「必须 ≥ 2 条同时命中」之类。
2. **长期记忆被测试数据污染**（当前真机状态）：6 条长期记忆全是近两小时测试残留（「用户偏好有人监督其背单词」「需要被提醒多喝水」「喜欢用调侃语气」等）。这会让 AI 任何话题都往背单词/喝水上蹭。**这是数据问题不是代码问题**，需要手动清理或加「测试数据隔离」。
3. **日记相关性未在真机验证**：只验证过「我很开心」→ 日记不注入（符合预期）；**没验证过「相关时确实注入」**。
4. **`memory_short` 没有 reminderId 列** → 完成/放弃时的清理只能靠**文本相似度**猜，既有误删风险也有漏删风险（「宁可漏删」是当前取舍）。彻底解法是加列 + 迁移（v7→v8），代价是一次 DB 迁移 + 仪器测试。
5. **容量淘汰只在写入后触发**：历史脏数据（已结束提醒留下的记忆）不会被主动清理，只能等新写入把条数顶到 50。
6. **长期记忆去重只按「文本完全一致」**：语义重复（「对花生过敏」vs「花生过敏」）会占两条名额。
7. **`MemoryContextProvider` 每条缺失向量的短期记忆会现场 embed**：记忆多（接近 50 条）时首轮可能多花几十~几百毫秒。**未做真机延迟测量**。
8. **`FollowupPort.activeFollowup()` 单链旧查询仍在**（`LIMIT 1`），目前只被兼容/日志使用；对方案友若要清理需确认无其它调用点。
9. **`LocalDate.now()` 与注入时钟混用**：只修了 `EnvironmentHookService` 一处，**未全量排查**其它类。
10. **`ContextTrimmer` 行为已变**（只钉首条 system）：如果有 UI 侧新代码依赖「所有 system 都常驻」，需要注意。

---

## 6. 如何验证（给对方案友的操作手册）

**单测**

```powershell
$env:JAVA_HOME='<JDK17_DIR>'
cd C:\Users\<user>\.deepseek-project\dsh-project
.\gradlew.bat testDebugUnitTest --no-daemon
# 当前基线：893 tests / 107 suites / 0 failures
```

**真机看「本轮注入了多少字」**

```powershell
& $adb logcat -d -v time | Select-String 'MemoryCtx'
# W/MemoryCtx: 本轮注入记忆块 205 字   ← 闲聊轮（日记不相关）
# W/MemoryCtx: 本轮注入记忆块 339 字   ← 记忆相关轮
```

**看记忆与提醒的真实状态**（只读，不改设备）

```powershell
# 拉库（含 WAL）
& $adb exec-out "run-as com.companion.chat cat databases/companion.db" > d.db
& $adb exec-out "run-as com.companion.chat cat databases/companion.db-wal" > d.db-wal
# 用本机 Python sqlite3 读；表：memory_short / memory_long / memory_daily / reminders / pending_messages
```

**看淘汰日志**

```
W/MemoryCapacity: 短期记忆淘汰 20 条（总量 50 → 30），保护任务 N 条
```

**装机**

```powershell
.\tools\app-data-backup.ps1 -Backup -Serial <DEVICE_SERIAL>
& $adb install -r -t dist\陪聊-companion-v1.3.3.apk
```

---

## 7. 文件清单（本阶段新增 / 修改）

**新增**

```
app/src/main/java/com/companion/chat/domain/reminder/ReminderMemoryMatcher.kt
app/src/main/java/com/companion/chat/domain/memory/ShortMemoryCapacity.kt
app/src/main/java/com/companion/chat/domain/memory/MemoryContextBlock.kt
app/src/main/java/com/companion/chat/data/memory/MemoryCapacityEnforcer.kt
app/src/main/java/com/companion/chat/data/memory/MemoryContextProvider.kt
app/src/main/java/com/companion/chat/domain/settings/QuickDnd.kt        （同阶段早些时候，铃铛一键勿扰）
```

**修改（按重要性）**

```
domain/reminder/FollowupPort.kt          activeAll()、taskOf()
data/reminder/FollowupPortImpl.kt        complete/abandon 清队列 + 删记忆；activeAll；taskOf
data/reminder/FollowupTaskExtractor.kt   去重键=任务文本；相对时长优先（1分钟后）
domain/ContextBuilder.kt                 memoryBlock 参数（尾部注入）
domain/ContextTrimmer.kt                 只钉首条 system
data/local/dao/ReminderDao.kt            pendingFollowupByTask / activeFollowups
data/prefs/ConfigRepository.kt           dnd_saved_start_minute（铃铛快照）
ui/chat/ChatViewModel.kt                 记忆块接线 + embedding 预热 + MemoryCtx 日志
ui/MainActivity.kt                       铃铛 onDndClick 真实现（原为 TODO 空壳）
ui/settings/SettingsScreen.kt            「当前心情 / 判定原因」显示
ui/memory/MemoryScreen.kt                「日志」→「日记」文案
worker/ReminderAlarmReceiver.kt          handleInstruction 后触发容量淘汰
data/proactive/EnvironmentHookService.kt 天气「今天」改用注入时钟
domain/mood/{MoodPromptBuilder, MoodUpdate}.kt、data/mood/MoodService.kt   多链逐条分派
```

**测试新增**

```
ReminderMemoryMatcherTest(6) / ShortMemoryCapacityTest(7) / MemoryCapacityEnforcerTest(5)
MemoryContextBlockTest(13) / MemoryContextProviderTest(6) / QuickDndTest(9)
```

---

## 8. 踩过的坑（防对方重复）

1. **`connectedDebugAndroidTest` 会清空数据**（AGP 8.1 跑完自动卸载）。必走 `deploy.ps1 -RunTests`。
2. **`leaveApksInstalledAfterRun=true` 在 AGP 8.1 无效**（实测），别信。
3. **MIUI 吞 `Log.d`** —— 诊断日志用 `Log.w`。
4. **`memory_short` 存自由中文**，不是编码；按前缀删必漏。
5. **停用词不剥会误删**：长任务名与别的主题共有「分钟提醒」。
6. **砍预算不能用 `removeLast`**：拼接顺序是「最近/关于你/前几天」，`removeLast` 会先砍长期，与设计相反。已改为按固定 dropOrder。
7. **`ContextTrimmer` 原把所有 system 提到最前**：记忆块进去会跑到人设前面 → 破坏缓存前缀。
8. **`private class` 的 fake 不能继承**（Kotlin class 默认 final）：测试里要给 fake 加行为，用**接口委托**（`class X : Dao by base`）而不是继承。
9. **跨午夜会翻红一批测试**：任何「今天」的取值必须与注入时钟同源。
10. **写作 fake 的自动补丁要核对类边界**：批量替换 `deleteAll() { items.clear() }` 会同时命中 `FakeLong`/`FakeDaily`，插进了不属于它们的接口方法。

---

## 9. 待用户拍板的三件事（讨论时可提）

1. 相似度阈值 0.35 → 0.45？（减少"硬蹭"，但相关记忆更容易被漏）
2. 长期记忆挪到稳定前缀吃缓存折扣？（省 token，但记忆更新时会单次失效）
3. `memory_short` 加 `reminderId` 列（DB 迁移 v7→v8）？（彻底解决"完成时删错/漏删"，代价是迁移 + 仪器测试）

---

## 10. 附：当前真机数据快照（2026-11-21 00:4x）

**memory_short（4 条）**

```
[1] 用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）
[2] 现在我要背单词，提醒我。每隔5分钟提醒一次
[3] 用户要求在1分钟后被提醒喝水，并每隔1分钟提醒一次
[4] 喝水，每隔1分钟提醒我一次
```

**memory_long（6 条，全部为测试残留）**

```
[1] preference 用户偏好有人监督其背单词
[2] other      用户正在背单词，需要督促提醒
[3] other      用户需要被提醒多喝水
[4] other      用户会以喝水提醒为名义测试助手的追问功能
[5] preference 用户喜欢用调侃语气与助手交互
[6] preference 用户晚上有每分钟喝一杯水的习惯，被建议改为五分钟一次
```

**memory_daily（2 天）**：`2026-11-21`、`2026-11-20`（events 为 JSON 数组字符串）

**reminders**：1/2 = 背单词（done / abandoned），3 = 指令提醒（done），4 = 喝水（done）

**最后一次实测注入**

```
W/MemoryCtx(15108): 本轮注入记忆块 149 字     ← 用户说「我很开心」
AI 回复：开心什么，背单词背出成就感了？我这边五分钟一次的钟还没响呢，先老实背。
⇒ 长期记忆确实被模型用上了（背单词 + 五分钟喝水），但话题被测试数据带偏
```

---

## 9. 深度回忆：命中「记不记得/上次」时专门翻记忆（2026-11-22，1.10.0）

**动机**：普通召回是「跟你这句话像不像」。但「你还记不记得那件事」这种问句本身很空，相似度低于 0.45 → 召不回；日记又只看近 3 天，「上次」可能早在两周前。于是命中这类**过去回忆词**时专门放宽翻一遍。

**链路**（全在 App 侧，不碰模型/网络结构）：

1. `domain/memory/RecallTriggerDetector.detect(text)`（纯函数）：命中**明确过去回忆词**（还记得/记不记得/我上次/上次/那次/之前说的…）**且**不含**未来/待办词**（明天/下次/答应/提醒我…）才 `triggered=true`。坑：「你还记不记得我**明天**要开会」是提醒不是回忆，靠排除词挡掉。
2. `ChatViewModel.generateAssistant`：`deepRecall = detect(...) && getDeepRecallEnabled()`，作为 `recallMode` 传进 `MemoryContextProvider.build`，记忆预算 12%→18%。
3. `MemoryContextProvider.build(recallMode=true)`：**两档渐进放宽**——
   - 第一档：日记 3→14 天、短期阈值 0.45→0.32、短期名额 4→6；
   - 第一档短期+日记一条都没过线 → 第二档：日记扫到 ≤180 天、阈值 0.28；
   - 长期记忆照旧全量注入（稳定事实，不算「凑数」）。
4. **翻不到就认、不瞎编**（用户明确要求）：`ContextBuilder.CAPABILITIES` 新增永久规则「记不清就坦白、别编具体日期/地点/经过」；回忆轮再由 `MemoryContextBlock.RECALL_NOTE` 追加一句尾部轻提示。**绝不塞「最近/重要度」无关记忆凑数**——`MemoryContextBlock.build` 在无匹配时只返回该提示，不返回随机记忆。
5. 开关：设置页「深度回忆」默认开（`ConfigRepository.KEY_DEEP_RECALL`），关掉即回普通召回。

**为什么一期不做「模糊指代」**（那个 / 之前那家）：误伤最多，只做明确词；二期若做，也只应在虚词旁有具体名词时触发。

**降级**：检测是纯字符串、失败最多「少翻一次」；provider 任一步异常 `runCatching → null`，不影响主对话。

---

## 10. 长期记忆去重失效修复（2026-11-22，1.10.1）

**现象**：手动在记忆页加「用户说有一家糖水店的绿豆汤很好喝」，聊完又自动写入「用户喜欢喝糖水店的绿豆汤」，两条并存没合并。

**真机取证**（拉库看 `memory_long`）：手动加的那条 `category=fact` 且 **`embedding` 为空（0 字节）**；自动写的那条 `category=preference` 有向量。所以去重根本没机会比较。

**两个根因**：
1. **手动加记忆裸插**：`MemoryViewModel.addShort/addLong` 直接 `dao.insert`，不算向量、不走去重 → 向量为空，之后既不能语义去重也不能召回。
2. **去重只比同类别**：`MemoryDeduplicator.upsertLong` 用 `filter { category == cat }`，`fact` 和 `preference` 同义也永不比较。

**修复**：
- 手动加记忆改走 `MemoryDeduplicator.upsertShort/upsertLong`（算向量 + 去重）。
- `upsertLong` 改**跨类别比较**；唯一护栏：**allergy 不与其它类别互相合并**（别把「对花生过敏」并成「不喜欢花生」而丢过敏事实），合并保留原类别。
- 新增 `consolidatePlainLong()`：进聊天页、embedding 就绪后跑一次，**合并历史重复 + 给旧记录补向量**；幂等、只动 `intensity<=0` 的普通记忆，不碰情绪记忆（那是另一套子系统）。

**注意**：整理只解决"已存在的重复"，将来靠 `upsertLong` 实时去重；阈值仍是 0.85，真机若发现该并没并，用 `MemoryCtx`/`MemoryDedup` 日志核分数再调。

