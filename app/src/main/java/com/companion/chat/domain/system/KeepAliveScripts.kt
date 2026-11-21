package com.companion.chat.domain.system

/**
 * root 保活脚本清单（任务 V2.4.2）：纯函数，可 JVM 单测。
 * 平台执行在 `data/system/KeepAliveScriptRunner`。
 *
 * 为什么单独拆出来：命令串是本任务唯一「值得测的东西」（哪条、什么顺序、多长超时、是否可选），
 * 而执行层必然碰 `su` / `ProcessBuilder`，主机无法覆盖。与 `RootProbe` + `ProcessShellRunner`
 * 同一套「纯逻辑进 domain、平台实现进 data」的分法。
 */
object KeepAliveScripts {

    /** 一步：命令原文 + 单条超时 + 给用户看的短标签 + 是否可选。 */
    data class Step(val command: String, val timeoutMs: Long, val label: String, val optional: Boolean)

    /**
     * 按顺序生成 5 条命令。[pkg] 为调用方包名。
     *
     * 刻意**不含** `pm grant <pkg> REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：
     * 那是 install-time 普通权限，`pm grant` 会报 `not a changeable permission type`（计划书 L1171 已删）。
     * 也**不含**厂商私有 sqlite（miui_security.db / appops.db / huawei_powergenie.db）：
     * 计划书已把它降级为可选、失败只记日志、不进验收；改错会破坏系统数据，本轮不做。
     *
     * `AUTO_START` 是 MIUI 专有 op（计划书 L1169 已注明），其它 ROM 上必失败 →
     * `optional = true`，执行层只在日志尾部加一句「（可选，失败可忽略）」，不影响外层 Result。
     */
    fun commands(pkg: String): List<Step> = listOf(
        Step("dumpsys deviceidle whitelist +$pkg", DUMP_TIMEOUT_MS, "电池优化白名单", false),
        Step("cmd appops set $pkg RUN_ANYWHERE allow", DEFAULT_TIMEOUT_MS, "appops RUN_ANYWHERE", false),
        Step("cmd appops set $pkg RUN_IN_BACKGROUND allow", DEFAULT_TIMEOUT_MS, "appops RUN_IN_BACKGROUND", false),
        Step("am set-standby-bucket $pkg active", DEFAULT_TIMEOUT_MS, "standby-bucket active", false),
        Step("cmd appops set $pkg AUTO_START allow", DEFAULT_TIMEOUT_MS, "appops AUTO_START（MIUI 专有）", true),
    )

    /** 计划书要求的「每条 3s」：取自 [RootProbe.DEFAULT_TIMEOUT_MS]，不另写 3000 常量。 */
    const val DEFAULT_TIMEOUT_MS = RootProbe.DEFAULT_TIMEOUT_MS

    /**
     * `dumpsys deviceidle whitelist` 冷启动偶尔 >3s（低端机首帧 dumpsys 要等 system_server 回包），
     * 单条放宽到 5s。这是**有意放宽**：3s 卡住会把「已经生效的白名单」记成 fail 日志，
     * 比多等 2 秒更容易误导人。其余 4 条仍是 3s。
     */
    const val DUMP_TIMEOUT_MS = 5_000L
}