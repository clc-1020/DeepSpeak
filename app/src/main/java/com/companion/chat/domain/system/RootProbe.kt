package com.companion.chat.domain.system

/**
 * root 判据（任务 V2.4.1）：纯函数，无 Android 依赖，可 JVM 单测。
 *
 * 判据严格（三条同时成立）的理由见交付说明 / 计划书：只看 `su` 存在会被 SELinux 骗、
 * 只看 exit=0 会被「Magisk 点拒绝也返回 0」骗，必须验 `su -c id` 的 `uid=0`。
 */
object RootProbe {
    /** 计划书要求的「每条 3s 超时」。 */
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /**
     * 诊断输出保留的前缀字符数。
     *
     * 单一出处：`data/system/ProcessShellRunner` 记日志、`data/system/RootPermissionChecker`
     * 拼失败消息都用它，避免两处各写一个 200 而将来只改一处漂移。
     */
    const val LOG_HEAD_CHARS = 200

    /**
     * 是否真的拿到 root。判据三条同时成立：
     *  1. 没超时；
     *  2. 退出码 0；
     *  3. 输出里出现 `uid=0`（`id` 的输出形如 `uid=0(root) gid=0(root) ...`）。
     *
     * `contains("uid=0")` 对 `uid=10000(...)` 是 false（不误判），对 `uid=0(root)` 是 true，
     * 够用；不要写正则去解析 uid 数字。
     */
    fun isRooted(outcome: ShellOutcome): Boolean =
        !outcome.timedOut && outcome.exitCode == 0 && outcome.output.contains("uid=0")
}
