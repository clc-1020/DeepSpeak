# 陪聊 APP 第二版开发计划（v3.4）

> **状态：历史** ｜ v2 施工依据；§3 接口契约仍有参考价值，实现细节以代码为准。
> 本文档随代码演进，细节以代码与本目录其他现行文档为准。

> **本文件是第二版的唯一施工依据。** v1（`陪聊APP开发计划.md`）只作历史参考。
> 施工进度记录在 `陪聊APP项目规则.md`，每完成一个任务更新一次。
> 第 3 章「接口契约」**不可协商**：任务正文可以调，契约不能各自改。

## 0. 版本与使用方式

### 0.1 变更记录

**v2 → v3**（并入两轮审查）

| 类别 | v2 | v3 |
|---|---|---|
| DB | 「version → 6」 | 6 → **7** + 显式 `Migration(6,7)`，删掉 `fallbackToDestructiveMigration()` |
| 追问字段 | 「增 4 个」 | 只加 `attemptCount` / `maxAttempts`（其余已存在） |
| `followupInterval` | 秒 | **分钟** |
| 常量 | `"FOLLOWUP"` / `"PENDING"` | 全小写 |
| 放弃状态 | `ABANDONED`（未定义） | 新增 `ReminderState.ABANDONED` |
| 节日日期 | 手写、整体差一年 | 构建期用农历库生成，单测锚定 |
| 定位 | `FusedLocationProviderClient` | `LocationManager.getLastKnownLocation()` |
| STT 降级 | 系统 STT 识别同段录音 | **删掉**（`RecognizerIntent` 只能接麦克风） |
| STT 方案 | 本地 Sherpa-ONNX 离线（232 MB 模型 + AAR）| **v3.4 改为 DeepSeek 云端 ASR**：同 key、零模型、约 8 元/年 |
| 前台 Service | `Application.onCreate()` 启动 | `MainActivity.onResume()` + `foregroundServiceType="specialUse"` |
| 保活命令 | `adb shell` + `pm grant` | 全改 `su -c`，删 `pm grant` |
| 闸门 | 「改 config 绕冷却」 | 已实现，删掉重复项 |
| 追问判定 | 未指定入口 | 合并进 mood 调用，**绕过 every-5-turns 闸门** |
| 追问 id | 无传递链路 | `PendingMessageContent.Followup` + `EXTRA_REMINDER_ID` |
| 环境去重 | 无 | `stableId` + 日期，**送达成功后记账** |
| 环境检查 | 挂 2h 周期 | 独立整点闹钟（不显示闹钟图标） |

**v3 → v3.1**（11 处修正 + 4 处补充）

| # | v3 的问题 | v3.1 的处理 |
|---|---|---|
| 1 | V2.2.3 首次闹钟写成 `kind=INSTRUCTION`，追问链根本不会启动 | 首次也用 `AlarmKind.FOLLOWUP` + `AlarmCodes.followup(id)` |
| 2 | `activeFollowup` 用 `triggerTime <= now`，间隔期内用户的回复被漏判 | 改判据为 `attemptCount > 0`（不动表结构） |
| 3 | 熬夜提醒去重键用当天日期，23:30 与 00:30 会连发两条 | 新增 `EnvironmentHook.dateKey(now)`：熬夜用 `now-4h` 的日期，20:00–03:59 算同一夜 |
| 4 | 节日钩子没有任何任务接进 `ProactiveCheckRunner` | 新增 **V2.1.7** |
| 5 | 去重回调需要消息本体，但 `onDelivered` 无参数 | 新增独立回调 `onDeliveredMessage(PendingMessageEntity)`，**不改现有 `onDelivered`** |
| 6 | `AlarmScheduling` 加参后现有 Fake 会崩，任务里没写 | V2.1.5 / V2.2.1 验收明确要求同步改 `ReminderServiceTest` 的 `FakeScheduler` |
| 7 | 第 3 章没有 `AppStateDao` 契约 | 新增 **§3.10**（`put(AppStateEntity)`，不是 `set`） |
| 8 | 节日顺延上限没实现，且与现有 `deferInsteadOfDiscard` 两套逻辑并存 | 新增 **§3.8** 统一规则 + 并入 V2.1.7 |
| 9 | V2.2.5 引用的图标不存在 | 前置步骤补 `ic_check.xml` / `ic_pause.xml` |
| 10 | Groovy 引号规则没写；AAR 核对路径写错；`pickFirsts` 只在边界表 | 写进 V2.3.1 |
| 11 | 生日日期正则没兜底 | V2.1.3 补 `11/20`、中文日期两条兜底 |

补充采纳：① 追问按钮补两个 vector drawable；② Groovy 引号规则明文；③ `pickFirsts` 落进任务；④ 日期正则兜底。

**v3.1 → v3.4**（2026-11-20）

- **v3.2**：新增 V2.1.8「对话天气可见」（`WeatherSummary.line()` + `CAPABILITIES` 声明天气能力），
  背景是用户实测问 AI「能看到我这边的天气吗」，AI 答看不到。
- **v3.3**：清掉 V2.1.7 的 4 个设计卡点——`anyPendingFollowup()`（与 `activeFollowup()` 是两个不同问题）、
  `Mutex` 只包「查+建」、`PendingMessageContent.Festival` 子类、勿扰分支跨日纠错（原文档把它和
  `deferInsteadOfDiscard` 混成一件，实际那是闸门分支）。
- **v3.4**：**V2.3 语音整条改为云端 ASR**（DeepSeek `POST /v1/tools/asr`，复用聊天 key）。
  删掉 Sherpa-ONNX 离线方案、232 MB 模型与 AAR 依赖；工作量 **4.5 天 → 1 天**，总工作量 21 → **17.5 天**。
  附录 B 的 B2 / B3 / B4 随之闭环作废（核实结论已归档，见附录 B）。

### 0.2 已核验的外部依赖 ✅

| 依赖 | 结论 |
|---|---|
| `com.k2fsa.sherpa.onnx:*` | **Maven Central 不存在**（`repo1.maven.org/maven2/com/k2fsa/` → 404）。**v3.4 起不再需要** |
| `sherpa-onnx-2.0.2.aar` | GitHub Release [v2.0.2](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2)（2026-11-19），52,321,252 字节，SHA-256 `6420cfb8…`；另有 `-rknn.aar`（瑞芯微 NPU）。**没有 `-android` 后缀的资产名**。**v3.4 起不再需要**（已核实：内含 4 ABI × 4 个 `.so`，含 `libonnxruntime.so`，与现有依赖冲突）|
| `cn.6tail:lunar` | Maven Central 存在，最新 **1.7.8（2026-11-05）**；仅构建期生成 JSON，**不进 APK** |
| Open-Meteo | 免 Key：天气 `api.open-meteo.com/v1/forecast`；地名 `geocoding-api.open-meteo.com/v1/search` |
| `MemoryLongDao.getByCategory(category)` | **已存在**（`MemoryLongDao.kt` L34–35），V2.1.3 不需要新增 DAO 方法 |
| `ReminderService` | 已存在（`data/alarm/ReminderService.kt`），是 `AlarmScheduling.schedule` 的现有调用点 |

### 0.3 施工前必读的三条硬约束

1. **现有 418 条单元测试必须保持全绿**。每完成一个任务跑一次 `testDebugUnitTest`，报告通过数。
2. **改 DAO 接口或 `AlarmScheduling` 签名 → 同步补所有手写 Fake**（本项目不用 Robolectric，全部手写替身）。漏一个就编译崩：
   - DAO 相关 Fake：`PendingMessageQueueTest` / `ProactiveDeliveryRunnerTest` / `ProactiveCheckRunnerTest` / `ProactiveTestTriggerTest` / `ProactiveDeliveryDndTest` / `PendingMessageQueueRescheduleTest`
   - `AlarmScheduling` Fake：`ReminderServiceTest.kt` 的 `FakeScheduler`（现在是 3 参 override）
3. **枚举加值后先编译**：`PendingMessageType` / `ProactiveMessageCategory` / `AlarmKind` 各加一个值，任何穷举 `when` 都会报错，先跑一遍 `compileDebugKotlin` 再改逻辑。

---

## 1. 项目信息与关键决策

- **技术栈**：Kotlin + Jetpack Compose Material3，包名 `com.companion.chat`
- **构建**：`minSdk 26` / `targetSdk 34` / `compileSdk 34`，AGP 8.1.0，Gradle 8.7，**Groovy DSL**（`app/build.gradle`）
- **目标系统**：Android（已 root）
- **基于版本**：v0.1（第一版阶段 0–7，418 测试全绿）
- **分工**：`domain/`、`data/`、`worker/`、构建/测试/文档 → 逻辑线；`ui/` 下所有 Compose → UI 线

### 1.1 关键技术决策

| 模块 | 决策 | 原因 |
|---|---|---|
| 环境感知 | Open-Meteo 免费 API + 纯规则判断 | 无需 Key、不调模型 |
| 定位 | `LocationManager.getLastKnownLocation(NETWORK_PROVIDER → GPS_PROVIDER)` | 项目**没有** `play-services-location`；国产 ROM 多数无 GMS |
| 特殊日期 | 长期记忆 `category=special_date` + 构建期生成 JSON | 不新增表；农历不手写 |
| 生日/纪念日 | **按月日（MM-DD）匹配、忽略年份** | 生日每年都要触发；按整串 `2026-11-20` 只生效一年 |
| 追问判定 | 与 mood 更新合并成**一次** `callJson`，有追问时绕过 every-5 闸门 | 省 API 费用 + 15 分钟粒度 |
| 语音输入 | **DeepSeek ASR 云端识别**（v3.4 由本地 Sherpa 改为云端）| 本地要 232MB 模型 + 多 3.5 天开发，不抵成本 |
| 语音回填 | 写 `app_state[voice_pending_text]` 再拉起主界面 | 透明 Activity 拿不到 `MainActivity` 的 ViewModel |
| 常驻入口 | 前台 Service（`specialUse`）+ `PRIORITY_LOW` 通知 | 不打扰、快速唤起 |
| 依赖装配 | 全部在 `CompanionApp` 里 `by lazy` | 项目无 Hilt/Dagger/kapt/ksp |

---

## 2. 功能清单

### 模块 1：环境感知 + 环境钩子

| 功能 | 说明 |
|---|---|
| 时间规则 | 夜间 `hour >= 23` 或 `< 5` → 熬夜；12:00±30 午饭；18:00±30 晚饭 |
| 天气规则 | 降水概率 >60% 带伞；最低温较昨日骤降 >5°C 添衣；最高温 >35°C 提醒 |
| 位置规则 | 每天首次打开前台更新缓存；手动城市后不再自动定位 |
| 检查频率 | 独立**整点**闹钟（`setAndAllowWhileIdle`，不显示闹钟图标） |
| 同日去重 | 同一 `stableId` 每天一次；**熬夜按"夜"计（`now-4h` 的日期）** |
| 关于页署名 | 「天气数据来自 Open-Meteo」+ 链接（CC BY 4.0） |

### 模块 2：特殊日期与节日

| 功能 | 说明 |
|---|---|
| 生日/纪念日 | `memory_long` 的 `category=special_date`，按月日重复 |
| 内置节日 | 公历（国庆/元旦/劳动节）+ 农历（春节/元宵/端午/七夕/中秋/重阳） |
| 农历处理 | 构建期生成 2026–2036 映射，JSON 入库，运行时零依赖 |
| 优先级 | `priority=3` |
| 当天时效 | 勿扰顺延最多到当天结束；跨次日则丢弃（`FESTIVAL_EXPIRED`） |

### 模块 3：追问型任务 / 监督链

| 功能 | 说明 |
|---|---|
| 触发判断 | 与 mood 合并成一次 `callJson`；有活跃追问时**强制调用** |
| 循环追问 | 到点发「任务没？」→ 判「完成 / 继续 / 放弃」→ 按间隔再问 |
| 次数上限 | 到点投队列时 `attemptCount+1`，超过 `maxAttempts`(5) 转 `abandoned`；**恰好发 5 条** |
| 超时 | 30 分钟无回复只重排，**不 +1、不发通知** |
| 优先级 | `priority=4` |
| 通知按钮 | 「✅ 已完成」「⏸ 再等等」 |

### 模块 4：语音输入（STT）

| 功能 | 说明 |
|---|---|
| 输入方式 | 底部语音按钮，长按录音、松手识别 |
| 引擎 | **DeepSeek ASR 云端**（`/v1/tools/asr`，复用聊天 key）；v3.4 起无本地模型 |
| 模型 | `app/src/main/assets/models/`，`noCompress`，首启拷到 `filesDir` |
| 交互 | 圆形动画 + 音量波形 → 结果填输入框 → 可编辑再发 |
| 权限 | `RECORD_AUDIO`（**首次从主界面申请**，不在透明 Activity 里弹） |

### 模块 5：通知栏常驻快捷入口

| 功能 | 说明 |
|---|---|
| 常驻通知 | `PRIORITY_LOW`，点击打开 `MainActivity` |
| 语音按钮 | → 透明 `VoiceInputActivity` → 识别 → 回填输入框 |
| 启动时机 | **`MainActivity.onResume()`** |
| 进程状态 | 前台 Service 常驻通知，进程仍可休眠 |

### 模块 6：root 保活

| 功能 | 说明 |
|---|---|
| 一键保活 | `su -c` 直执行受支持 CLI（不走 `adb`） |
| 非 root | 显示手动引导（6.2 已有） |
| 适配 ROM | MIUI / ColorOS / EMUI / Flyme |
| 厂商私有库 | 可选，失败只记日志，**不进验收** |

### 模块 7：钩子整合

| 功能 | 说明 |
|---|---|
| 双通道 | 环境钩子走整点闹钟；节日 → 记忆走 2h `ProactiveCheckWorker` |
| 顺序 | 2h worker 内：节日 → 记忆，取第一条 |
| 闸门 | 环境/节日/追问已绕过冷却与每日上限（3.4 已实现） |

---

## 3. 接口契约（不可协商）

### 3.1 数据库 v7 + 显式迁移

```kotlin
@Database(entities = [...], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminders ADD COLUMN maxAttempts INTEGER NOT NULL DEFAULT 5")
            }
        }
        fun getInstance(context: Context): AppDatabase = ...
            .addMigrations(MIGRATION_6_7)     // 必须
            .build()                           // 删除 fallbackToDestructiveMigration()
    }
}
```

- 只有 2 个新列；其他表一律不动。
- **删掉 `fallbackToDestructiveMigration()`**：留着等于"迁移写错就静默清库"，与「旧数据不丢失」直接矛盾。
  ⚠️ **但落地分两步**（v3.3 补充）：写 migration 时**先保留** fallback → instrumented 迁移测试全绿 → **再删**。
  理由：一次性删掉后，migration 里任何一处写错（表名 / 列名 / 默认值）都会让装上新包**一点就闪退**且无兜底路径；
  分两步则错误只在测试阶段暴露，不碰真实数据。

#### 3.1.1 迁移落地细节（v3.3，全部经核实）

1. **表名是复数 `reminders`**（`@Entity(tableName = "reminders")`）。写成 `reminder` → `no such table` → 升级当场闪退。
2. **`ALTER TABLE ... DEFAULT` 必须与 entity 的 `@ColumnInfo(defaultValue = ...)` 配对**
   （见 §3.2）。Room 在 migration 后会做**完整 schema 校验**（比较 `TableInfo.defaultValue`）：
   migration 写了 `DEFAULT 0` 而 entity 没写 `@ColumnInfo(defaultValue = "0")` →
   `IllegalStateException: Migration didn't properly handle: reminders` → **真机升级闪退**。
   SQLite 的 `ADD COLUMN NOT NULL` 必须有默认值，所以不能靠"不加 DEFAULT"绕过。
3. **`kapt { arguments { arg("room.schemaLocation", ...) } }` 是顶级块**，
   **不能**放进 `android { }` 里（否则 `Could not find method kapt()`）。
4. **schema 导出顺序（方案 D）**：先 `exportSchema = true` 且 **version 仍为 6** → 构建 → 得到 `6.json`；
   **再**改 `version = 7` + 写 migration → 构建 → 得到 `7.json`。
   这样 `MigrationTestHelper` 才有 v6 schema 可建库。
   `identityHash` 由静态注解决定、与 `exportSchema` 开关无关，所以从当前代码导出的 `6.json`
   与真机上已有的 v6 库**必然一致**（真机库就是当前代码建的 / 被 fallback 重建过）。
5. **依赖**：需新增 `androidTestImplementation "androidx.room:room-testing:2.6.0"`（当前没有）。
6. **测试断言必须用 `org.junit.Assert.*`，禁止裸 `assert`**：
   androidTest（ART）默认关闭断言 → `assert` **静默跳过** → 测试绿但什么都没验证。
   详见 `陪聊APP项目规则.md`「测试纪律（硬规则）」。

### 3.2 `ReminderEntity` / `ReminderState`

```kotlin
data class ReminderEntity(
    ... 现有字段不动 ...,
    /** 已发出的追问次数；在「FOLLOWUP 闹钟到点准备投队列」时 +1 */
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    /** 追问上限，默认 5 */
    @ColumnInfo(defaultValue = "5") val maxAttempts: Int = 5
)
```

> ⚠️ **`@ColumnInfo(defaultValue = ...)` 不是可选项**：migration 里 `ALTER TABLE ... ADD COLUMN ...
> NOT NULL DEFAULT 0` 会在表上留下 SQL 默认值，而 Room 迁移后做完整 schema 校验时会比对
> `TableInfo.defaultValue`。entity 不声明 → 校验报 `Migration didn't properly handle` → 真机升级闪退。
> 详见 §3.1.1 第 2 条。

```
ReminderState = pending / done / cancelled / abandoned(新增)
```

- `followupInterval` 单位 **分钟**（`Int?`）：15 表示 15 分钟，转毫秒 `* 60_000L`。
- `type` 用 `ReminderType.FOLLOWUP`（`"followup"`），**不要写字面量 `"FOLLOWUP"`**。
- **活跃追问的判据**（关键）：
  ```sql
  SELECT * FROM reminders
   WHERE state='pending' AND type='followup' AND attemptCount > 0
   ORDER BY triggerTime ASC LIMIT 1
  ```
  用 `attemptCount > 0` 而不是 `triggerTime <= now`：`advance()` 会把 `triggerTime` 推到未来，
  若按时间判，用户在**间隔期内**说「我吃了」就判不出来，下次又问一遍。
  `attemptCount > 0` 天然表示「已问过至少一次、正在等回复」，且首次闹钟还没响时不会误判。
- **同时只允许一个活跃追问**；新建前先查，已存在则跳过。

### 3.3 `PendingMessageType`（新增 `LATE_NIGHT`）

```kotlin
enum class PendingMessageType(val code: String, val priority: Int, val label: String) {
    INSTRUCTION("instruction", 5, "指令提醒"),
    FOLLOW_UP("followup", 4, "追问"),
    SPECIAL_DATE("special_date", 3, "特殊日期"),
    ENVIRONMENT("environment", 2, "环境"),
    LATE_NIGHT("late_night", 2, "熬夜提醒"),   // 新增
    CHITCHAT("chitchat", 1, "闲聊");
}
```

> 字段名保持 `priority` / `label` 不改（`PendingMessageQueue.enqueue` 里有 `priority = type.priority`）。
> 必须新增类型而不是在内容里做标记：闸门只能看到 `type` 的 code，
> 靠 `content.contains("LateNight")` **永远匹配不上**——`content` 存的是生成后的中文正文。

### 3.4 `PendingMessageContent`（新增三个变体）

```kotlin
sealed class PendingMessageContent {
    data class Memory(val memoryId: Long)                                       // memory:
    data class PlainText(val text: String)                                      // text:
    data class Instruction(val task: String, val memoryId: Long?)               // instr:
    data class Environment(val stableId: String)                                // env:       新增
    data class Followup(val reminderId: Long, val memoryId: Long?, val task: String) // followup: 新增
    data class Festival(val name: String)                                       // fest:      新增
}
```

| 变体 | 编码 |
|---|---|
| `Environment` | `env:<stableId>` |
| `Followup` | `followup:<reminderId>:<memoryId 或空>:<task>` |
| `Festival` | `fest:<name>` |

- 新分支的 `decode()` 放在 `else -> PlainText` **之前**；不改动已有前缀语义。
- `Followup` 用 `substringAfter(prefix).split(":", limit = 3)`（第三段保留 task 里的冒号）。
- ⚠️ `PendingMessageEntity.content` 是 **`String`**。送达层要用变体必须
  `PendingMessageContent.decode(msg.content) as? Environment`，**不能直接强转**。
- `Environment` **只带 `stableId` 不带正文**：正文仍由送达层生成，避免在过闸门之前就白烧一次 LLM 调用。
- **`Environment` 的数值只作触发条件，不持久化（B7 已拍板 = a）**：`Rain(80)` / `TempDrop(6)` /
  `HighTemp(38)` / `LateNight(23)` 里的数字只在 `EnvironmentRuleEngine.evaluate()` 内部判阈值用，
  `encode()` 只写 `env:<stableId>`；送达层拿到的是标签，正文一律走**泛化措辞**
  （「今天有雨，记得带伞」「今天挺冷的」「该睡了」）。
  - **禁止把数值拼进 `stableId`**（如 `rain_80`）：去重键 `env_sent_<stableId>_<dateKey>` 会随数值波动，
    同一场雨概率 `78% → 80% → 82%` 会被当成三件事各发一条。
  - 需要数字时**唯一**的升级路径（当前不实施）：`Environment(stableId, hint: String?)`，hint 由规则引擎
    产出（不额外调 LLM）、decode 按**第一个**分隔符切分、去重键仍只取 `stableId`。
    实际做文案时用 `hint ?: 泛化措辞` 兜底，向后兼容无 hint 的旧值。
  - ⚠️ `LlmProactiveMessageGenerator.resolveHookText()` 的 `Environment` 分支直接返回泛化中文短语，
    **不要去读天气缓存补数字**（那需要网络 + 会拖慢送达）。

### 3.5 闹钟：`AlarmKind` / `AlarmCodes` / `AlarmScheduling`

```kotlin
enum class AlarmKind { INSTRUCTION, FOLLOWUP, TIMEOUT, ENV_CHECK }

interface AlarmScheduling {
    fun schedule(                                   // 保持非 suspend
        requestCode: Int,
        triggerAtMillis: Long,
        taskDesc: String,
        reminderId: Long? = null,                   // 环境检查传 null，不用 0L 哨兵
        kind: AlarmKind = AlarmKind.INSTRUCTION
    )
    fun cancel(requestCode: Int)
}
```

- **默认参数写在接口上**，`AlarmScheduler` 的 override **不能重复写默认值**；这样现有 3 参调用点零改动。
- ⚠️ **但 Fake 必须改**：`ReminderServiceTest.kt:15–21` 的 `FakeScheduler` 是
  `override fun schedule(requestCode: Int, triggerAtMillis: Long, taskDesc: String)`，默认参数救不了它，
  必须补成 5 参（可与接口默认值对齐，不重复写默认值）。

`requestCode` 分配（集中在 `AlarmCodes`，带单测）：

| 用途 | 公式 |
|---|---|
| 首次 / 指令提醒 | `reminderId.toInt()`（**沿用现有约定，不改**，否则旧闹钟取消不掉） |
| 追问 | `1_000_000 + reminderId.toInt()` |
| 超时 | `2_000_000 + reminderId.toInt()` |
| 环境检查 | `999_999` |

**kind → AlarmManager API**：

| kind | API |
|---|---|
| `INSTRUCTION` / `FOLLOWUP` / `TIMEOUT` | `setAlarmClock`（用户自己设的提醒，状态栏显示闹钟图标是符合预期的） |
| `ENV_CHECK` | `setAndAllowWhileIdle`（每小时一次，若用 `setAlarmClock` 会在状态栏刷一整夜闹钟图标） |

### 3.6 `ReminderAlarmReceiver` 按 kind 分流

Intent extra：`EXTRA_REMINDER_ID: Long`、`EXTRA_KIND: String`（`AlarmKind.name`）。类名**不改**（改名字要动 manifest 和已排的 PendingIntent），文档里注明它已是「闹钟统一入口」。持锁契约沿用现有 `WakeLockScope.runHolding` + 10s `RECEIVER_TIMEOUT_MS`。

| kind | 行为 |
|---|---|
| `INSTRUCTION` | 沿用现有：建短期记忆 → 投 `INSTRUCTION` → 触发送达 |
| `FOLLOWUP` | `n = attemptCount + 1`；`n > maxAttempts` → `abandoned` + `cancelAll`，**不投队列**；否则 `incrementAttempt` → 投 `FOLLOW_UP`（`PendingMessageContent.Followup`）→ 排 `TIMEOUT`（now + 30 min） |
| `TIMEOUT` | **不投队列、不发通知、不改 `attemptCount`**；重排下一次 `FOLLOWUP`（now + `followupInterval * 60_000`） |
| `ENV_CHECK` | `EnvironmentHookService.recall()` → 有钩子则投队列 → 无论有无都排下一次整点 `ENV_CHECK` |

**计数语义（定死）**：`attemptCount` 只在 `FOLLOWUP` 投队列时 +1；`TIMEOUT` 和用户回复「继续」都**不 +1**。
从 0 起：第 1 次到点 → `n=1 ≤ 5` → 发第 1 条……第 5 次 → `n=5` → 发第 5 条；第 6 次到点 → `n=6 > 5` → 放弃。
→ **恰好发出 `maxAttempts`(=5) 条追问**。

**三个 code 的取消必须成套**：`cancelAll(id)` = `cancel(first)` + `cancel(followup)` + `cancel(timeout)`。漏一个就留僵尸闹钟。

### 3.7 闸门与送达层的勿扰豁免

`ProactiveMessageCategory` 新增末项：

```kotlin
enum class ProactiveMessageCategory {
    SPONTANEOUS, INSTRUCTION, FOLLOWUP, SPECIAL_DATE, ENVIRONMENT, LATE_NIGHT_ALERT
}

fun ofPendingType(code: String) = when (code) {
    "instruction" -> INSTRUCTION
    "followup" -> FOLLOWUP
    "special_date" -> SPECIAL_DATE
    "environment" -> ENVIRONMENT
    "late_night" -> LATE_NIGHT_ALERT
    else -> SPONTANEOUS
}
```

**闸门层**（`ProactiveGateRules.evaluate` 第 1 步）：

```kotlin
val dndExempt = category == INSTRUCTION || category == FOLLOWUP || category == LATE_NIGHT_ALERT
if (!dndExempt && ctx.dndEnabled && inQuietHours(ctx.nowMillis, ctx.dndStartMinute, ctx.dndEndMinute, zone)) {
    return ProactiveDecision.block(ProactiveBlockReason.DO_NOT_DISTURB)
}
```

**送达层**：只豁免 `LATE_NIGHT`。

```kotlin
if (!force && dndBlocksDelivery() && msg.type != PendingMessageType.LATE_NIGHT.code) { ...顺延... }
```

> ⚠️ **不要把 `INSTRUCTION` / `FOLLOW_UP` 加进送达层的豁免表**。指令提醒/追问在勿扰期是「顺延到区间结束」而不是「硬发」——这是 7.2 定好的语义，也是 `dnd_suppress_notification` 设置的意义。把它们硬发等于把用户的勿扰设置悄悄废掉。

配套新增设置项 `late_night_enabled`（默认开）：用户不想被夜间打扰就关它，「勿扰」与「熬夜提醒」的冲突就有了明确归属。

### 3.8 送达层顺延规则（统一，禁止两套并存）

`ProactiveDeliveryRunner` 里**只允许一个决策函数**：

```kotlin
private suspend fun resolveDeferralAction(msg: PendingMessageEntity, force: Boolean): DeferralAction
```

| 情形 | 行为 |
|---|---|
| `force`（测试模式） | 直接送 |
| 闸门拦 + `SPECIAL_DATE` / `ENVIRONMENT` | 顺延（**复用现有 `deferInsteadOfDiscard`**） |
| 闸门拦 + 其他类别 | `queue.discard(id)` |
| `dndBlocksDelivery()` 命中 + `LATE_NIGHT` | 不拦，直接送 |
| `dndBlocksDelivery()` 命中 + `SPECIAL_DATE`，且 `quietHoursEnd` **跨到次日** | `queue.discard(id)` → `FESTIVAL_EXPIRED`（当天已过，发"昨天春节快乐"语义错误） |
| `dndBlocksDelivery()` 命中 + 其他 | 顺延到 `quietHoursEnd`，回 `DND_DEFERRED` |

- 现有枚举是 `ProactiveDeliveryOutcome { EMPTY, BLOCKED, GENERATION_FAILED, DELIVERED, DND_DEFERRED }`；
  若要区分「丢弃原因」需新增 `DISCARDED` + 一个 `reason` 字段，**落地时一并补，不要留两套返回类型**。
- 队列没有 `updateState`：丢弃用 `discard(id)`，取消用 `cancel(id)`，顺延用 `reschedule(id, t)`。

### 3.9 通知：追问按钮

```kotlin
data class ProactiveNotification(
    val notificationId: Int,
    val title: String,
    val body: String,
    val conversationId: Long?,
    val completeMemoryId: Long? = null,
    val followupReminderId: Long? = null   // 新增
)
```

- `followupReminderId != null` → 加「已完成」「再等等」两个 action；
  否则**维持现有的单个「已完成」**（现在只有这一个，不存在「回复/忽略/别提这个」）。
- 图标：现有 `res/drawable/` 只有 `ic_notification.xml`，需新增 `ic_check.xml` / `ic_pause.xml`（见 V2.2.5 前置步骤）。
- `ReminderDoneReceiver` **扩展不改名**（改名会打断 manifest 与通知里已发出的 PendingIntent）：
  新增 `EXTRA_REMINDER_ID` + `EXTRA_ACTION`（`"done"` / `"postpone"`），原有 `EXTRA_MEMORY_ID` 分支保留。

### 3.10 `AppStateDao` 键值契约

```kotlin
@Dao interface AppStateDao {
    suspend fun put(state: AppStateEntity)      // ← 唯一写入口，不是 set(key, value)
    suspend fun get(key: String): String?       // 不存在返回 null
    suspend fun delete(key: String)
}
```

**禁止写 `appStateDao.set(k, v)`（不存在此方法）。** 写值一律 `put(AppStateEntity(k, v))`。

本版新增的键：

| 键 | 值 | 用途 |
|---|---|---|
| `env_sent_<stableId>_<dateKey>` | `"1"` | 环境钩子当天已发（**送达成功后才写**） |
| `manual_city_name` | 城市名 | 非空 = 手动城市模式，不再自动定位 |
| `manual_lat` / `manual_lon` | 经纬度 | 手动城市解析结果 |
| `voice_pending_text` | 识别文本 | 透明 Activity 回填输入框，`ChatViewModel` 读一次后 `delete` |
| `env_check_scheduled` | `"1"` | 整点环境闹钟是否已排（防每次进程启动重排） |

### 3.11 依赖装配约定

不引入 DI 框架。新类构造函数显式声明参数，在 `CompanionApp` 里 `by lazy` 装配：

```kotlin
val environmentHookService: EnvironmentHookService by lazy {
    EnvironmentHookService(
        ruleEngine = EnvironmentRuleEngine(),
        appStateDao = database.appStateDao(),
        weatherCache = weatherCache,
        locationCache = locationCache
    )
}
```

> `MoodService` 现有的 `callModel` 是 3 参 lambda 适配 4 参 `callJson` 的写法，**已经能编译，不要动**。
> `ChatApiClient.callJson` 签名顺序是 `(config, messages, schemaHint, opts)`。

---

## 4. 任务清单（按依赖顺序）

### 阶段 V2.1：环境感知层（8.5 天）

#### 任务 V2.1.1：环境数据采集 ✅ 已完成（2026-11-20）

- **内容**：
  - `data/environment/LocationCache`
    - `LocationManager.getLastKnownLocation(NETWORK_PROVIDER)` → 失败取 `GPS_PROVIDER` → 捕获 `SecurityException` 返回 null
    - **不引入 `play-services-location`**
    - `app_state[manual_city_name]` 非空 → 直接用 `manual_lat/lon`，不调定位
    - 每天首次打开前台更新一次
  - `data/environment/CityGeocoder`
    - `GET https://geocoding-api.open-meteo.com/v1/search?name={city}&count=1&language=zh&format=json`
    - 命中 → `put(AppStateEntity("manual_city_name", name))` + `manual_lat/lon`
    - 空结果 → Toast「找不到该城市，请检查拼写」，不更新缓存
    - 网络失败 → 保留原缓存 + Toast「网络异常，使用上次位置」
    - 清空输入 → `delete("manual_city_name")` → 恢复自动定位
  - `data/environment/WeatherCache`
    - `GET api.open-meteo.com/v1/forecast?latitude=&longitude=&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&past_days=1&timezone=auto`
    - 缓存 `SharedPreferences`，TTL 24h；超 24h 天气字段返回 null
    - 输出 `domain/environment/WeatherSnapshot`（V2.1.2 定义）
  - 权限：`ACCESS_COARSE_LOCATION`（manifest 已声明，只需运行时申请）
  - 失败兜底：定位失败 → 手动城市；天气失败 → 只用时间规则
- **依赖**：1.1
- **验收**：
  - 单测（MockWebServer）：正常响应 → `WeatherSnapshot` 解析正确；24h 过期 → null；HTTP 500 → null 且不崩
  - 单测（假 `CityGeocoder`）：命中/空结果/网络失败三条分支
  - 单测（假 `LocationCache`）：有手动城市时不调用定位
  - 真机：打开 App 拉到天气；后台只读缓存
- **复杂度**：★★★☆☆

#### 任务 V2.1.2：环境规则引擎（纯逻辑，无任何 Android 依赖）✅ 已完成（2026-11-20）

- **内容**：
  - `domain/environment/WeatherSnapshot`：`date: LocalDate`、`tempMax: Int?`、`tempMin: Int?`、`precipProbabilityMax: Int?`
  - `domain/environment/EnvironmentHook`
    ```kotlin
    sealed class EnvironmentHook {
        abstract val stableId: String
        /** 去重用的"日"：熬夜按夜计（now-4h），其余按当天 */
        abstract fun dateKey(nowMillis: Long, zone: ZoneId): String
        data class LateNight(val hour: Int) : EnvironmentHook() {
            override val stableId = "late_night"
            override fun dateKey(nowMillis: Long, zone: ZoneId) =
                Instant.ofEpochMilli(nowMillis - 4 * 3_600_000L).atZone(zone).toLocalDate().toString()
        }
        data class Rain(val probability: Int) : EnvironmentHook() { override val stableId = "rain"; /* 默认当天 */ }
        data class TempDrop(val delta: Int) : EnvironmentHook() { override val stableId = "temp_drop" }
        data class HighTemp(val temp: Int) : EnvironmentHook() { override val stableId = "high_temp" }
        data class Mealtime(val type: String) : EnvironmentHook() { override val stableId = "mealtime_$type" }
    }
    ```
  - `domain/environment/EnvironmentHooks`（去重键的**唯一出处**，纯函数；`data/` 侧与送达回调都调它）
    ```kotlin
    object EnvironmentHooks {
        const val LATE_NIGHT = "late_night"
        /** 去重用的"日"：熬夜按夜计（now-4h 的日期，20:00–03:59 算同一夜），其余按当天 */
        fun dateKeyOf(stableId: String, nowMillis: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(if (stableId == LATE_NIGHT) nowMillis - 4 * 3_600_000L else nowMillis)
                .atZone(zone).toLocalDate().toString()
    }
    ```
  - `domain/environment/EnvironmentRuleConfig`：`lateNightHour=23`、`nightEndHour=5`、`rainThreshold=60`、`tempDropThreshold=5`、`hotThreshold=35`、`mealtimeWindowMinutes=30`
  - `domain/environment/EnvironmentRuleEngine.evaluate(now: LocalDateTime, today: WeatherSnapshot?, yesterday: WeatherSnapshot?, cfg): List<EnvironmentHook>`
    - 时间：`hour >= lateNightHour || hour < nightEndHour` → `LateNight`；12:00±30 → `Mealtime("lunch")`；18:00±30 → `Mealtime("dinner")`
    - 天气（`today` 非空才算）：降水 > `rainThreshold` → `Rain`；`today.tempMin - yesterday.tempMin > tempDropThreshold` → `TempDrop`；`today.tempMax > hotThreshold` → `HighTemp`
    - **返回按优先级排序的列表**：熬夜 > 带伞 > 添衣 > 饭点 > 高温（调用方取第一条）
  - 入队类型映射交由 V2.1.4 决定（`LateNight → LATE_NIGHT`，其余 → `ENVIRONMENT`）
- **依赖**：无（`WeatherSnapshot` 由本任务定义，V2.1.1 去填）
- **验收**（16 条单测，主机可 100% 跑完）：
  - 夜间边界：`23:00` / `23:30` / `00:30` / `04:59` 命中；`22:59` / `05:00` 不命中
  - 饭点：`11:30` / `12:00` / `12:29` → lunch；`12:30` 不含；`18:00` → dinner
  - 天气三阈值：`60/61/59`、`5/6/4`、`35/36/34` 各一条
  - 排序：同时满足熬夜+下雨+高温 → `[LateNight, Rain, HighTemp]`
  - 缺数据：`today=null` → 只返回时间类；`yesterday=null` → 不返回 `TempDrop`
  - `stableId` 五个逐一断言，且**断言不含数值**：`Rain(80).stableId == "rain"`、`TempDrop(6).stableId == "temp_drop"`、`HighTemp(38).stableId == "high_temp"`（B7-a 的回归保护）
  - `dateKey`：`23:30` 与次日 `00:30` 的 `LateNight.dateKey` **相同**；`Rain` 的 `dateKey` 是当天
- **复杂度**：★★☆☆☆

#### 任务 V2.1.3：特殊日期匹配 ✅ 已完成（2026-11-20）

- **内容**：
  - `app/src/main/assets/calendar/festivals_2026_2036.json`（**脚本生成一次，JSON 提交入库**）
    ```json
    {
      "lunar": [ {"name":"春节","dates":["2026-02-17","2027-02-06","2028-01-26"]}, {"name":"元宵","dates":[...]},
                 {"name":"端午","dates":[...]}, {"name":"七夕","dates":[...]}, {"name":"中秋","dates":[...]},
                 {"name":"重阳","dates":[...]} ],
      "solar": [ {"name":"国庆","month":10,"day":1}, {"name":"元旦","month":1,"day":1}, {"name":"劳动节","month":5,"day":1} ]
    }
    ```
  - 生成方式：`tools/genfestivals`（一次性 JVM 程序，依赖 `cn.6tail:lunar:1.7.8`）→
    `Lunar.fromYmd(year, month, day).solar.toYmd()` 逐条展开。
    **不把 lunar 加进 `app/build.gradle`**，**不引入 `buildSrc`**（`project(':buildSrc')` 不成立），
    **挂到 `preBuild`** 也算过度设计（每次构建都跑 + 让构建依赖私有库）。
  - `data/calendar/FestivalProvider`：加载 JSON → `getTodayFestivals(LocalDate): List<Festival>`
    - 农历按 `dates.contains(toString())`；公历按 `month/day` 匹配
  - `domain/proactive/SpecialDateMatcher`（**纯函数，不碰 DAO**）
    - 输入：`LocalDate` + 已解析的用户日期（`List<Pair<String, MonthDay?>>`）+ 节日列表
    - 规则：**按月日匹配、忽略年份**
    - 解析顺序（**都要兜底**）：
      1. `yyyy-MM-dd` → 取 MM-DD
      2. `MM-dd`
      3. `MM/dd`（斜杠）
      4. `M月d日`（中文）
      - 都匹配不到 → 该条忽略，不抛异常
  - `data/proactive/SpecialDateHookService`：`MemoryLongDao.getByCategory("special_date")`（**已存在，不需新增**）→ 解析 → 交 matcher
- **依赖**：1.1、2.2
- **验收**：
  - 单测：`2026-02-17` / `2027-02-06` / `2028-01-26` → 春节；`2026-10-01` → 国庆
  - 单测：`content="生日 2026-11-20"` 在 `2027-11-20` / `2028-11-20` **仍然命中**
  - 单测：`"生日 11-20"`、`"生日 11/20"`、`"生日 11月20日"` 三种格式都命中
  - 单测：无法解析的 `content` 被忽略，不崩
  - JSON 覆盖 2026–2036 全部年份
- **复杂度**：★★★☆☆

#### 任务 V2.1.4：环境钩子投递 + 同日去重 ✅ 已完成（2026-11-20）

- **内容**：
  - `data/proactive/EnvironmentHookService`
    - `recall(): EnvironmentHook?`：`evaluate()` 取第一条 → 查 `app_state[env_sent_<stableId>_<dateKey>]`，存在则返回 null
    - **`recall()` 只读不写**
    - `enqueue(hook)`：`LateNight → PendingMessageType.LATE_NIGHT`，其余 → `ENVIRONMENT`；
      `queue.enqueue(type, PendingMessageContent.Environment(hook.stableId), triggerTime = now)`
  - `ProactiveDeliveryRunner`：**新增独立回调**，不动现有 `onDelivered`
    ```kotlin
    private val onDeliveredMessage: suspend (PendingMessageEntity) -> Unit = {}
    ```
    在送达成功处（与现有 `if (!force) onDelivered()` 同一位置）调用 `onDeliveredMessage(msg)`
  - `CompanionApp` 接线（`msg.type` 是 **String**，比较要 `.code`；去重键用纯函数算，别在回调里拼字符串）：
    ```kotlin
    onDeliveredMessage = { msg ->
        if (msg.type == PendingMessageType.ENVIRONMENT.code ||
            msg.type == PendingMessageType.LATE_NIGHT.code) {
            val env = PendingMessageContent.decode(msg.content) as? PendingMessageContent.Environment
            if (env != null) {
                val day = EnvironmentHooks.dateKeyOf(env.stableId, now(), ZoneId.systemDefault())
                appStateDao.put(AppStateEntity("env_sent_${env.stableId}_$day", "1"))
            }
        }
    }
    ```
- **依赖**：V2.1.2
- **验收**：
  - 单测：同一 `stableId` 同一 `dateKey` 第二次 `recall()` 返回 null；换次日又返回
  - 单测：闸门拦掉 → 回调没跑 → 账没记 → 下一轮仍可发
  - 单测：`Environment("rain").encode() == "env:rain"`，decode 往返一致；`decode` 失败不崩
  - 现有 `onDelivered` 相关的既有用例保持不变（不回归）
- **复杂度**：★★☆☆☆

#### 任务 V2.1.5：整点环境检查闹钟 ✅ 已完成（2026-11-20）

- **内容**：
  - `AlarmKind` / `AlarmCodes` / `AlarmScheduling` 扩展（见 §3.5）
  - `data/proactive/EnvironmentCheckScheduler.nextWholeHour(nowMillis): Long`（纯函数）
  - `AlarmScheduler`：`kind == ENV_CHECK` → `setAndAllowWhileIdle`；其余 → `setAlarmClock`
  - `ReminderAlarmReceiver` 的 `ENV_CHECK` 分支：`recall()` → 有则投队列 + 触发送达 → 无论有无都排下一次
  - 首次排钟：`CompanionApp.onCreate()` 里 `if (环境关心开 && app_state[env_check_scheduled] 空) { 排一次 + put("env_check_scheduled","1") }`
  - ⚠️ **同步更新** `ReminderServiceTest.kt` 的 `FakeScheduler` 为 5 参
- **依赖**：V2.1.4
- **验收**：
  - 单测：`nextWholeHour(10:37) == 11:00`、`nextWholeHour(11:00) == 12:00`、`23:30 → 次日 00:00`
  - 单测：`FakeScheduler` 记录了 `kind`/`reminderId`
  - 真机：`dumpsys alarm` 能看到 `env_check`，且**状态栏不出现闹钟图标**
- **复杂度**：★★★☆☆

#### 任务 V2.1.6：设置项（逻辑线只交 Repository 键）✅ 已完成（2026-11-20）

- **内容**：
  - `ConfigRepository` 新增键（**逻辑线交付**）：`environment_enabled`(true)、`late_night_enabled`(true)、
    `manual_city_name`、`late_night_hour`(23)、`rain_threshold`(60)、`temp_drop_threshold`(5)
  - 传给 UI 线的接口约定：`SettingsForm` 增加 4 个字段（`environmentEnabled` / `lateNightEnabled` /
    `rainThreshold` / `tempDropThreshold`）+ 城市输入框回调；**Compose 由 UI 线实现**
  - 关于页署名：「天气数据来自 Open-Meteo（https://open-meteo.com）」+ 可点链接（UI 线）
- **依赖**：V2.1.1、7.1
- **验收**：
  - 逻辑线：单测覆盖 6 个键的读写与默认值
  - UI 线：关于页署名可点开浏览器；开关联动生效
- **UI 侧落定（已实现，不要再当 bug 改）**：
  - 降水阈值滑块**下限 1**、上限 100：`EnvironmentRuleEngine` 是 `precip > threshold`，阈值 0 等于
    「只要预报有降水概率就提醒」→ 天天喊带伞。数据层仍允许 0–100，只收 UI 下限。
  - 清空手动城市输入框**不弹 Toast**（`CityGeocoder` 的 `Cleared` 态），只清空并恢复自动定位。
  - `lateNightHour` **不给编辑入口**（数据层注释「v2 不对外开放编辑」），但 persist 时保留原值，
    不得用默认值覆盖。
- **复杂度**：★☆☆☆☆

#### 任务 V2.1.7：节日钩子接入 + 当天时效 ✅ 已完成（2026-11-20）

- **内容**：
  - `PendingMessageContent` 新增 `Festival(name)`（契约见 §3.4，`fest:` 前缀）
  - 修改 `data/proactive/ProactiveCheckRunner`（现有：闸门 → 记忆钩子 → 投队列）
    - 新构造签名（默认值保证现有调用点零改动；`CompanionApp` 用的是**命名参数**，插参安全）：
      ```kotlin
      class ProactiveCheckRunner(
          private val gate: ProactiveGate,
          private val recallFestival: (suspend () -> String?)? = null,  // 节日/纪念日名；null=今天没有
          private val recallHook: suspend () -> Long?,
          private val queue: PendingMessageQueue,
          private val checkSent: suspend (dedupeKey: String) -> Boolean = { false },
          private val now: () -> Long = { System.currentTimeMillis() },
          private val log: (String) -> Unit = { ... }
      )
      ```
      ⚠️ `checkSent` 的参数是**完整去重键**（`fes_sent_<name>_<dateKey>`），不是裸 name——
      日期由 runner 用自己的 `now()` 派生，保证可注入、测试能钉住跨日边界
    - 分流顺序（节日优先，逐条短路）：
      1. `recallFestival?.invoke()`；null → 走记忆分支
      2. 非空 → 算 `dateKey`（`Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()`）
      3. 已发过？`checkSent("fes_sent_<name>_<dateKey>")` 为 true → `ALREADY_SENT`
      4. **防重复入队**（同一轮内的洞）：
         `queue.hasPendingWithContent(PendingMessageContent.Festival(name).encode())` 为 true → `ALREADY_SENT`
         - 用**现成 API**（`PendingMessageDao.countPendingByContent`，精确 `content` 匹配一条 SQL）
         - **不要**引入 `fes_queued_*` 标记：该标记在消息被丢弃/取消后会残留，导致当天再也不发
           （正是 V2.1.4 定案要避免的「投了队列就记为已发」）
      5. **闸门预筛**：`gate.allow(SPECIAL_DATE)` 为 false → `GATE_BLOCKED`，**不入队**
         - 为什么预筛：runner 是**周期性**的（每 2h），这次被拦下次检查会重试，不需要送达层 defer 兜底；
           不预筛则总开关长期关闭时队列会堆积永远送不出的消息
         - 送达层闸门仍是第二道防线（防「入队后用户才改设置」）
      6. 入队：`type = SPECIAL_DATE`（`priority=3` 由枚举自带，**不传**）、`content = Festival(name)`
    - 记忆分支保持现状（`gate.allow(SPONTANEOUS)` → `recallHook()` → `CHITCHAT + Memory(id)`）
    - 节日被拦**不回退**去试记忆：节日只受总开关 + 勿扰，而勿扰对所有类别都生效，回退纯属浪费
  - `ProactiveCheckOutcome` 增加 `ALREADY_SENT`（区分「今天没节日」与「节日已发/已排队」，便于日志与断言）
  - ⚠️ `recallFestival` 必须覆盖 `SpecialDateHit` 的**两个子类**（V2.1.3 已交付）：
    `User(name)`（用户自己存的生日/纪念日）与 `Festival(name)`（内置节日）。
    **只取 `is Festival` 的话，用户生日永远发不出去**（违反 §8 验收「生日存 2026-11-20 → 2027-11-20 仍收到祝福」）。
    实现：`specialDatesOn(LocalDate.now()).firstOrNull()?.name`（两个子类都有 `name`）
  - 送达层节日时效（**注意：改的是勿扰分支，不是 `deferInsteadOfDiscard`**）：
    - 要改的位置是 `deliver()` 的勿扰分支（`dndBlocksDelivery()` 命中、走 `reschedule(id, end)` 那段）
    - `SPECIAL_DATE` 且 `dndEndMillisProvider()` 的结束时刻**跨到次日** → `queue.discard(id)` + `FESTIVAL_EXPIRED`
    - 当天内 → 维持现有 `reschedule` + `DND_DEFERRED`
    - **`deferInsteadOfDiscard` 一行不动**：它服务的是「闸门拦截」分支，顺延目标是 `endOfLocalDay`（当天末），
      本来就不可能跨日。v3.1 原文把它和勿扰分支混成同一件事了
  - `ProactiveDeliveryOutcome` 增加 `FESTIVAL_EXPIRED`
  - 送达成功后的记账：在现有 `onDeliveredMessage(msg)` 的**接线处**（`CompanionApp`）按 `msg.content` 分发，
    新增 `fes_sent_<name>_<dateKey>`；**dateKey 必须用 `msg.triggerTime` 派生**（不是送达时刻）：
    节日可能被 defer 到次日，用送达时刻会把键写到错误日期 → 次日再发一遍
    写入用 `appStateDao.put(AppStateEntity(key, "1"))`（§3.10：**没有 `set`**）
  - 防御（建议做）：送达层 decode 出 `PlainText`、且文本以已知 tag（`memory:`/`instr:`/`env:`/`fest:`）开头
    → 说明是不认识的新 tag，`queue.discard()` 而不是把 `"fest:春节"` 当正文发出去。
    **不要**改 `decode()` 的 `else -> PlainText` 兜底契约：个人自用无版本共存，改成抛异常会击穿
    `memoryIdOrNull` / 送达层 `as?` 等现有调用点，风险远大于收益
- **依赖**：V2.1.3（**已完成**）、3.2、3.4
- **验收**：
  - 单测：有节日时不召回记忆钩子（用计数断言 `recallHook` 调用次数 = 0）
  - 单测：`SpecialDateHit.User` 也走节日分支（覆盖用户生日，不只是内置节日）
  - 单测：同一节日当天第二次 → `ALREADY_SENT`，不投队列
  - 单测：队列里已有同 `content` 的未发节日 → `ALREADY_SENT`（防重复入队）
  - 单测：`gate.allow(SPECIAL_DATE)` 为 false → `GATE_BLOCKED` 且队列为空
  - 单测：`checkSent` 收到的是完整键 `fes_sent_春节_2026-02-17`
  - 单测：勿扰结束跨次日 → `FESTIVAL_EXPIRED` + `discard`；当天内 → `reschedule`
  - 单测：送达回调按 `triggerTime` 派生 dateKey（跨午夜不错位）
- **复杂度**：★★★☆☆

#### 任务 V2.1.8：对话天气可见（v3.2 新增，2026-11-20 已实现）

- **背景**：用户实测问 Ai「能不能看到我这边的天气」，Ai 答看不到——V2.1.1–V2.1.6 只做
  「天气→主动提醒」，对话上下文（`latest_reminder` 时间注记）没有天气，`CAPABILITIES` 也没
  声明天气能力，模型只会老实说不知道。
- **内容**：
  - `domain/environment/WeatherSummary.line(snapshot): String?`（纯函数）：快照 → 中文短语
    `18~25°C 降水概率10%`；缺字段逐级降级（双温 > 单温 > 仅降水 > null）。**不编造**——
    Open-Meteo 当前请求未取天气状况字段，没有就是没有，不猜「晴/多云」。
  - `UserMetaMarker.build()` 新增可选参数 `weather`，**并入第 3 段（location）**，段数恒为 5：
    `2026-11-20,星期五,佛山市 12~18°C 降水概率98%,App,中文`。
    ⚠️ **绝对不要新增第 6 段**——1.1.0 曾这样做，真机上该请求直接失败（HTTP 400、界面只弹「重试」），
    而同一会话此前 5 段的请求全部成功；官方 `latest_reminder` 结构固定为 5 段。
  - `ContextBuilder.CAPABILITIES` 新增第 4 条「天气」：注记里的天气是真实数据，可直接回答
    「今天热不热/会不会下雨/要带伞吗」；注记无天气段 = 这会儿没拿到（定位/网络），说不知道，不要编。
  - `ChatViewModel.handleSend`：拼 reminder 时调 `weatherLine()` —— **只用 `WeatherCache.peek()`
    （只读缓存、绝不联网）**，另在聊天页 `init` 里 `prefetchWeather()` 后台预热。
    ⚠️ 不要在发送路径上调 `weatherCache.load()`：缓存缺失时会现场发 HTTP（readTimeout 20s），
    真机表现为「点发送后消息迟迟不上屏」（实测该次请求把整个发送挡住，`weather_cache_at`
    与消息 timestamp 只差 2ms）。全程 `runCatching`，天气失败绝不影响主对话。
- **分工**：纯逻辑线（`domain/`）+ 装配线（`ChatViewModel`，本会话全做，无 UI 改动）
- **依赖**：V2.1.1（WeatherCache/LocationCache）、V2.1.2（WeatherSnapshot）
- **验收**：
  - 单测：`WeatherSummaryTest` 7 条（双温+降水 / 缺降水 / 仅最高 / 仅最低 / 仅降水 / 全缺 null / null 快照）
  - 单测：`WeatherCacheTest` +4（`peek` 新鲜命中 / 无缓存 / 过期不重拉 / 畸形 JSON）
  - 单测：`UserMetaMarkerTest` 验证「天气并入第 3 段、段数恒 5、逗号净化」
  - 真机：有定位或手动城市 → 问 Ai「今天天气怎么样」→ 能按注记里的温度/降水回答；
    飞行模式或清空城市 → 问同样问题 → Ai 说不知道（不编造）
  - 真机：**发消息无可感知延迟**（发送路径零网络等待）；请求失败时 logcat 能看到
    `W/ChatApiClient 流式请求失败: ...`（含 HTTP 状态）
  - 真机：有定位或手动城市 → 问 Ai「今天天气怎么样」→ 能按注记里的温度/降水回答；
    飞行模式或清空城市 → 问同样问题 → Ai 说不知道（不编造）
- **复杂度**：★☆☆☆☆

---

### 阶段 V2.2：追问型任务 / 监督链（5.5 天）

#### 任务 V2.2.1：数据结构与迁移 ✅ 已完成（2026-11-20，审查通过）

- **内容**：
  - `AppDatabase` 6 → 7 + `MIGRATION_6_7`（§3.1 / **§3.1.1 六条落地细节**）
    - 按 §3.1.1 的顺序做：表名用复数 `reminders` → entity 加 `@ColumnInfo(defaultValue)` →
      `exportSchema = true`（**version 仍 6**）构建导出 `6.json` → 升 `version = 7` + 写 migration 导出 `7.json` →
      写 instrumented 迁移测试 → **测试全绿之后才删 fallback**
    - ⚠️ **禁止**「一次性删 fallback 再测」：migration 写错就没有兜底，装上新包一点即闪退（见 §3.1）
  - `ReminderEntity` 加 `attemptCount` / `maxAttempts`（§3.2）
  - `ReminderState` 加 `ABANDONED`
  - `ReminderDao` 新增：
    ```kotlin
    @Query("""SELECT * FROM reminders
              WHERE state='pending' AND type='followup' AND attemptCount > 0
              ORDER BY triggerTime ASC LIMIT 1""")
    suspend fun activeFollowup(): ReminderEntity?

    @Query("UPDATE reminders SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttempt(id: Long)

    @Query("UPDATE reminders SET triggerTime = :triggerTime WHERE id = :id")
    suspend fun updateTriggerTime(id: Long, triggerTime: Long)

    /**
     * 有没有「还没结束」的追问链（含刚创建、还没到点、attemptCount 仍为 0 的）。
     * 只用于 V2.2.3 防重复创建，判据只要 state=pending。
     */
    @Query("SELECT * FROM reminders WHERE state='pending' AND type='followup' LIMIT 1")
    suspend fun anyPendingFollowup(): ReminderEntity?
    ```
    > `activeFollowup()` **不带 `now` 参数**，判据是 `attemptCount > 0`（§3.2 说明了原因）
    >
    > ⚠️ **这两个查询回答的是两个不同问题，不许互相替代**（v3.2 补充）：
    >
    > | 查询 | 判据 | 回答 | 消费者 |
    > |---|---|---|---|
    > | `activeFollowup()` | `pending` + `attemptCount > 0` | 「要不要在对话里问用户做完了没」 | V2.2.2 `MoodService` |
    > | `anyPendingFollowup()` | 只要 `pending` | 「能不能再建一条追问」 | V2.2.3 `FollowupTaskExtractor` |
    >
    > 用 `activeFollowup()` 做防重复会漏掉「刚建还没到点」的那条 → 用户改口/手滑就产生两条并行追问链，
    > 每天被催两遍。。
  - 约束：同时只允许一个活跃追问
- **依赖**：1.1、3.3
- **验收**：
  - **instrumented 迁移测试**（`androidTest/.../AppDatabaseMigrationTest`，需真机）：
    - v6 建库插入一行 → `runMigrationsAndValidate(testDb, 7, true, MIGRATION_6_7)` → 旧数据仍在
    - 新列默认值正确（`attemptCount = 0`、`maxAttempts = 5`）
    - 断言**必须**用 `org.junit.Assert.*`（禁裸 `assert`：androidTest 里静默跳过 → 假绿。见 §3.1.1 第 6 条）
  - **真机真实升级**：装 v6 版 → 用一会儿产生数据 → 装 v7 版 → 数据仍在、不闪退
  - 单测（**新建** `FakeReminderDao`）：`incrementAttempt` 累加；`activeFollowup` 只返回到点后已发过至少一次的
  - 单测（`FakeReminderDao`）：`anyPendingFollowup` 在 `attemptCount = 0`（刚建还未到点）时**也返回非 null**
  - ⚠️ **更正（v3.3）**：`ReminderDao` 目前**没有任何 Fake 实现**（全测试目录 grep `: ReminderDao` 0 命中），
    所以是**新建 1 个** `FakeReminderDao`，不是原文说的「6 个 Fake DAO 补这 4 个方法」
- **复杂度**：★★☆☆☆

#### 任务 V2.2.2：追问判断（合并进 mood 调用）✅ 已完成（2026-11-20，审查通过）

- **内容**：
  - `domain/mood/MoodPromptBuilder.build(recent, currentMood, activeFollowup: FollowupContext?)`
    - `null` → 只要求 `{mood, mood_reason}`
    - 非空 → 追加 `{followup_status: completed|continue|abandoned, followup_reason}`
  - `domain/mood/MoodUpdateParser` 增 `followupStatus` / `followupReason`
  - `domain/reminder/FollowupPort`（端口，便于 JVM 测试）
    ```kotlin
    interface FollowupPort {
        suspend fun active(): ActiveFollowup?
        suspend fun complete(reminderId: Long)   // updateState(DONE) + cancelAll
        suspend fun abandon(reminderId: Long)    // updateState(ABANDONED) + cancelAll
        suspend fun advance(reminderId: Long)    // 取消挂起的 TIMEOUT + 排下一次 FOLLOWUP
    }
    data class ActiveFollowup(val reminderId: Long, val task: String, val stopCondition: String?)
    ```
  - **`data/alarm/FollowupAlarms`（提前到本任务交付）**：`FollowupAlarms` 的薄封装
    `scheduleFollowup(id, at)` / `scheduleTimeout(id, at)` / `cancelAll(id)`，内部只用
    `AlarmScheduling` + `AlarmCodes`（**不需要 Context**，`AlarmScheduler` 已在 `CompanionApp` 里造好）。
    提前交付的理由：下面那个真实实现类要用它，留给 V2.2.4 会导致 V2.2.2 无法独立编译完成
  - **`data/reminder/FollowupPortImpl`（本任务交付真实实现）**
    - `active()` → `ReminderDao.activeFollowup()`（判据 `attemptCount > 0`）
      ⚠️ 它的语义是「这条链已经催过至少一次，现在问用户进度合理」。**防重复创建不能用它**，
      那是 V2.2.3 的 `anyPendingFollowup()`；两个查询的分工见 V2.2.1 的对照表
    - `complete(id)` → `updateState(id, DONE)` + `FollowupAlarms.cancelAll(id)`
    - `abandon(id)` → `updateState(id, ABANDONED)` + `FollowupAlarms.cancelAll(id)`
    - `advance(id)` → `FollowupAlarms.cancel(AlarmCodes.timeout(id))` → `updateTriggerTime(now + interval*60_000)` → `scheduleFollowup(id, newAt)`
  - **`CompanionApp` 接线（本任务必做，不许留 null）**
    - `MoodServiceFactory.create(appStateDao, chatClient, followup = FollowupPortImpl(...))`
    - 依赖全部现成：`database.reminderDao()`、`alarmScheduler`、`pendingMessageQueue`
    - ⚠️ **反例警告**：若图省事不接（默认 null），V2.2.2 的单测会全绿（测试自己塞假 port），
      但真机上 `followup?.active()` 恒为 null，追问链**永不启动**。这是本任务唯一的假完成陷阱
  - `data/mood/MoodService`
    - 构造新增 `followup: FollowupPort?`（默认 null，旧测试零改动）
    - **绕过 every-5 闸门**：
      ```kotlin
      val active = followup?.active()
      val shouldCall = active != null || MoodCounter.shouldUpdate(count, everyTurns)
      if (!shouldCall) return false
      ```
    - 结果处理：
      | followup_status | 动作 |
      |---|---|
      | `completed` | `complete(id)` |
      | `abandoned` | `abandon(id)`（**保留 `ABANDONED` 状态，不要记成 DONE**） |
      | `continue` | `advance(id)`（**不 incrementAttempt**，次数已在到点发消息时加过） |
      | `null` / 解析失败 / 未知值 | **按 `continue` 处理**（`advance`），否则追问链卡死：闹钟已触发、超时也过了，既不结束也不重排 |
  - `advance()` 的语义要精确：**取消当前挂起的 `TIMEOUT`** → `updateTriggerTime(now + interval*60_000)` → 排下一次 `FOLLOWUP`。
    （`TIMEOUT` 是在 `FOLLOWUP` 投队列时才排的，所以 `advance` 只负责取消旧的、排新的）
- **依赖**：V2.2.1、1.2
- **验收**：
  - 单测：`active` 非空时，即使 `turnCount % 5 != 0` 也调了模型
  - 单测：`active` 为 null 时行为与现有 5.1 完全一致（不回归）
  - 单测：`completed` / `abandoned` / `continue` / `null` 四条分支各自只触发对应动作
  - 单测：`null` → 调的是 `advance`（不是 no-op）
  - 单测（`FollowupPortImpl` + FakeDao + FakeScheduler）：`complete`/`abandon` 各触发 1 次 `updateState` + **3 次** `cancel`；`abandon` 写的是 `ABANDONED`
  - 单测（`FollowupPortImpl.advance`）：先 `cancel(timeout code)` 再 `updateTriggerTime` 再 `schedule(followup code)`，顺序断言
  - **接线硬指标**：`CompanionApp` 里 `moodService` 的创建处必须出现 `followup = FollowupPortImpl(...)`；
    不许靠默认 `null` 通过验收（见本任务「反例警告」）
- **复杂度**：★★★☆☆

#### 任务 V2.2.3：追问任务创建 ✅ 已完成（2026-11-20，审查通过）

- **内容**：
  - `data/reminder/FollowupTaskExtractor.extractAndSchedule(recent)`（**逻辑线交付，UI 线调用**）
    - **防重复（第一步，在解析之前）**：`anyPendingFollowup() != null` → 直接 return。
      **必须用 `anyPendingFollowup()`，不是 `activeFollowup()`**——后者漏掉「刚建还未到点」的链（分工表见 V2.2.1）
    - **并发防护**：`Mutex` **只包「查 + 建」两步**；解析对话与排闹钟都在锁外（它们慢，且不需要串行）
      - 为什么要锁：查与建之间是 `suspend` 缝隙，用户手滑双击会让两条协程都查到空、各自建一条，
        结果是两条并行追问链每天各催一遍
      - 为什么不靠「上线观察」：本 App 个人自用、没有用户群，撞上了就是自己烦且无处反馈；
        5 行代码一次消掉整类问题，比留个已知坑划算
      - 现有 UI 只有「已完成 / 再等等」两个按钮（V2.2.5），**没有取消入口**，
        双链后要分别点两次「已完成」才能清掉，所以双链的实际代价比看上去高
    - 解析「提醒我 / 监督我」类需求 → 任务描述 + 触发时间 + 间隔 + 停止条件
    - 新建 `ReminderEntity(type="followup", priority=4, followupInterval=intervalMin, maxAttempts=5, attemptCount=0)`
    - **首次闹钟**：
      ```kotlin
      alarmScheduler.schedule(
          requestCode = AlarmCodes.followup(id),          // 不用 first(id)
          triggerAtMillis = triggerTime,
          taskDesc = task,
          reminderId = id,
          kind = AlarmKind.FOLLOWUP                     // 不能是 INSTRUCTION
      )
      ```
      > ⚠️ 首次写成 `kind=INSTRUCTION` 的话，追问第一次到点会走指令分支
      > （建短期记忆 + 投 priority-5），**不 `attemptCount+1`、不排 `TIMEOUT`、追问链根本不启动**。
  - 时间解析复用 2.4 已有的时间锚点转换，不新写解析器
  - 不阻断主对话：`viewModelScope.launch(Dispatchers.IO)`
- **依赖**：V2.2.1、V2.2.2、3.3
- **验收**：
  - 单测（FakeDao + FakeScheduler）：产出确定 `ReminderEntity`；`kind == FOLLOWUP`；`requestCode == AlarmCodes.followup(id)`
  - 单测：已有待办追问时不重复创建（**含 `attemptCount = 0` 的刚建链**，这条专门防「误用 `activeFollowup`」）
  - 单测：连续调用两次，只建一条
  - 单测：并发调用（两条协程同时进 `extractAndSchedule`），只建一条（锁生效）
  - **UI 线接入**：`ChatViewModel` 主对话成功后调 `extractAndSchedule(recent)`
- **复杂度**：★★★☆☆

#### 任务 V2.2.4：追问循环调度 ✅ 已完成（三轮审查 21:4x 通过；收口四项已合入，800 全绿；真机 3 条未验）

- **内容**：
  - `ReminderAlarmReceiver` 按 kind 分流（§3.6）—— 追问链的心脏（当前是 `Log.w("暂未实现")` 空壳，要换成真分支）
  - **复用 V2.2.2 已交付的 `data/alarm/FollowupAlarms`**（`scheduleFollowup` / `scheduleTimeout` / `cancelAll`），
    **不要在这里重新实现一遍**——重复实现会导致两套 requestCode 逻辑分叉
  - 计数语义与三个 code 的取消（§3.6 定死）
  - 通知「再等等」→ `advance(id)`（不扣次数）
- **依赖**：V2.2.2（`FollowupAlarms` + `FollowupPortImpl`）、V2.2.3、3.2
- **验收**：
  - 单测：从 0 起连续触发，**恰好发 5 条**，第 6 次到点转 `ABANDONED` 且不投队列
  - 单测：`TIMEOUT` 不加 `attemptCount`、不投队列，且重排了下一次 `FOLLOWUP`
  - 单测：`FOLLOWUP` 到点时 **`++` 发生在「即将投队列」之前**（先计数后入队，避免入队失败却已计数）
  - 真机：`dumpsys alarm` 里三个 code 都在且能取消
- **复杂度**：★★★☆☆

#### 任务 V2.2.5：追问通知按钮 ✅ 已完成（2026-11-20 22:1x 审查通过，818 / 101 suite 全绿；真机 3 条未验）

- **内容**：
  - **前置（先做，否则编译失败）**：新增 `res/drawable/ic_check.xml`、`res/drawable/ic_pause.xml`（Material `check` / `pause` 的 vector）
  - `ProactiveNotification` 增 `followupReminderId`（§3.9）
  - `ProactiveDeliveryRunner` 从 `PendingMessageContent.decode(msg.content) as? Followup` 取 `reminderId` 传入
  - `AndroidProactiveNotifier`：`followupReminderId != null` → 两个 action；否则维持现有单「已完成」
  - `ReminderDoneReceiver` 扩展 `EXTRA_ACTION`（`done` / `postpone`），原 `EXTRA_MEMORY_ID` 分支保留
- **依赖**：V2.2.4、3.5
- **验收**：
  - 单测：`followupReminderId == null` → 不加追问按钮
  - 单测：`done` / `postpone` 各只触发对应分支
  - 真机：点「已完成」不再追问；点「再等等」按间隔重发
- **复杂度**：★★☆☆☆

---

### 阶段 V2.3：语音输入（STT，**云端 ASR**）+ 通知栏快捷入口（**1 天**，v3.4 由 4.5 天下调）

#### 任务 V2.3.1：ASR 端口 + PCM/WAV 工具（v3.4 改为云端方案）✅ 已完成（2026-11-20）

> **v3.4 决策变更（2026-11-20）**：原「Sherpa-ONNX 离线集成」整条废止。
> 改用 **DeepSeek 官方 ASR API**（与聊天同一个 key、同一个 base_url）。
>
> **为什么改**：① 本地模型 232 MB（小模型也有 78 MB），APK 与设备存储代价过大；
> ② 云端一年约 **8 元**，而本地要多花 **3.5 天**开发；③ 识别质量云端更好；
> ④ 省掉 sherpa AAR 与现有 `onnxruntime-android` 的 `.so` 冲突问题。
>
> **已核实事实**（官方文档实读，2026-11-20）：
> - 端点 `POST https://api.deepseek.com/v1/tools/asr`，头 `Authorization: Bearer <key>`
> - 请求 `multipart/form-data`，文件字段名 **`file`**
> - 音频：**16 位 PCM WAV** 或 Ogg Opus；采样率 8000/16000/22050/24000/32000/44100/48000
> - 声道：单或双（**双声道只识别左声道**）
> - 上限：**8 MiB / 120 秒**，需 10 分钟内传完
> - 响应：`{"request_id":"...","text":"...","duration_ms":3200}`
> - 语种**自动识别**（中/粤/英/日/韩等 21 种），**没有语种参数**
> - 计费 0.39 元/小时（限时 5 折），精确到秒
> - 错误码：400 格式错 / 408 上传超时 / 413 超 8 MiB / 429 并发超限 / 503 繁忙 / 504 识别超时

- **内容**：
  - `data/speech/PcmUtils`（纯函数，可单测，**本任务唯一需要单测的部分**）
    - `stripWavHeader(bytes): ByteArray` —— 按 RIFF chunk 解析找到 `data` 块，**不硬编码 44 字节**
      （手机录音常带 `LIST` / `fact` chunk）
    - `s16leToFloat(bytes): FloatArray` —— `(low or (high shl 8)) / 32768f`
    - `floatToS16le(samples: FloatArray): ByteArray` —— 反向，用于统一成 PCM16
    - `pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray` —— 补标准 44 字节头
      （**ASR 只收 WAV / Ogg，不能直接传裸 PCM**）
  - `data/speech/AsrResult`（sealed）：`Success(text: String, durationMs: Int)` / `Failure(reason: String)`
  - `data/speech/AsrPort`（端口，便于 JVM 测试）
    ```kotlin
    interface AsrPort {
        suspend fun transcribe(wav: ByteArray): AsrResult
    }
    ```
  - **本任务不写网络实现**（归 V2.3.3）：只交端口 + PCM/WAV 工具 + 结果类型
- **依赖**：无
- **验收**：
  - 单测：`stripWavHeader` 对标准 44 字节头、带 `LIST` chunk 的头都能正确切出 PCM
  - 单测：`s16leToFloat` → `0x00,0x00`=0f、`0xFF,0x7F`≈0.99997f、`0x00,0x80`=−1f
  - 单测：`pcmToWav` 头部字段全对（RIFF/WAVE/fmt/data、采样率、字节率、块对齐、data 长度）
  - 单测：`floatToS16le` 边界（`0.9999→32767`、`−1.0→−32768`）
- **复杂度**：★☆☆☆☆

#### 任务 V2.3.2：音频录制 ✅ 已完成（2026-11-20）

- **内容**：
  - `data/speech/AudioRecorder`：`AudioRecord` **16k / mono / PCM16**
    - `start()` / `stop(): ByteArray`（写标准 44 字节 WAV 头）
    - `volumeLevel(): Int`（0–100 线性 RMS%，供 UI 波形；UI 需自行 sqrt/log 拉伸）
    - **120 秒硬上限**：到点自动 `stop()` 并回调（ASR 单次最多 120 秒，超了会 413）
  - 权限：`RECORD_AUDIO`（manifest 新增 + **首次在主界面申请**）
  - 失败（权限拒绝/设备占用）→ 空数组 + Toast，不崩
  - **已定案契约（2026-11-20，V2.3.4 UI 线照此实现）**：
    1. **自动停走回调**：`AudioRecorder.onAutoStop: (() -> Unit)?`，到 120 秒在录制线程触发；
       UI 收到后到 IO 线程调 `stop()` 取数据。`isAutoStopped` 供事后区分「到点」与「用户松手」。
       **不采用 UI 轮询 `isRecording`**（会和波形定时器绑死，容易写出录满不动的界面）。
    2. **空数组即失败**：`stop()` 在未 start / 权限拒绝 / 设备占用 / 空录音时返回 `ByteArray(0)`；
       调用方必须用 `wav.isEmpty()` 判失败并 Toast、不发消息，**绝不把 44 字节空 WAV 当成功**（上传必吃 400）。
    3. **`stop()` 阻塞**：内部含一次阻塞读 + `join(5s)`，**必须在 IO 线程调用**，主线程调用有 ANR 风险。
    4. **音频源 `MediaRecorder.AudioSource.VOICE_RECOGNITION`**：避开 MIC 的 AGC/降噪/通话音量策略，
       小声说话不被压掉，利于云端识别率。
    5. **波形拉伸**：`volumeLevel()` 是线性刻度，正常说话仅 3–9%，UI 画波形前做
       `sqrt(v / 100f)` 或对数拉伸，否则波形贴底几乎不动。
    6. **WAV 头活路径**：录音链路只调 `WavHeader.buildWavHeader`；`PcmUtils.pcmToWav`（V2.3.1，ASR 侧）
       产出等价头但不属于录音路径，两者勿混改。
- **格式天然对齐**：16k / 单声道 / PCM16 WAV **正好是 ASR 要求的格式**，不需要任何转码
  （ASR 支持 8k/16k/22.05k/24k/32k/44.1k/48k，双声道只认左声道）
- **依赖**：无
- **验收**：单测覆盖音量换算与 WAV 头字段；真机验证录音时长与文件大小线性（16k mono ≈ 32 KB/秒，
  120 秒 ≈ 3.8 MB，远低于 8 MiB 上限）
- **复杂度**：★★☆☆☆

#### 任务 V2.3.3：DeepSeek ASR 实现（上传 + 解析）✅ 已完成（2026-11-20，复核通过）

- **内容**：
  - `data/speech/DeepSeekAsrClient : AsrPort`
    - `POST https://api.deepseek.com/v1/tools/asr`
    - 头：`Authorization: Bearer <apiKey>` —— **复用 `ConfigRepository.getApiKey()`，不新增 key**
    - 体：`MultipartBody.Part.createFormData("file", "speech.wav", bytes.toRequestBody("audio/wav"))`
      （字段名必须叫 **`file`**）
    - 客户端：**新建一个短超时 OkHttp**（不建议复用聊天的 120s 流式超时）：
      `connect 10s / write 30s / read 60s / callTimeout 90s`
      （上传约 3.8 MB + 云端识别，1 分钟内该有结果；408/504 由服务端自己给）
    - 响应解析：取 `text` 与 `duration_ms`；`text` 为空白 → `Failure("没听清，请再说一次")`
    - 错误映射（**文案要能指导用户，不能只说"失败"**）：

      | HTTP | `Failure(reason)` |
      |---|---|
      | 400 | 录音格式有问题，请重试 |
      | 408 | 上传超时，请检查网络后重试 |
      | 413 | 录音太长（超过 2 分钟），请分段说 |
      | 429 | 说话太频繁，歇一下再试 |
      | 503 / 504 | 服务正忙，稍后再试 |
      | 无响应/连接失败 | 网络异常，请检查连接 |

    - **本地前置拦截**（省钱也省时间）：上传前判 `wav.size > 8 MiB` 或 `durationMs > 120_000`
      → 直接 `Failure`，**不发请求**
  - `data/speech/SttManager(asr: AsrPort, enabled: () -> Boolean)`：`enabled=false` → `Failure("语音已关闭")`
    （注意 `enabled` 是 **lambda 而不是 Boolean**：设置页改了开关要立刻生效，不能只在构造时读一次）
  - `CompanionApp` 里 `by lazy` 装配 `deepSeekAsrClient` + `sttManager`
- **依赖**：V2.3.1（`AsrPort` / `AsrResult` / `PcmUtils`）
- **验收**：
  - 单测（MockWebServer 真 HTTP）：
    - 正常响应 → `Success(text, durationMs)`
    - `text` 为空 → `Failure`
    - 400 / 413 / 429 / 503 / 504 各自映射到对应文案
    - 连接失败 → `Failure`，**不抛异常**
  - 单测：URL 恰好以 `/v1/tools/asr` 结尾；有 `Authorization: Bearer ...`；体是 multipart 且含 `file` 字段
  - 单测：超 8 MiB 或超 120 秒 → 本地拦截，`server.requestCount == 0`
  - 单测（假 `AsrPort`）：`enabled=false` → `Failure`；`enabled` 变为 true 后同样能成功（验证 lambda 每轮生效）
- **复杂度**：★★☆☆☆

#### 任务 V2.3.4：语音输入 UI（**UI 线**）✅ 代码已完成（2026-11-20，真机待跑）

- **内容**：
  - `ChatScreen` 底部语音按钮（1.3 占位）：长按录音（圆形动画 + 音量波形）→ 松手识别 → 结果填输入框可编辑
  - 录音中提示「松手发送，上划取消」；上划超出取消
  - **录音结束路径（依赖 V2.3.2 已定案契约）**：
    - 设置 `recorder.onAutoStop = { ... }`；到 120 秒在录制线程回调 → 切到 IO 线程调 `stop()`，
      按 `isAutoStopped` 显示「录音已满 2 分钟」并结束录音，不要再等松手
    - 用户松手同样在 IO 线程调 `stop()`；返回 `ByteArray(0)` → Toast + 不发消息
    - 波形用 `volumeLevel()` 的线性值做 `sqrt/log` 拉伸后再画，否则贴底不动
  - 识别中 Loading；**失败 Toast 直接用 `AsrResult.Failure.reason` 的文案**（"录音太长"/"说话太频繁"等），
    **不要统一写死「识别失败，请重试」**——200/400/413/429 的原因不同，用户该知道自己哪步错了
  - 不发空消息
- **依赖**：V2.3.2、V2.3.3、1.4
- **验收**：真机全流程；取消不上屏
- **复杂度**：★★☆☆☆

#### 任务 V2.3.5：设置页 STT 配置 ✅ 代码已完成（2026-11-20，真机待跑）

- **内容**：`ConfigRepository` 增 `stt_enabled`（默认开）；关闭时语音按钮隐藏（UI 线渲染）
  - **云端方案下不需要**：引擎选择、模型管理、下载进度、存储占用显示 —— 这些随 v3.4 一并取消
  - 也不需要第二个 API key：**ASR 复用聊天那个 key**（同一 `base_url` `api.deepseek.com`）
- **依赖**：V2.3.4、7.1
- **验收**：开关联动按钮可见性；关掉后长按不出录音、不开麦
- **复杂度**：★☆☆☆☆

#### 任务 V2.3.6：通知栏常驻快捷入口 ✅ 代码通过（🔴 已闭环，留 1 项 ⚠️；真机待跑）

- **内容**：
  - `service/QuickAccessService`：`startForeground()` 立即调用；通知 `PRIORITY_LOW`；
    点击 → `MainActivity`；「🎙 语音输入」→ `PendingIntent.getActivity(VoiceInputActivity, FLAG_IMMUTABLE)`
  - manifest：
    ```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <service android:name=".service.QuickAccessService"
             android:exported="false"
             android:foregroundServiceType="specialUse">
        <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                  android:value="companion_quick_access" />
    </service>
    ```
  - `VoiceInputActivity`（透明主题、`excludeFromRecents`）：录音 → STT → `put(voice_pending_text, text)` → 拉起 `MainActivity`（`NEW_TASK or CLEAR_TOP or SINGLE_TOP`）→ `finish()`
  - **不直接调 `ChatViewModel.send()`**（ViewModel 按 Activity 作用域）
  - `ChatViewModel`（UI 线）：启动/`onResume` 读 `voice_pending_text` 填输入框，读完 `delete`
  - 启动位置：**`MainActivity.onResume()`**；`CompanionApp.onCreate()` 里**不启动**
  - 设置项 `quick_access_enabled`（默认关）
- **依赖**：V2.3.3、1.4
- **验收**：
  - 开启 → 低优先级通知出现；关闭 → 消失
  - 点「语音输入」→ 识别 → 主界面打开且输入框已填好
  - 冷启动不崩（不在 Application 里启动 FGS）
- **复杂度**：★★★☆☆

**已定案契约（2026-11-20 审查线，实现必须照抄，别重新发明）**

1. **`AudioRecorder.stop()` 返回的已是完整 WAV（44 字节头 + 截断后 PCM）**，内部已调 `WavHeader.buildWavHeader(16000, 1, 16)`。**调用方禁止再补一次头**（双头会让 ASR 解出错误采样长度）。空录音返回 `ByteArray(0)`，**不是** 44 字节空 WAV。
2. **通知 id 用 `Int.MAX_VALUE`**：`ProactiveDeliveryRunner` 用 `msg.id % Int.MAX_VALUE` 派生主动消息通知 id，值域 `[1, Int.MAX_VALUE-1]`，取不到 MAX_VALUE → 常驻通知与主动消息**可证明不撞 id**（撞了会把常驻通知顶掉）。改这个数字前先读该公式。
3. **PendingIntent requestCode 用负数（-1 tap / -2 voice）**：`AlarmCodes` 从 1 起、notifier 的「已完成」是 `+100_000`，全为正数，负数段天然隔离。
4. **独立渠道 `quick_access`**：`IMPORTANCE_LOW` + `setSound(null)` + `enableVibration(false)` + `setShowBadge(false)`。**绝不复用 `proactive_message`**（HIGH），否则用户关主动消息响铃会连带把常驻入口一起静音。
5. **`VoiceInputActivity` 的手工交互必须闭环**（2026-11-20 审查发现的首版缺陷）：录音中「再点一次」= **结束并识别**（`stop()` → `transcribe()` → 写 pref → 拉 `MainActivity`）；**取消只能走返回键/长按**，不许把「录音中点击」再定义成取消。`stop()` 非幂等，一次会话只允许一次，用 `AtomicBoolean` 守。
6. **必须先判开关**：`VoiceInputActivity.onCreate` 第一件事 `sttManager.isEnabled()`，false → Toast + finish（在 `setContent` 之前），否则通知栏入口会绕过 V2.3.5 的关断。
7. **启动点只有 `MainActivity.onResume()`**（+ 设置页 `persist` 保存后同步一次）。`CompanionApp.onCreate` 里**禁止**启动 FGS（Android 12+ 抛 `ForegroundServiceStartNotAllowedException`）。
8. **`startForeground` 必须在 `onCreate`/`onStartCommand` 第一行**（5s ANR 窗口），中间不许有 IO。
9. **回填语义**：`app_state[voice_pending_text]` **读到即删**，只填输入框、**绝不自动发送**；空串/全空白不入库（`normalizeVoiceText`）。
10. **待定（不要擅自决定）**：`android:stopWithTask` 默认 true → 用户从最近任务划掉 App 后常驻通知消失。是「划掉就该消失」还是「划掉也要留着」，等用户拍板后再改，**这之前保持默认**。

---

### 阶段 V2.4：root 自动保活脚本（2.5 天）

#### 任务 V2.4.1：root 权限检测 ✅ 已完成（2026-11-20，审查通过）

- **内容**：`data/system/RootPermissionChecker`：`isRooted()`（`su -c id`）、`run(cmd): Result<String>`
  - 全部在 `Dispatchers.IO` 执行（**禁止主线程**），每条 3s 超时
- **依赖**：无
- **验收**：root 设备 true / 非 root false；不崩
- **复杂度**：★☆☆☆☆

#### 任务 V2.4.2：保活脚本库 ✅ 已完成（2026-11-20 审查通过，1 ⚠️ 已修；接缝见 §11.1）

- **内容**：`data/system/KeepAliveScriptRunner.run(pkg): Result<List<String>>`，逐条执行 + 每条日志
  ```
  su -c "dumpsys deviceidle whitelist +<pkg>"
  su -c "cmd appops set <pkg> RUN_ANYWHERE allow"
  su -c "cmd appops set <pkg> RUN_IN_BACKGROUND allow"     （落地时核对 op 名）
  su -c "am set-standby-bucket <pkg> active"
  su -c "cmd appops set <pkg> AUTO_START allow"            （MIUI 专有 op，失败仅记日志）
  ```
  - **删掉** `pm grant … REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（install-time 普通权限，`pm grant` 报 `not a changeable permission type`）
  - 厂商私有 sqlite（`miui_security.db` / `appops.db` / `huawei_powergenie.db`）：**降级为可选**，失败只记日志，**不进验收**
- **依赖**：V2.4.1
- **验收**：
  - 每条命令成功/失败都进日志，一条失败不中断后续
  - 非 root → 「需要 root」提示
  - **验收只认这条**：`su -c "dumpsys deviceidle whitelist"` 输出里能看到包名
- **复杂度**：★★☆☆☆

#### 任务 V2.4.3：设置页保活按钮 ✅ 已完成（2026-11-20 审查通过，1 ⚠️ 已修）

- **内容**：设置页「系统/调试」新增「一键保活（需 root）」+「查看执行日志」对话框；无 root → Toast + 6.2 引导
- **依赖**：V2.4.2、7.1
- **验收**：root 设备执行并显示日志；非 root 只提示
- **复杂度**：★★☆☆☆

---

## 5. 工作量估算

| 阶段 | 天数 |
|---|---|
| V2.1 环境感知层（7 任务） | 8.5 |
| V2.2 追问链（5 任务） | 5.5 |
| V2.3 语音 + 快捷入口（6 任务） | **1.0**（v3.4 云端化，原 4.5）|
| V2.4 root 保活（3 任务） | 2.5 |
| **总计** | **约 17.5 天**（v3.4 云端 ASR 省 3.5 天，原 21）|

## 6. 开发顺序

1. **V2.1.2 先做**（纯逻辑、零依赖、主机可全验）→ **V2.1.1** → V2.1.4 → V2.1.5 → V2.1.3（含生成脚本）→ **V2.1.7** → V2.1.6
   > V2.1.3 的 JSON 生成脚本可与 V2.1.4 并行，但不要挡住 V2.1.7。
2. **V2.2**：V2.2.1 → V2.2.2 → V2.2.3 → V2.2.4 → V2.2.5
3. **V2.3**（v3.4 云端化后 1 天）：V2.3.1（PCM/WAV 工具 + `AsrPort`）→ V2.3.2（录音）→ V2.3.3（DeepSeek ASR 实现）→ V2.3.4（UI）→ V2.3.5（设置开关）→ V2.3.6（通知栏入口）
4. **V2.4**

## 7. 开发流程（每个任务）

1. 读 `陪聊APP项目规则.md` 确认进度
2. 读本文件对应任务 + 第 3 章相关契约
3. 实现：**先 `domain/` 纯逻辑 + 单测，再 `data/`**
4. 跑 `testDebugUnitTest assembleDebug assembleDebugAndroidTest`，报告用例数
5. 更新 `陪聊APP项目规则.md`
6. 需要 UI 线的部分：写「接口约定」（文件 + 行 + 签名），**不直接改 `ui/`**

## 8. 验收标准（第二版整体）

### 环境感知
- 夜间 `hour >= 23` 或 `< 5` → 收到「别熬夜」（**豁免勿扰**；关掉 `late_night_enabled` 则不发）
- **23:30 与次日 00:30 只发一次**（按"夜"去重）
- 降水 >60% → 「带伞」；最低温较昨日骤降 >5°C → 「添衣」；最高温 >35°C → 提醒
- 同一 `stableId` 一天只发一次
- 定位失败可手动指定城市，不崩；天气失败退化为只用时间；缓存超 24h 不发天气类
- 关于页标注「天气数据来自 Open-Meteo」并附链接

### 特殊日期与节日
- 生日存 `2026-11-20` → **`2027-11-20` 仍收到祝福**
- **`2026-02-17` → 春节**；`2026-10-01` → 国庆
- `11/20`、`11月20日` 两种写法也能识别
- **勿扰顺延不跨日**：跨次日则丢弃，不发「昨天的春节快乐」

### 追问型任务
- 「明天 7 点提醒我吃早餐」→ 到点发「吃早餐没？」（**首次闹钟必须是 `kind=FOLLOWUP`**）
- 「吃了」→ `done`；「还没」→ 间隔后再问；「来不及不吃了」→ `abandoned`
- **恰好发 5 条**，第 6 次到点转 `abandoned`，三个闹钟全部取消
- 超时（30 分钟）不消耗次数、不发通知、只重排
- **间隔期内回复也能被判定**（`activeFollowup` 用 `attemptCount > 0`）
- 通知带「✅ 已完成」「⏸ 再等等」
- 追问判定与 mood 共用一次 `callJson`，有活跃追问时**不受 every-5 闸门限制**

### 语音输入
- 长按录音 → 松手识别 → 结果填输入框可编辑；上划取消不发
- 录音 → 上传 DeepSeek ASR → 回填输入框；失败**按原因** Toast（录音太长 / 太频繁 / 网络异常），不发空消息
- **离线不可用**（v3.4 云端方案）：无网络时语音按钮提示「需要联网」；这不算退化——聊天本身也要联网
- 单次录音上限 **120 秒 / 8 MiB**，到点自动停并提示

### 通知栏常驻入口
- 开启后显示低优先级通知；点通知打开 App；点「语音输入」→ 识别 → 输入框已填
- 关闭开关 → 通知消失

### root 保活
- root 设备执行 → 逐条日志；`dumpsys deviceidle whitelist` 能看到包名
- 非 root → Toast「需要 root 权限」

### 整体
- 双通道：环境（整点闹钟）/ 节日 → 记忆（2h Worker）不互相阻塞
- 环境 / 节日 / 追问绕过冷却与每日上限（3.4 已实现）
- 断网不崩，恢复后继续工作
- **现有 418 条测试保持全绿**

## 9. 已知边界（不做 / 做不到）

| 项 | 说明 |
|---|---|
| 系统 STT 降级 | `RecognizerIntent` 不支持传入音频字节，只能开麦；本项目不做 |
| 精确时间保证 | `ENV_CHECK` 用 `setAndAllowWhileIdle`，Doze 下可能晚几分钟；饭点窗口可能错过 |
| 用户强行停止 App | 闹钟/延迟任务全部被系统取消，不会补发 |
| 全天勿扰 | 节日祝福会在当天最后一次检查时丢弃（不推次日） |
| 厂商私有保活库 | 仅作可选尝试，不作验收依据 |
| Play 上架 | 含模型后 APK 230MB+，超常规上限；侧载使用 |
| 双 ONNX 运行时 | **已作废**（v3.4 改云端 ASR，不再引入 Sherpa AAR，与现有 `onnxruntime-android:1.16.3` 无冲突）|
| 勿扰期指令/追问 | 是**顺延**不是硬发；用户关掉「勿扰期间不发通知」才会即时送达 |

## 10. 参考资料

- 第一版计划：`陪聊APP开发计划.md` ｜ 项目规则：`陪聊APP项目规则.md` ｜ 原始需求：`陪聊APP-最终方案.md`
- **DeepSeek 语音识别 API**（v3.4 起采用）：<https://api-docs.deepseek.com/zh-cn/guides/asr>
  - `POST https://api.deepseek.com/v1/tools/asr`；multipart `file`；WAV/Ogg，≤8 MiB、≤120 秒；0.39 元/小时
- sherpa-onnx Android AAR（**v3.4 起不再使用**，仅留档）：<https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2>
- Open-Meteo：<https://open-meteo.com>（CC BY 4.0，需 App 内署名）

## 11. 下一步

### 11.1 进度（截至 2026-11-20，按代码核实，不按记忆）

> **测试基线：830 条 / 101 个 suite / 0 失败 / 0 错误 / 0 跳过**（逻辑线 2026-11-20 **22:4x** 收口 6 条遗留后全量重跑）。
> 拆解：818（22:06）＋ 引入 5 条（`FollowupPortImplTest` +4 / `FollowupChainRunnerTest` +1）＋ 相对时长 7 条（`FollowupTaskExtractorTest` +4 / `ReminderParserTest` +3）。**第二版已交付 412 条**（v1 收尾 418 + 412 = 830）。
> **进度口径：第二版 22 / 22 项全部代码完成且已接线**（V2.1 8 + V2.2 5 + V2.3 6 + V2.4 3；V2.3.6 的 1 项 ⚠️ 已于 22:2x 修掉）。**代码层面无未开工任务**，剩余仅「真机验收」与 §11.2 的 2 项残留（一项系统行为、一项统计未接线）。

| 任务 | 状态 | 核实依据 |
|---|---|---|
| **V2.1.1** 环境数据采集 | ✅ 完成 | `data/environment/` 10 个类；35 条测试（MockWebServer 真 HTTP） |
| **V2.1.2** 环境规则引擎 | ✅ 完成 | `domain/environment/` 5 个类；29 条测试 |
| **V2.1.3** 特殊日期匹配 | ✅ 完成 | `domain/calendar/Festival`、`data/calendar/FestivalProvider`、`domain/proactive/SpecialDateMatcher`、`data/proactive/SpecialDateHookService` + `assets/calendar/festivals_2026_2036.json`（覆盖 2026–2036） |
| **V2.1.4** 环境钩子投递 | ✅ 完成 | `EnvironmentHookService`（`recall` / `enqueue` / `dedupKey` 齐全）；`ProactiveDeliveryRunner.onDeliveredMessage` 已存在 |
| **V2.1.5** 整点检查闹钟 | ✅ 完成 | `AlarmKind` / `AlarmCodes` / `AlarmScheduling`（5 参，默认值只在接口上）/ `EnvironmentCheckScheduler`（在 `worker/`）/ `ReminderAlarmReceiver.handleEnvCheck`（先续命→再判开关→再 recall→投队+送达）/ `CompanionApp.syncEnvCheckSchedule()`（四分支；返回本次新排的整点时间）；`AlarmCodesTest`(6) + `EnvironmentCheckSchedulerTest`(6) + `EnvironmentHookServiceTest` 7→10 |
| **V2.1.6** 设置项 | ✅ 完成 | 逻辑：`domain/settings/EnvironmentSettings`（默认值 + 夹取 + `toRuleConfig`，6 条测试）+ `ConfigRepository.get/saveEnvironmentSettings`；UI：环境区块 / 城市框 / 署名 / 定位权限全部落地。**明细见 `陪聊APP项目规则.md` 阶段 8** |
| **V2.1.7** 节日钩子接入 | ✅ 完成（审查线 18:4x 复核通过） | `ProactiveCheckRunnerFestivalTest`(6) + `ProactiveDeliveryDndTest` 10→12 + `ProactiveDeliveryRunnerTest` 7→9 + `PendingMessageContentTest` 8→10；`Festival(name)` / `FestivalDedupe` / `ALREADY_SENT` / `FESTIVAL_EXPIRED` |
| **V2.1.8** 对话天气可见 | ✅ 完成 | `WeatherSummaryTest`(7) + `WeatherCacheTest`(16) + `UserMetaMarkerTest`(9)；`weatherLine` 只读 `peek()` 不联网 |
| **V2.2.1** 数据结构与迁移（DB 6→7） | ✅ 完成（审查线 18:4x） | `MIGRATION_6_7` + **删掉** `fallbackToDestructiveMigration()`；`app/schemas/6.json`(8 列) / `7.json`(10 列)；`FakeReminderDaoTest`(8) + `AppDatabaseMigrationTest`(2，真机 9/9) |
| **V2.2.2** 追问判断（合并 mood 调用） | ✅ 完成（审查线 20:2x；**22:2x 收口「不清队列」遗留**） | `MoodServiceFollowupTest`(8) + `FollowupPortImplTest`(6→10) + `MoodUpdateTest` 6→8；`domain/reminder/FollowupPort` + `data/alarm/FollowupAlarms` + `data/reminder/FollowupPortImpl`（**构造第 3 参数 `queue` 必填**） |
| **V2.2.3** 追问任务提取 | ✅ 完成（审查线 20:4x；唯一 🔴「并发用例假绿」已于 20:46 修复并复核） | `FollowupIntentTest`(9) + `FollowupExtractionParserTest`(9) + `FollowupTaskExtractorTest`(10) + `BootRestorePlannerTest` 8→10；真并发改用 `async(Dispatchers.Default)` + Fake DAO `delay(10)`；首钟走 `AlarmCodes.followup`（有 `assertNotEquals(first(id), …)` 钉死） |
| **V2.2.4** 追问闹钟链 | ✅ 完成（审查线三轮 21:4x 通过 + **收口已合入**，798→800 全绿） | `FollowupChainRunnerTest`(15) + `PendingMessageContentFollowupTest`(4) + `BootFollowupRestorerTest` 4→8 + `ProactiveDeliveryRunnerTest` 9→13；receiver 两分支 + `FOLLOWUP_TIMEOUT_MS`；收口四项：`scheduleOverdueFollowups` 改名 + 判据放宽为「overdue followup（不分 `attemptCount`）」+ `at` 去掉默认值 + light 路径返回值语义。**与 V2.2.3 已同包落地**，原「必须同包」约束已解除。**真机三码 / 5→ABANDONED / force-stop 补钟未验** |
| **V2.2.5** 追问通知按钮（UI 线） | ✅ 完成（审查线 22:1x，818 全绿 / 101 suite；**观察②③ 已于 22:2x 收口，仅剩观察①「图标无视觉效果」为系统行为**） | `NotificationActionsTest`(6) + `ReminderActionHandlerTest`(7) + `PendingMessageQueueCancelFollowupsTest`(3) + `ProactiveDeliveryRunnerTest` 13→15；`domain/proactive/NotificationActions`（纯函数 `planFor`）+ `data/reminder/ReminderActionHandler` + `ProactiveNotification.followupReminderId` + `PendingMessageQueue.cancelPendingFollowups`（**尾冒号**）+ `ReminderDoneReceiver.EXTRA_ACTION/EXTRA_REMINDER_ID` + `ic_check/ic_pause`。**真机双按钮三条未验** |
| **V2.3.1** ASR 端口 + PCM/WAV 工具 | ✅ 完成（17:5x） | `PcmUtilsTest`(11)；`data/speech/`：`AsrResult`(sealed) / `AsrPort`(接口) / `PcmUtils`(纯函数)，零 Android 依赖 |
| **V2.3.2** 音频录制 | ✅ 完成（18:5x 补 120s 截断） | `WavHeader`(4) + `WavHeaderTest`(16) + `AudioRecorder`；manifest `RECORD_AUDIO` |
| **V2.3.3** DeepSeek ASR 实现 | ✅ 完成（初审 2🔴 修复后复核通过） | `DeepSeekAsrClientTest`(28) + `SttManagerTest`(4→5)；`DeepSeekAsrClient`（chunk 扫描解析）+ `SttManager` |
| **V2.3.4** 语音输入 UI（UI 线） | ✅ 代码完成（**真机 8 条待跑**） | `VoiceBarLevelTest`(5)；`VoiceInputOverlay` / `VoiceInputController`(状态机) / `ChatScreen` 长按手势 |
| **V2.3.5** 设置页 STT 配置 | ✅ 代码完成（**真机 6 条待跑**） | `ConfigRepository.getSttEnabled/setSttEnabled`（key `stt_enabled`，默认 true）+ `SettingsScreen` SwitchRow + `ChatScreen` 门控（关掉不建 controller） |
| **V2.3.6** 通知栏常驻快捷入口 | ✅ **代码通过**（原 1 项 🔴「点击不产生识别」已闭环 + `NEW_TASK` 加固 + **22:2x 修掉 `onPause` 语义**；**真机 6 条未跑**） | `QuickAccessNotificationSpec`(5) + `QuickAccessEntrance`(4) + `VoicePrefillTest`(3)；`service/` 3 文件 + `VoiceInputActivity` + manifest 2 权限 / specialUse service / 透明 activity |
| **V2.4.1** root 权限检测 | ✅ 完成（审查线 20:4x；首轮 6 条 ⚠️ 全部落地后复核通过） | `RootProbeTest`(8) + `RootPermissionCheckerTest`(11)；`domain/system/`（`ShellOutcome` / `ShellRunner` / `RootProbe`）+ `data/system/`（`ProcessShellRunner` / `RootPermissionChecker`）；**真机未验**（`adb devices` 空） |
| **V2.4.2** 保活脚本库 | ✅ **代码完成**（2026-11-20 21:1x，审查通过、1 ⚠️ 已修；真机未验） | `KeepAliveScriptRunnerTest`(8)；`domain/system/KeepAliveScripts`（5 条命令纯函数 + `DUMP_TIMEOUT_MS=5s`）+ `data/system/KeepAliveScriptRunner`（`probe()` 一次判 root → 逐条跑、单条失败不断链；失败消息展示层压换行）；763 / 94 全绿；`CompanionApp` 零改动 |
| **V2.4.3** 设置页保活按钮 | ✅ **已完成**（2026-11-20 21:1x，审查通过、1 ⚠️ 已修；真机未验） | `KeepAliveUiStateTest`(4)；`ui/settings/KeepAliveUiState`（4 态 + `Result→UI` 纯函数）+ `SettingsScreen`（按钮 + 日志对话框 + 需要 root 对话框，失败原文透传；直接调 `run` 不吞 Cancellation）；**零新增入参**、MainActivity/CompanionApp/data/domain 零改动；786 / 97 全绿 |

### 11.2 建议顺序

按 §6 + 当前卡口：**V2.2 线 5/5 全部闭环**，且 **V2.2.3 已于 2026-11-20 22:2x 真正接入 `ChatViewModel`** —— 此前 `FollowupTaskExtractor` 写好了、10 条单测全绿、`CompanionApp` 也 lazy 出来了，但**全项目零调用**，`reminders` 表永远建不出 followup 行，等于 V2.2.2/2.2.3/2.2.4/2.2.5 四个任务的功能在真机上一次都不会发生。

22:2x 补掉的四条遗留（全部带单测 / 全部绿灯）：

| # | 事项 | 落地 |
|---|---|---|
| 1 | **V2.2.3 接入**（追问链唯一入口） | `ChatViewModel.generateAssistant` 主对话成功后 `followupTaskExtractor.extractAndSchedule(effectiveConfig, recentForFollowup)`，fire-and-forget，新常量 `FOLLOWUP_RECENT_MESSAGES = 8`；外层 `runCatching`，失败只打 `W/Followup` |
| 2 | **V2.2.2 遗留：`complete()/abandon()` 不清队列** | `FollowupPortImpl` 注入 `PendingMessageQueue`（构造第 3 参数，**必填**）；顺序 `updateState` → `cancelAll` → `cancelPendingFollowups`。守卫：`complete_writes_state_then_cancels_alarms_then_clears_queue`（三方共用 order 流水）+ `complete_does_not_clear_queue_when_state_write_fails`（落库抛异常时队列必须原样）+ `complete_cancels_only_queued_followups_of_this_chain`（42 不杀 420） |
| 3 | **V2.2.5 观察③：毫秒级竞态** | `FollowupChainRunner.tickFollowup` 入队后立刻复查 state；链已结束就 `cancelPendingFollowups` 撤回刚入队那条，返回 false。守卫 `tick_withdraws_just_enqueued_followup_when_chain_ends_mid_flight`（DAO 第 2 次 `getById` 时翻成 DONE，精确模拟窗口） |
| 4 | **V2.2.5 观察②：跨通知 requestCode 碰撞** | `AndroidProactiveNotifier` 的 `setAction` 拼上 `":" + notificationId`，`filterEquals` 天然唯一，不再可能互覆写 |
| 5 | **V2.3.6 ⚠️：`onPause` 识别途中取消** | `VoiceInputActivity.onPause` 先判 `transcribing = !recording.value && busy`，为真则 `super.onPause()` 后直接 return —— 已说完的话切走不再整段丢弃 || 6 | **追问 `start` 提示支持相对时长**（2026-11-20 22:4x，真机首测暴露） | `ReminderParser` 公开 `relativeOffsetMillis/relativeTriggerAt`；`FollowupTaskExtractor.resolveTriggerTime` 改为「相对时长 → 绝对时刻 → now+interval」三级优先。此前「1分钟后开始」被静默忽略、落到 now+interval（表现为「说好 1 分钟，5 分钟才响」） |
| 7 | **「今晚8点」解析成早上 8 点**（同上，顺手修的既有 BUG） | `TimeAnchorConverter.findTime` 前缀候选补 `今晚/明夜/今早/明早` + `pm` 集合 + `DAY_WORDS` 加 `明晚|明早 → +1`、`昨晚 → -1`。`FollowupPrompt` 的示例本来就写「今晚8点」，原先等于把用户往坑里带 |

**仍未做（2 项，均不阻断）**：
- V2.2.5 观察①：`addAction` 的图标在 API 24+ 无视觉效果（系统行为，要图形按钮得换 `RemoteViews`）；
- `LOW_REPLY_RATE` 回复率统计未接线（`CompanionApp:379` TODO），该主动消息规则因此永不触发。

真机验收攒到**插上 USB 后一次性跑**（`adb devices` 为空只代表线没插，属正常）：
V2.3.4 八条 / V2.3.5 六条 / V2.3.6 六条 / B9 恢复四条 / V2.4.1 两条（非 root=false；**root=true 本条机器跑不了**，`su` 不存在）/ **V2.2.4 三条**（三码并存 / 恰好 5 条后 ABANDONED / force-stop 后补钟）/ **V2.2.5 三条**（双按钮 / 点完成三码消失且 15-30 分钟内不再追问 / 点再等等按 interval 重来且 `attemptCount` 不增）。
另加本轮 1 条**端到端**（此前根本无从验）：对话里说「我要背 30 个单词，你监督我」→ 看 `reminders` 表出现 `type=FOLLOWUP, state=PENDING` 行。

### 11.3 待 UI 线

**V2.1.6 的 UI 部分已完成**（2026-11-20 17:32，明细见 `陪聊APP项目规则.md` 阶段 8）：

- ✅ `SettingsForm` 加 4 字段 + 环境感知区块（总开关 / 熬夜 / 降水滑杆 / 降温滑杆）
- ✅ 手动城市输入框 + 四态 Toast（`Hit` / `NoMatch` / `NetworkError` / `Cleared` 不弹）
- ✅ 关于页 Open-Meteo 署名 + 可点链接
- ✅ 粗定位权限（`ACCESS_COARSE_LOCATION`）+ 三处修复：`ON_RESUME` 刷新、系统不再弹时跳应用设置页、提示块嵌进 `environmentEnabled`
- ✅ 容器接线（`CompanionApp:92/96/104` + `MainActivity:187-188`）

**仍待 UI 线：**

| 事项 | 状态 |
|---|---|
| ~~**B8**：运行中打开环境感知不补排~~ | ✅ **已闭环**：不开 UI 的额外接线——挂在 `ConfigRepository.onEnvironmentSettingsChanged` 上（`CompanionApp.onCreate` 注册），设置页**任何**保存入口都覆盖到 |
| V2.2.3 `extractAndSchedule` 接入 `ChatViewModel` | ✅ **已完成（2026-11-20 22:2x）**：`generateAssistant` 主对话成功后 fire-and-forget 调用，`FOLLOWUP_RECENT_MESSAGES = 8`。此前为「卡口已解除、等人接」，实为全项目零调用 → 追问链在真机上等于不存在 |
| V2.3.4 语音输入 UI / V2.3.5 `stt_enabled` | ✅ 均已完成（真机 8 + 6 条待跑） |
| V2.3.6 的 1 项 ⚠️（`onPause` 在识别途中取消） | ✅ **已修（22:2x）**：`onPause` 里 `transcribing = !recording.value && busy` 为真则直接 return |
| B6 `RECORD_AUDIO` 首次授权 | ✅ 已落地（主界面长按 + `VoiceInputActivity` 双入口） |
| V2.2.5 追问通知按钮（「已完成 / 再等等」） | ✅ **已完成**（22:1x 审查通过，818 全绿）。真机 3 条待跑 |
| **`FollowupPortImpl.complete()/abandon()` 不清队列**（V2.2.2 遗留，V2.2.5 只补了按钮那条路） | ✅ **已修（22:2x）**：注入 `PendingMessageQueue`，两处 `cancelPendingFollowups`，顺序与 `ReminderActionHandler` 逐字一致 |

### 11.4 文档维护规则（防「读了文档仍判错进度」）

1. 任务完成后**在该任务标题行尾追加 `✅ 已完成（日期）`**
2. 同步更新本节的表格
3. 判断某任务是否可开工前，**先核实代码**（`Get-ChildItem` / `Select-String`），不要只读文档——
   本项目已出现「代码早已完成、文档无标记」的情况（V2.1.3 / V2.1.4 / V2.1.5）

---

## 12. 附录 A：已否决的修订建议（防复发）

以下 6 条来自外部审查，**均已核实为错误或不适用于本工程**，不要采纳：

| 建议 | 为什么否掉 |
|---|---|
| 超时分支 `incrementAttempt`，且 `followupInterval * 1000L` | 超时不该消耗次数（与"恰好 5 条"矛盾）；`followupInterval` 存的是**分钟**，应 `*60_000L` |
| `continue` 分支再 `incrementAttempt`，`abandoned` 记成 `DONE` | 会与到点时的 +1 重复计数（次数翻倍）；`abandoned` 必须保留 `ABANDONED` 状态 |
| `msg.content as PendingMessageContent.Environment` | `content` 是 `String` 列，**强转必崩**；必须 `decode(...) as? ...` |
| `PendingMessageType` 的 `priority` 改名 `basePriority` | `PendingMessageQueue.enqueue` 里是 `priority = type.priority`，改名直接编译失败 |
| 送达层把 `INSTRUCTION` / `FOLLOW_UP` 加进勿扰豁免 | 推翻 7.2「勿扰期顺延」语义，等于废掉 `dnd_suppress_notification` 设置 |
| 在 `ChatViewModel` 里查 DAO 取活跃追问 | 跨层（`ui/` 属 UI 线），且会复刻 `triggerTime <= now` 的漏判 bug；应由 `FollowupPort` 注入 `MoodService` |

另：`ProactiveDeliveryRunner` 里 `DeliveryOutcome.Discarded/Deferred`、`dndPolicy` 这些类**不存在**，真实类型是 `ProactiveDeliveryOutcome` 与 `dndBlocksDelivery()` / `dndEndMillisProvider()`。

## 13. 附录 B：未闭环事项（工程/环境，非文档问题）

| #   | 事项                                                                                                                                                                        | 状态                                                                                                                                                                                                                                                                    |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B1  | **设备装不上**（MIUI `INSTALL_FAILED_USER_RESTRICTED`）                                                                                                                          | **已解决（选 c）**：USB 安装已开，`adb install -r -t` 返回 `Success`；真机 8 项验收全通过（包安装 / 冷启动 1.24s / 进程存活 / 2 个 WorkManager job `RUNNABLE` / deviceidle 白名单 `user,<pkg>,<uid>` / `dumpsys power` 无残留 WakeLock / 通知渠道 `importance=DEFAULT` / `ReminderAlarmReceiver` 已注册）；**真机验收时需插 USB**——拔线后 `adb devices` 为空属正常，不代表环境损坏，也不代表 B1 回退 |
| B2 | `sherpa-onnx-paraformer-zh-2023-09-14` 模型名 / 体积 / `tokens.txt` | **已闭环**（2026-11-20 实测）：`model.int8.onnx` = **232 MB**、`tokens.txt` = 74 KB，**无 fp32 版**。另有小模型 `paraformer-zh-small-2024-03-09` = **78 MB**。v3.4 改用云端 ASR，模型**无需下载** |
| B3 | AAR 是否自带 `libonnxruntime.so` | **已闭环**（解包实测）：自带。4 ABI × 4 个 `.so`（`libonnxruntime` / `libsherpa-onnx-{c-api,cxx-api,jni}`）→ 与现有 `onnxruntime-android:1.16.3` **同名冲突**。v3.4 云端化后不再引入 AAR，冲突消失 |
| B4 | Sherpa 2.x Kotlin 类名 | **已闭环**（`javap` 实读）：`com.k2fsa.sherpa.onnx.OfflineRecognizer`（**不是** `SherpaOnnxOfflineRecognizer`）。v3.4 云端化后不再使用 |
| B5  | V2.1.6 / V2.3.5 的设置页字段、V2.3.6 的 `voice_pending_text` 回填、V2.2.3 的 `extractAndSchedule` 调用 | **V2.1.6 部分已完成**（2026-11-20：环境区块 / 城市框 / 署名 / 定位权限 / 三处修复，明细见项目规则阶段 8）。剩：V2.3.6 的 1 项 ⚠️（`onPause` 在识别途中取消，不阻塞）|
| B6  | `RECORD_AUDIO` 首次授权入口                                                                                                                                                     | 定为主界面，需 UI 线实现                                                                                                                                                                                                                                                        |
| B7  | **环境钩子的数值传不到文案层**：V2.1.4 的 `PendingMessageContent.Environment` 只存 `stableId`，`Rain(80)` / `TempDrop(6)` / `HighTemp(38)` 里的数字在 encode 时全部丢失，送达后 LLM 只能说「今天有雨」，说不了「降水 80%」 | **已拍板：a**（数值只作触发条件、文案泛化）。陪聊场景「今天有雨，记得带伞」已够用；不动 `PendingMessageContent` 编解码、不加字段、不额外调 LLM。落定写法见 §3.4 末两条；V2.1.2 验收已加「stableId 不含数值」的回归断言。b / c **不再实施**：b（`Environment(stableId, hint)`）列为将来备选，hint 由规则引擎产出、按**第一个**分隔符切分、去重键仍只取 `stableId`；c 会丢掉去重键，否决                                           |
| B8  | **运行中打开环境感知不补排**：`syncEnvCheckSchedule()` 只有 `onCreate` 一个调用点，设置页保存回调与 `MainActivity.onResume` 未接 → 开关从关到开必须重启 App 才排钟 | **已闭环（2026-11-20 20:2x）**：不用 UI 加一行——挂在 `ConfigRepository.onEnvironmentSettingsChanged` 回调上（`CompanionApp.onCreate` 注册），设置页任何保存入口都覆盖到 |
| B9  | **无 `BOOT_COMPLETED` 接收器**（全项目 0 命中）：重启后 `AlarmManager` 闹钟全丢，而 `env_check_scheduled="1"` 又阻止补排 → 环境检查永久失效；用户提醒闹钟同样丢 | **已闭环（B9 交付并复核通过）**：`worker/BootReceiver`（收 `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`，**刻意不收** `LOCKED_BOOT_COMPLETED`——`EncryptedSharedPreferences` 要解锁后才可用）+ `CompanionApp.restoreAlarmsAfterBoot()`（环境检查删标记+`cancel`后强制重排；提醒逐条按 `AlarmCodes.first(id)` 重排，关机期间错过的改成 `now+5s` 补发、不丢不改状态）+ `restorePendingRemindersLight()`（每次启动只排 `triggerTime > now`，覆盖 force-stop / ROM 清后台这类拿不到开机广播的场景）；`reminders` 表也补上了写入（`ReminderService.saveReminder` + 出错降级），真机 `adb reboot` 已验（`提醒 1/1` + `下一次 @18:00`） |
| B10 | `AlarmScheduler` 的 kind→API 选择、接收器 `ENV_CHECK` 分流均无单测（需 Android Context）| 待立项：把 `AlarmManager` 抽成可注入接口即可补单测 |
| B11 | **V2.2.1 的迁移测试需要真机**：`MigrationTestHelper` 只能跑 `connectedDebugAndroidTest`，当前 `adb devices` 为空（设备未插，属正常）| **已闭环**：真机 `connectedDebugAndroidTest` 9/9 通过（`AppDatabaseMigrationTest` 2 条）；`fallbackToDestructiveMigration()` 已删，`app/schemas/6.json`(8 列) 与 `7.json`(10 列) 已入库 |
