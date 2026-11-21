package com.companion.chat.domain.system

/**
 * 执行一条 root shell 命令的端口（任务 V2.4.1）。
 *
 * 为什么是接口：JVM 单测注入假实现，`ProcessBuilder` 那段真代码在主机上无法覆盖
 * （Windows JVM 起不了 `su`/`sh`）。平台实现在 `data/system/ProcessShellRunner`。
 */
interface ShellRunner {
    /** 执行一条命令（整体作为 `su -c` 的单个参数），超时不抛异常，用 [ShellOutcome.timedOut] 表达。 */
    suspend fun run(command: String, timeoutMs: Long): ShellOutcome
}
