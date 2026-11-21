# 参与开发（给其他 Agent / 未来的自己）

> 这个仓库不只有代码，还有一套**必须跟着改的文档**。下面说清两件事：**改完写到哪**、**动手前必须知道什么**。

---

## 一、改完必须写在哪（核心约定）

| 改动类型 | 必写 | 选写 |
|---|---|---|
| **修 bug** | `CHANGELOG.md` → `修复` | 若是**新踩的坑**，在 `docs/00-现行/陪聊APP项目规则.md` 的「踩过的坑」加一条 |
| **新功能（用户可见）** | `CHANGELOG.md` → `新增` ＋ `README.md` 的特性表 ＋ 对应领域设计文档 | `docs/00-现行/陪聊APP-最终方案.md` 若属规划内模块 |
| **机制/算法改动**（情绪衰减、记忆注入、追问判定…） | 对应**领域设计文档**（见下）＋ `CHANGELOG.md` → `变更` | — |
| **接口契约 / DB 结构 / 常量** | `docs/00-现行/陪聊APP项目规则.md`（迁移步骤、常量表）＋ 领域设计文档 | `CHANGELOG.md` |
| **纯重构 / 内部整理** | `CHANGELOG.md` → `变更` | — |
| **依赖升级 / 工具链** | `CHANGELOG.md` ＋ `README.md` 技术栈 | — |
| **只改 UI 文案/布局** | `CHANGELOG.md` → `变更`（一行） | — |

**单一事实源，别重复写三份**：

| 文档 | 只负责 |
|---|---|
| `CHANGELOG.md` | **改了什么**（结果、可感知变化、versionCode、测试基线） |
| `docs/00-现行/陪聊APP项目规则.md` | **为什么这么改**＋**必须遵守的规则**＋**踩过的坑** |
| `docs/00-现行/陪聊APP-情绪系统设计.md` | 情绪 / 关系 / 热络度的**机制** |
| `docs/00-现行/陪聊APP-记忆与追问-交接说明.md` | 记忆注入、追问链、容量淘汰的**机制与现状数据** |
| `docs/00-现行/陪聊APP-DeepSeek新API接入设计.md` | **接口层**：请求形状、字段契约、类与方法清单 |
| `README.md` | **给外人看**：定位、能力、怎么跑 |

> 判断方法：**用户能感觉到 → CHANGELOG；后来者会踩坑 → 项目规则；涉及"为什么这样设计" → 领域设计文档。**

---

## 二、动手前必读（3 份，约 15 分钟）

1. `docs/00-现行/陪聊APP项目规则.md` —— 工程规则、数据保护铁律、当前测试基线
2. `docs/00-现行/陪聊APP-情绪系统设计.md`（若改情绪/关系）或 `陪聊APP-记忆与追问-交接说明.md`（若改记忆/追问）
3. `CHANGELOG.md` 最后一个版本 —— 确认别人的最新改动没和你冲突

---

## 三、五步流程

```
1. 读：上面三份 + 相关源码（以代码为准，文档可能滞后）
2. 改：只改自己的范围（见第四节），小步提交
3. 测：.\gradlew.bat testDebugUnitTest --no-daemon  → 必须全绿，记下新基线数
4. 记：CHANGELOG.md 加一条（含 versionCode + 新基线）；需要时更新领域文档/README/项目规则
5. 升版：app/build.gradle 的 versionCode +1、versionName 按语义化递进
```

**版本号规则**：修 bug → PATCH +1（1.5.1 → 1.5.2）；新功能 → MINOR +1（1.5.1 → 1.6.0）；不兼容的结构调整 → MAJOR +1。

**提交信息**：`<类型>(<范围>): <一句话>`，类型用 `feat / fix / docs / refactor / test / chore`。
例：`feat(emotion): 热络度 14 天半衰，撒娇需热络≥0.5`

---

## 四、范围与归属

| 范围 | 归属 | 说明 |
|---|---|---|
| `app/src/main/java/.../ui/**` | **UI 线** | Compose 界面。逻辑线只做必要接线（ChatViewModel / MainActivity / SettingsScreen），改前先对齐 |
| `domain/**`、`data/**`、`worker/**`、`service/**` | **逻辑线** | 业务规则、数据、调度 |
| `app/src/test/**` | 各自 | 谁的逻辑谁写测试；纯函数（`domain/**`）必须可 JVM 单测、零 Android 依赖 |
| 共享文件（`ContextBuilder`、`CompanionApp`、`build.gradle`） | 小步改 | 改完自己跑全量测试 |

---

## 五、硬禁忌（踩过事故）

1. **禁** 直接跑 `gradlew connectedDebugAndroidTest` / `connectedCheck` —— AGP 跑完会**自动卸载 App**，删掉 `/data/data`（会话、记忆、API Key 全丢）。要跑仪器测试走 `tools/deploy.ps1 -RunTests`（自动备份→测试→重装→恢复）
2. **禁** `fallbackToDestructiveMigration()` —— 改表必须显式 `Migration` 并升 `AppDatabase.version`，`app/schemas/` 进版本库
3. **禁** 改 WorkManager 周期不换名 —— `ExistingPeriodicWorkPolicy.KEEP` 对同名任务忽略新请求，改周期永不生效（需 `SCHEDULE_SCHEMA+1` + 换名 + 黑名单）
4. **禁** 把任何密钥/数据库/备份/APK 提交进仓库 —— `.backups/`、`dist/`、`*.onnx`、`*.jks`、`*.db` 已在 `.gitignore`
5. **禁** 在稳定前缀（system）里放变化内容（时间 / mood / 最近消息 / 记忆块）—— 会让服务端前缀缓存整段失效
6. **禁** 在发送路径做网络请求 —— 天气只能读缓存（`WeatherCache.peek()`）
7. **禁** 裸 `kotlin.assert()` —— 一律 `org.junit.Assert.*`
8. **禁** 往 `app_state` 的 `latest_reminder` 加第 6 段 —— 服务端会 400，恒定 5 段

---

## 六、提交前检查清单

- [ ] `testDebugUnitTest` 全量通过（不是只跑 `--tests`），XML `failures=0 / errors=0`
- [ ] `CHANGELOG.md` 新增一条（版本号 + versionCode + 测试基线）
- [ ] `versionCode` / `versionName` 已递进
- [ ] 涉及机制 → 领域设计文档已更新；涉及新坑 → 项目规则已加
- [ ] 用户可见变化 → README 特性表已更新
- [ ] 没把 `.backups/`、`dist/`、模型、密钥带进 git（`git status` 自查）
- [ ] 真机验证过的项在 CHANGELOG 里写清"已验证 / 未验证"

---

## 七、真机调试速查

```powershell
$env:JAVA_HOME='<JDK17>'                     # 必须 JDK17，JDK21 下 kapt 会报 IllegalAccessError
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.companion.chat/.ui.MainActivity
adb logcat -d -v time | Select-String 'MemoryCtx|EmotionCtx|ProactiveDelivery|Followup|DeepSeekAsr'
```

诊断日志一律用 **`Log.w`**（MIUI 常吞 `Log.d`）。
