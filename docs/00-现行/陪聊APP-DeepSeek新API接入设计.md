# 陪聊 APP · DeepSeek 新 API 接入设计（2026-11-20）

> **状态：现行** ｜ 接口层事实源（能力清单 / 采纳决策 / 类与方法清单）。
> 本文档随代码演进，细节以代码与本目录其他现行文档为准。

> 依据：DeepSeek 官方 API 文档（2026-11-13「全家桶应用 API」发布 + 相关指南）。
> 本文件是「能力清单 + 采纳决策 + 任务 1.2 类/方法清单 + TTS 模块设计」的**唯一详细来源**；《陪聊APP项目规则.md》只放摘要与链接。
> 目的：在动手写代码前，把"用哪些官方接口、怎么落到现有类"定死，避免边写边改。

---

## 0. TL;DR（一句话版）

- **主对话**：`deepseek-flash` + **关思考**（快、省）；结构化输出用 `response_format={"type":"json_object"}`；每轮 user 前注入 `latest_reminder`（日期/星期/地点/平台/语言）；新建会话首条请求开 `title:{enabled:true}`；**稳定人设 + 长期记忆摘要永远放最前作前缀**吃上下文缓存。
- **TTS**：抽成 `TtsEngine` 接口，**系统 TTS 兜底 + DeepSeek 官方 TTS 可选**（联网、按字符计费）。
- **视觉**：`deepseek-flash` 原生支持图片，继续用 `image_url` base64（任务 1.6 已实现，但**识图模型名要修正**，见 §6）。
- **ASR / 联网搜索 / OCR**：列 v1.1，先不做。
- **`safety_check` 不开**（个人自用，开了会被"拒答"打断）。

---

## 1. 官方能力清单（接口速查）

### 1.1 对话三大协议（都支持新能力，模型用 `deepseek-flash`）

| 协议 | Endpoint |
|---|---|
| Chat Completions（OpenAI 兼容，**本项目主用**） | `POST https://api.deepseek.com/chat/completions` |
| Messages（Anthropic 兼容） | `POST https://api.deepseek.com/anthropic/v1/messages` |
| Responses（OpenAI Responses） | `POST https://api.deepseek.com/responses` |

### 1.2 原接口开参数即用（A 类）

| 能力 | 关键参数 / 工具 | 说明 |
|---|---|---|
| 联网搜索 | `tools:[{"type":"deepseek_web_search","name":"deepseek_web_search"}]` | 非思考/思考模式均可自动搜网页/图片，带引用作答 |
| 深度思考搜索 | 思考模式 + `deepseek_web_search` | 多步推理中自主联网读链接 |
| 阅读链接 | `tools:[{"type":"deepseek_read_url","name":"deepseek_read_url"}]` | 自动抓用户消息里的链接 |
| 文件输入 | `file` 内容块 | PDF/Office/图片解析后入上下文 |
| 模型安全 | `safety_check:{"level":"high"}` | 命中风险返回 `stop_reason:"refusal"`（个人自用**不开**） |
| 会话标题 | `title:{"enabled":true}` | 回答同时带标题；流式取 `delta.title`，非流式取 `message.title`；不计费 |
| 用户元数据 | `{"role":"latest_reminder","content":"YYYY-MM-DD,星期,地点,平台,语言"}` | 放在 user 消息**正前方**；`platform`=`App`/`Web` |
| 思考控制 | `thinking:{"type":"enabled"|"disabled"}` + `reasoning_effort:"low"|"high"|"max"` | deepseek-flash 默认**开**思考，陪伴要显式关 |
| 结构化输出 | `response_format:{"type":"json_object"}` | prompt 里要写"输出 JSON"，并给足 `max_tokens` |

### 1.3 独立底层接口（B 类，可单独调用）

| 接口 | Endpoint | 请求/限制 | 返回 |
|---|---|---|---|
| Search | `POST /v1/tools/search` | `{query(1-128字), count(1-50), ori_question?, localization?, need_content?}` | `results[]`（title/url/snippet/summary/content/date/authority） |
| Fetch | `POST /v1/tools/fetch` | `{url(≤8192)}` | `{title, content(Markdown)}` |
| OCR | 见 `/guides/ocr` | 按页 | 文字 |
| ASR 语音识别 | `POST /v1/tools/asr` | `multipart/form-data`，`file`=16bit PCM WAV / Ogg Opus，≤8MiB、≤120s，自动识别 20+ 语言 | `{request_id, text, duration_ms}` |
| TTS 语音合成 | `POST /v1/tools/tts`（+ `wss://api.deepseek.com/v1/tools/tts` 流式） | `{text, voice_id:"stella"(女,默认)|"tide"(男), format:"wav"(默认)|"pcm", filter_markdown:true}`；≤10000 计费字符（中文1字算2），body≤64KiB，单次≤30 分钟 | 音频流（wav=`audio/wav`；pcm=24kHz 16bit mono） |

### 1.4 模型与计费速查

- **模型**：`deepseek-flash`（= DeepSeek-V4.1-Flash-1017，**原生视觉**、1M 上下文、最大输出 384K、thinking 三档 low/high/max）；`deepseek-v4-pro`（文本更强、更贵、**不支持视觉**）。
- **旧名已下线**：`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 下线，统一用 `deepseek-flash`。
- **模型价（每 1M tokens，$）**：flash 命中缓存 0.003(谷)/0.006(峰)，未命中 0.15/0.3，输出 0.6/1.2。谷时=峰时一半；峰时 UTC 01-04、06-10（周一至五，节假日除外）。
- **工具价**：TTS `$0.45/万字符`（限时五折 `0.225`）；ASR `$0.116/小时`（五折 `0.058`）；OCR/文件解析 `$0.225/千页`；**客户端直连** Search/Fetch `$0.75/千次`。
- **重要**：自 2026-11-18 12:00（北京时间）起，**模型侧（服务端）触发的搜索/抓取工具调用不再单独计费**（结果算进 token）。即用 `deepseek_web_search` 工具让模型自己搜，不按次收费；只有客户端直接调 `/v1/tools/search`、`/v1/tools/fetch` 才按次收费。

---

## 2. 采纳决策

### ★ 立即用（对现有模块直接增强）
1. **TTS 语音合成**（官方 `POST /v1/tools/tts`）→ 双通道，系统 TTS 兜底。
2. **上下文缓存 Context Caching** → 稳定前缀，吃命中价（约为未命中 1/40）。
3. **JSON Output**（`json_object`）→ mood 更新、记忆提取稳定拿 JSON。
4. **用户元数据 `latest_reminder`** → 主动消息/时间感更自然。
5. **会话标题 `title`** → 建会话顺带生成、不额外请求、不计费。

### ○ 可选增强（v1.1+）
6. **联网搜索 `deepseek_web_search`** → 关键词/手动触发，别默认每句都搜（延迟+token）。
7. **视觉/图片输入（vision, base64）** → `deepseek-flash` 原生支持；**任务 1.6 已实现**，仅需修正模型名。
8. **ASR 语音识别** → 以后加"按住说话"用官方，不接第三方。
9. **OCR** → 纯文字截图抽取更省；一般 vision 够用。

### ✕ 不用（个人自用场景）
- `safety_check`：对外服务才需要，个人陪伴内容开了会打断，**不开**。
- 文件输入/PDF·Office 解析：陪聊用不到。
- FIM / Chat Prefix Completion / Responses / Anthropic 协议：与陪伴无关，继续 Chat Completions。
- `deepseek-v4-pro`：陪伴聊天 flash 足够。

---

## 3. 逐项落地要点

### 3.1 TTS 双通道（详见 §5）
- `TtsEngine` 接口 + `SystemTtsEngine`（兜底）+ `DeepSeekTtsEngine`（官方）。
- 默认系统 TTS；设置页可切「官方 TTS（联网计费）」。
- 官方 TTS 失败/无网 → 自动降级系统 TTS，不崩。

### 3.2 上下文缓存前缀（省钱核心）
- 请求 `messages` 顺序**固定**：`[system: 人设 + 长期记忆摘要(稳定前缀)] → [历史消息(含各轮 latest_reminder)] → [本轮 latest_reminder] → [本轮 user]`。
- **稳定前缀里绝不放变化内容**（时间、mood、最近消息），否则缓存不命中。
- 历史里的 `latest_reminder` 要**随历史回传**（官方：省略会降低缓存命中）。
- `usage` 里已有 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`，继续落 `MessageEntity.cacheHit`。

### 3.3 JSON Output
- 调用点：任务 5.1 mood（`{mood,reason}`）、任务 2.2 记忆提取（`{short,long,daily}`）。
- 请求体加 `response_format={"type":"json_object"}`；prompt 明确"只输出 JSON"；`max_tokens` 给足防截断；解析失败保持原值。

### 3.4 latest_reminder 注入
- 格式固定：`<date>,<weekday>,<location>,<platform>,<language>`，如 `2026-11-20,Friday,<城市>,App,中文`。
- 星期用**目标语言**写（中文场景写"星期五"）。地点可空；本项目可用"<城市>"或用户设置，拿不到就留空。
- 必须**紧挨**其后的 user 消息；不能插在 tool_use 与 tool_result 之间。
- 与 user 消息**成对持久化**，历史回传时一起带上（缓存友好）。

### 3.5 title
- 建新会话**首条** user 请求加 `title:{"enabled":true}`；拿到标题后**后续请求不再开**。
- 流式：从最后一个 chunk 的 `delta.title` 取；非流式：`message.title`。写入 `ConversationEntity.title`。
- 无标题（拒答/错误）时回退用 user 消息前几个字。

### 3.6 thinking 控制
- 主对话默认 `thinking:{"type":"disabled"}`（陪伴要快、要省）。
- 仅"深度回忆 / 情绪复盘"等复杂分析调用可开 `enabled` + `reasoning_effort:"high"`（可后续作为高级选项）。

---

## 4. 任务 1.2 API 调用设计增补（类 / 方法清单）

> 以下为**待实现**清单（现有 `ChatApiClient`/`ChatViewModel`/`ContextBuilder`/`ConfigRepository`/`ApiConfig`/`ProviderPresets` 基础上增量改）。落地时按"一个功能→停→打包"节奏分批。

### 4.1 DTO / 数据变更
- **`ApiMessageDto`**：`role` 支持新增值 `"latest_reminder"`（与 user/assistant/system 并列）。
- **`ChatRequest`（或等价请求体类）**新增可选字段：
  - `response_format: ResponseFormat?`（`{"type":"json_object"}`，为空则不传）
  - `title: TitleOption?`（`{"enabled": true}`）
  - `thinking: ThinkingOption?`（`{"type":"enabled"|"disabled"}`）
  - `reasoning_effort: String?`（`"low"|"high"|"max"`，仅 thinking=enabled 时传）
  - 工具字段（v1.1 再用）：`tools: List<ToolSpec>?`
- **数据类**：`data class ResponseFormat(val type: String = "json_object")`、`data class TitleOption(val enabled: Boolean = true)`、`data class ThinkingOption(val type: String)`。

### 4.2 `ApiConfig` 增字段（+ `ConfigRepository` 持久化 + `ProviderPresets` 默认）
- `thinkingEnabled: Boolean = false`（主对话默认关）
- `deepSeekTtsEnabled: Boolean = false`（TTS 通道开关，见 §5）
- `ttsVoice: String = "stella"`
- `latestReminderEnabled: Boolean = true`
- `autoTitleEnabled: Boolean = true`
- `jsonModeDefault: Boolean = false`（按调用场景切换，非全局）

### 4.3 `ChatApiClient` 增方法
```kotlin
// 主对话（流式）：保持现有 sendStream，请求体按 4.1 组装
suspend fun sendStream(
    messages: List<ApiMessageDto>,
    cfg: ApiConfig,
    opts: ChatOptions = ChatOptions(),   // 新增：jsonMode / titleEnabled / thinking
    onDelta: (String) -> Unit,
    onUsage: (Usage?) -> Unit,
    onTitle: (String?) -> Unit,          // 新增：取 delta.title
): Result<Unit>

// 结构化一次性调用（非流式，mood/记忆提取用）
suspend fun callJson(
    messages: List<ApiMessageDto>,
    cfg: ApiConfig,
    schemaHint: String,                  // 写入 prompt 的"输出JSON"说明
): Result<String>                        // 返回原始 JSON 串，由上层 Gson 解析
```

### 4.4 `ContextBuilder` 顺序 + latest_reminder 注入
```kotlin
object ContextBuilder {
    // 顺序：system(人设+长期记忆摘要) → 历史(含各轮reminder) → 本轮reminder → 本轮user
    fun build(
        persona: String,
        longMemoryDigest: String?,       // 长期记忆摘要（稳定前缀的一部分）
        history: List<MessageEntity>,    // 每条 user 若曾带 reminder 需回传
        currentUserText: String,
        currentReminder: String?,        // UserMetaMarker.current()
        budgetTokens: Int,
    ): List<ApiMessageDto>
}
```
- 稳定前缀 `system` 内容 = 人设 + 长期记忆摘要，**跨轮不变**才命中缓存。
- `currentReminder != null` 时，在最后一条 user 前插 `ApiMessageDto("latest_reminder", currentReminder)`。

### 4.5 新类 `UserMetaMarker`（纯函数，可单测）
```kotlin
object UserMetaMarker {
    // 例：2026-11-20,星期五,<城市>,App,中文
    fun build(
        date: LocalDate = LocalDate.now(),
        location: String = "",             // 取不到就空
        platform: String = "App",
        language: String = "中文",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String
    fun weekday(date: LocalDate, language: String): String  // 中文→星期五；en→Friday
}
```

### 4.6 title 提取与落库（`ChatViewModel`）
- 新建会话首条消息：`ChatOptions(titleEnabled = true)`；`onTitle` 回调里 `conversationDao.updateTitle(id, title ?: fallback)`。
- 已有标题则不再开 title。

### 4.7 json_object 调用点
- `MoodUpdater`（任务 5.1）：`ChatApiClient.callJson(...)` + `response_format=json_object`。
- `MemoryExtractor`（任务 2.2）：同上。

### 4.8 单测清单（纯逻辑，无需真机）
- `UserMetaMarkerTest`：日期/星期（中英文）/空地点格式正确；非法字符不入。
- `ContextBuilderTest` 扩展：稳定前缀在前、reminder 紧挨 user、历史 reminder 回传、裁剪后 system 仍保留。
- `ChatApiClientTest`：json_object/title/thinking 字段序列化正确；`delta.title` 解析；`chat` 无 `delta.title` 不崩。
- `TtsEngine` 选择/降级逻辑单测（见 §5）。

---

## 5. TTS 模块设计（任务 4.x）

```kotlin
interface TtsEngine {
    suspend fun speak(text: String, onDone: () -> Unit, onError: (Throwable) -> Unit)
    fun stop()
    fun isAvailable(): Boolean
}

// 系统 TTS（兜底，离线可用）
class SystemTtsEngine(context: Context) : TtsEngine { /* android.speech.tts.TextToSpeech + onInit 队列 */ }

// DeepSeek 官方 TTS（联网、按字符计费）
// POST https://api.deepseek.com/v1/tools/tts  body:{text,voice_id,format:"wav"}
// 返回音频流 → 写入临时 wav → MediaPlayer 播放；失败 onError
class DeepSeekTtsEngine(private val apiKeyProvider: () -> String) : TtsEngine { /* OkHttp 流式下载 + MediaPlayer */ }

// 管理器：按配置选择，官方失败自动降级系统
class TtsManager(cfg: ConfigRepository) {
    suspend fun speak(text: String) // deepSeekTtsEnabled && 有Key && 有网 → 官方；否则/失败 → 系统
}
```
- 设置页（任务 7.1）加「语音通道：系统 / DeepSeek 官方（联网计费）」。
- 费用提示：官方 TTS `$0.45/万字符`（约中文 5000 字），做开关并提示。
- 一次请求 ≤30 分钟、≤10000 计费字符；长文本分片。

---

## 6. 需修正的既有问题（实现期处理）

1. **识图模型名错误**：`ProviderPresets` 里识图默认写的 `deepseek-4.1-flash` **不是有效模型名**（官方只有 `deepseek-flash`、`deepseek-v4-pro`）。`deepseek-flash` 现**原生支持视觉**，识图直接用 `deepseek-flash` 即可，**不需要单独识图模型名**。→ 建议 `visionModel` 直接复用主模型 `deepseek-flash`，或删除该字段。
2. **旧模型名**：`deepseek-chat`/`deepseek-reasoner` 已下线，代码/预设里若出现要清掉。
3. **`safety_check`**：确认未开启。

---

## 7. v1.1 候选（已有官方 API，先不做，列出备用）

| 功能 | 接口 | 备注 |
|---|---|---|
| 语音输入（按住说话） | `POST /v1/tools/asr` | 16k WAV/Ogg，≤120s，自动识别中英 |
| 联网搜索（陪伴查实时信息） | `deepseek_web_search` 工具 | 关键词/手动触发；服务端搜索不再按次计费 |
| OCR（截图文字） | OCR 接口 | vision 一般已够 |
| DeepSeek 官方 TTS 正式启用 | `/v1/tools/tts` | v1 先做抽象，v1.1 打通并默认可选 |
| 缓存命中率优化 | 请求前缀策略 | 观测 `prompt_cache_hit_tokens` 调前缀 |

---

## 8. 参考（官方）

- 全家桶 API 公告：https://api-docs.deepseek.com/zh-cn/news/news261113/
- Models & Pricing：https://api-docs.deepseek.com/quick_start/pricing
- Build a Chatbot：https://api-docs.deepseek.com/guides/build_a_chatbot
- TTS：https://api-docs.deepseek.com/guides/tts ｜ ASR：https://api-docs.deepseek.com/guides/asr
- Search & Fetch：https://api-docs.deepseek.com/guides/search
- Vision：https://api-docs.deepseek.com/guides/vision
- Conversation Title：https://api-docs.deepseek.com/guides/conversation_title
- User Metadata Marker：https://api-docs.deepseek.com/guides/latest_reminder
- Change Log：https://api-docs.deepseek.com/updates
