package com.companion.chat.data.system

import com.companion.chat.domain.system.RootProbe
import com.companion.chat.domain.system.ShellOutcome
import com.companion.chat.domain.system.ShellRunner
import kotlinx.coroutines.CancellationException

/**
 * root 权限检测（V2.4.1）。**不持有 Context**：`su -c id` 不需要包名，
 * V2.4.2 需要包名时由调用方传进 `run(command)`。
 *
 * 全部在 Dispatchers.IO 执行（计划书硬要求「禁止主线程」）——实际由 [runner] 保证。
 * **不缓存结果**：Magisk 的授权可随时被用户撤销，缓存会让设置页显示错误的「已 root」。
 *
 * 本轮**没有任何自动调用点**：不在 CompanionApp / onCreate / WorkManager / Receiver 里调
 * `isRooted()`，否则每次启动弹 Magisk 授权框。
 *
 * 取消（[CancellationException]）必须向上传播，不能被「任何异常归 false」吞掉：
 * 否则 V2.4.2 的 5 条命令循环在协程已取消后仍会跑完剩余几条、日志刷「Job was cancelled」。
 */
class RootPermissionChecker(
    private val runner: ShellRunner = ProcessShellRunner(),
    private val timeoutMs: Long = RootProbe.DEFAULT_TIMEOUT_MS,
) {
    /**
     * 原始探测结果（V2.4.3 用它区分「设备没 su」/「Magisk 弹框被拒」/「超时」三种提示）。
     *
     * 取消时重新抛出；其它任何异常都兜成 `exitCode = -1` 的合成结果，绝不抛。
     * 每次调用都实跑，不缓存（授权可随时被撤销）。
     */
    suspend fun probe(): ShellOutcome = try {
        runner.run(PROBE_COMMAND, timeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ShellOutcome(
            exitCode = -1,
            output = "${e.javaClass.simpleName}: ${e.message}",
            timedOut = false,
        )
    }

    /** root 设备 true / 非 root false；任何异常都归为 false（取消除外，向上传播）。 */
    suspend fun isRooted(): Boolean = RootProbe.isRooted(probe())

    /**
     * 跑一条命令（V2.4.2 保活脚本用）。成功=退出码 0，失败=非 0/超时/起不来，绝不抛（取消除外）。
     *
     * [timeoutMs] 可按单条命令覆盖构造器默认值：低端机 `dumpsys deviceidle whitelist`
     * 冷启动偶尔 >3s，不必为此再造一个 checker。现有 `run(command)` 调用点零改动。
     */
    suspend fun run(command: String, timeoutMs: Long = this.timeoutMs): Result<String> = try {
        val outcome = runner.run(command, timeoutMs)
        val normalized = normalize(outcome.output)
        when {
            outcome.timedOut -> Result.failure(
                IllegalStateException("超时 ${timeoutMs}ms：$command")
            )
            outcome.exitCode < 0 -> Result.failure(
                IllegalStateException("无法执行 su：$command")
            )
            outcome.exitCode != 0 -> Result.failure(
                IllegalStateException(
                    "退出码 ${outcome.exitCode}：${normalized.take(RootProbe.LOG_HEAD_CHARS)}"
                )
            )
            else -> Result.success(normalized)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val PROBE_COMMAND = "id"

        /** 规范化：trim() + 去 `\r`（su 输出可能带 Windows 风格回车）。 */
        fun normalize(raw: String): String = raw.replace("\r", "").trim()
    }
}
