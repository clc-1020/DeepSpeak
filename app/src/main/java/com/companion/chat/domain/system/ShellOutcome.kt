package com.companion.chat.domain.system

/**
 * 一条 shell 命令的执行结果（任务 V2.4.1）。
 *
 * 纯数据，无 Android 依赖：`data/` 负责真正起进程，这里只描述结果，
 * 便于 JVM 单测注入假 [ShellRunner] 覆盖判据。
 */
data class ShellOutcome(
    /** 进程退出码；-1 = 没能起进程（su 不存在 / IOException）。 */
    val exitCode: Int,
    /** stdout+stderr 合并后的原文（未 trim），调用方自己规范化。 */
    val output: String,
    /** true = 等满超时被强杀。 */
    val timedOut: Boolean,
)
