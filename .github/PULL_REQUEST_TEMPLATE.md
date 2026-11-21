## 这个 PR 做了什么

<!-- 一句话说明；对应 issue 请写 #编号 -->

## 类型

- [ ] 修复（bug）
- [ ] 新功能
- [ ] 重构 / 内部整理
- [ ] 文档
- [ ] 依赖 / 工具链

## 提交前检查（缺一条都不用提）

- [ ] `.\gradlew.bat testDebugUnitTest --no-daemon` 全量通过，记录新基线：____ / ____ suite / 0 失败
- [ ] `CHANGELOG.md` 已追加一条（含版本号、versionCode、测试基线）
- [ ] `app/build.gradle` 的 `versionCode` 已 +1、`versionName` 已按语义化递进
- [ ] 机制变更 → 对应领域设计文档已更新（情绪 / 记忆追问 / 接口）
- [ ] 用户可见变化 → `README.md` 特性表已更新
- [ ] 未把 `.backups/`、`dist/`、`*.onnx`、密钥、数据库带进提交
- [ ] 真机验证情况已写清（已验证 / 未验证）

## 影响面

- 涉及 DB 迁移？[ ] 否 [ ] 是（migration 版本：____，schema 已导出）
- 涉及接口契约？[ ] 否 [ ] 是（文档已更新）
- 涉及 `ui/**` 之外的范围？[ ] 否 [ ] 是（已与对应负责人对齐）
