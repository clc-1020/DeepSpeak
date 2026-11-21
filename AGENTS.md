# AGENTS.md —— 给"接手这个仓库的 AI"的硬性约定

> 本文件是这个仓库的**行为契约**。任何 Agent（包括未来的自己）在改代码前必须读完这一页。
> 完整细则见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

---

## 0. 这个项目是什么

个人自用的 Android AI 陪伴应用（Kotlin + Compose + Room）。**纯客户端**，用户自备 API Key。
当前形态与版本以 `app/build.gradle` 和 [`CHANGELOG.md`](CHANGELOG.md) 为准。

## 1. 开工前必读（3 份，别跳过）

1. [`docs/00-现行/陪聊APP项目规则.md`](docs/00-现行/陪聊APP项目规则.md) —— 工程规则、数据保护铁律、踩过的坑
2. [`CHANGELOG.md`](CHANGELOG.md) 的**最新 1~2 条** —— 别人刚改了什么、当前测试基线是多少
3. 你要改的那块**领域设计文档**：
   - 情绪 / 关系 / 热络度 → `docs/00-现行/陪聊APP-情绪系统设计.md`
   - 记忆注入 / 追问链 → `docs/00-现行/陪聊APP-记忆与追问-交接说明.md`
   - 接口 / 字段契约 → `docs/00-现行/陪聊APP-DeepSeek新API接入设计.md`

## 2. 完成定义（DoD）—— 缺一条就不算做完

| # | 必做 |
|---|---|
| 1 | `.\gradlew.bat testDebugUnitTest --no-daemon` **全量通过**（不是只跑 `--tests`），记下新的「用例数 / suite 数」 |
| 2 | **在 `CHANGELOG.md` 顶部追加一条**：`## [版本] - 日期 · versionCode N` + `测试基线：NNN / NN suite / 0 失败` + `新增/变更/修复` |
| 3 | `app/build.gradle` 的 `versionCode` +1、`versionName` 按语义化递进（修 bug→PATCH，新功能→MINOR） |
| 4 | 机制变更 → 更新对应领域设计文档；新踩的坑 → 追加到项目规则「踩过的坑」 |
| 5 | 用户可见的新功能 → 更新 `README.md` 特性表 |

> **第 2 条是硬要求。** 记录写在 `CHANGELOG.md`，不要写到别处，也不要只在聊天里说。

## 3. 硬禁忌（都出过事故）

1. **禁** `gradlew connectedDebugAndroidTest` / `connectedCheck` —— 跑完 AGP 自动卸载 App，清空数据库与 API Key。要跑仪器测试用 `tools/deploy.ps1 -RunTests`
2. **禁** `fallbackToDestructiveMigration()`；改表必须显式 `Migration` + 升 `AppDatabase.version`，`app/schemas/` 进版本库
3. **禁** 往 git 提交密钥 / 数据库 / `.backups/` / `dist/` / `*.onnx`
4. **禁** 在 system 稳定前缀里放变化内容（时间/mood/记忆块）—— 前缀缓存会整段失效
5. **禁** 在发送路径做网络请求（天气只读缓存）
6. **禁** 在 `latest_reminder` 上加第 6 段（服务端 400，恒定 5 段）
7. **禁** 裸 `kotlin.assert()`，一律 `org.junit.Assert.*`
8. **禁** 改 WorkManager 周期不换任务名（`KEEP` 会忽略新请求）

## 4. 并行开发约定

- **改之前先读 `build.gradle` 的 versionCode 与 `CHANGELOG.md` 最新条目**，避免撞号（本项目出现过两条线同时发版、版本号互相覆盖）。
- 范围：`ui/**` 归 UI 线；`domain/**`、`data/**`、`worker/**`、`service/**` 归逻辑线。共享文件（`ContextBuilder`、`CompanionApp`、`build.gradle`）小步改。
- 只改自己的范围；需要改别人范围时先说明。
- 一次改动一个 CHANGELOG 条目，别攒。

## 5. 提交信息

`<类型>(<范围>): <一句话>`，类型 `feat / fix / docs / refactor / test / chore`。
例：`fix(proactive): 时钟回拨导致周期任务冻死在 2027 年`
