package com.companion.chat.data.system

import com.companion.chat.domain.system.KeepAliveScripts
import com.companion.chat.domain.system.RootProbe
import kotlinx.coroutines.CancellationException

/**
 * root 一键保活（任务 V2.4.2）：逐条执行 [KeepAliveScripts.commands]，每条都进日志。
 *
 * 执行前**先判 root**：非 root 直接 `Result.failure`，一条命令都不跑。
 * 否则 5 条命令会让 Magisk 各弹一次授权框（5 次），且全部以 exit -1 记失败日志，纯噪音。
 *
 * 判 root 只 `probe()` **一次**，再用 [RootProbe.isRooted] 判：
 * `isRooted()` 内部已经 probe 过一次，若它返回 false 再补一次 `probe()` 取原始结果，
 * 非 root 路径就会调 runner 两次（两次 `su`，两次授权框）。所以这里自己拿原始 outcome，
 * 判据仍是同一个纯函数，语义与 `isRooted()` 完全等价。
 *
 * 单条失败**不中断后续**，也不让外层 `Result` 变 failure（计划书验收「一条失败不中断后续」）。
 * [CancellationException] 透传（与 `RootPermissionChecker` 一致）。
 *
 * 构造器用**默认值注入**而不是在 `CompanionApp` 装配：本轮零调用点、零启动副作用，
 * V2.4.3 直接 `KeepAliveScriptRunner()` 即可，测试传 `RootPermissionChecker(FakeShellRunner())`。
 */
class KeepAliveScriptRunner(
    private val checker: RootPermissionChecker = RootPermissionChecker(),
) {

    /**
     * 跑完全部 5 条脚本。成功路径 `Result.success(logs)` 且 `logs.size == commands.size`；
     * 非 root 路径 `Result.failure(IllegalStateException)`，message 带 su 探测的原始三要素。
     */
    suspend fun run(pkg: String): Result<List<String>> {
        val steps = KeepAliveScripts.commands(pkg)
        val logs = ArrayList<String>(steps.size + 1)

        val probe = checker.probe() // 一次探测：既判 root，又给失败消息留原始原因
        if (!RootProbe.isRooted(probe)) {
            // probe() 返回的是**原始** ShellOutcome，不过 run() 里的 normalize（去 \r + trim）：
            // su 输出常带 \r\n，这里必须自己把换行压成空格，否则 V2.4.3 的对话框/Toast 会断行错乱。
            // 只清理展示串，不动 probe 原始数据（V2.4.3 要靠 stderr 的换行结构判读 Magisk 拒绝原因）。
            return Result.failure(
                IllegalStateException(
                    "需要 root 权限（su 探测：exit=${probe.exitCode} " +
                        "timedOut=${probe.timedOut} " +
                        "output=${probe.output.replace("\r", "").replace("\n", " ").take(RootProbe.LOG_HEAD_CHARS)}）"
                )
            )
        }

        steps.forEachIndexed { index, step ->
            val tag = "[${index + 1}/${steps.size}]"
            val result = try {
                checker.run(step.command, step.timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { out ->
                    val head = out.replace("\n", " ").take(RootProbe.LOG_HEAD_CHARS).trim()
                    logs += "$tag ok   ${step.label} → ${step.command}" + if (head.isNotEmpty()) " → $head" else ""
                },
                onFailure = { e ->
                    val reason = (e.message ?: e.javaClass.simpleName).replace("\n", " ")
                    logs += "$tag fail ${step.label} → ${step.command} → $reason" +
                        if (step.optional) "（可选，失败可忽略）" else ""
                },
            )
        }
        return Result.success(logs)
    }
}