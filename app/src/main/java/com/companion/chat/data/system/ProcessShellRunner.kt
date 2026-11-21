package com.companion.chat.data.system

import com.companion.chat.domain.system.RootProbe
import com.companion.chat.domain.system.ShellOutcome
import com.companion.chat.domain.system.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [ShellRunner] 的唯一平台实现（任务 V2.4.1）：用 `su -c` 起进程执行一条命令。
 *
 * 逐条 try/catch、失败只记日志不抛给调用方（与 `data/power/KeepAliveLauncher` 同一风格）。
 * 本类不做 JVM 单测：主机没有 `su`、Windows JVM 起不了 `sh`，属已知空白，真机一次性验证。
 */
class ProcessShellRunner(
    private val log: (String) -> Unit = { android.util.Log.d("RootShell", it) },
) : ShellRunner {

    override suspend fun run(command: String, timeoutMs: Long): ShellOutcome =
        withContext(Dispatchers.IO) { exec(command, timeoutMs) }

    private fun exec(command: String, timeoutMs: Long): ShellOutcome {
        var lastFailure: Exception? = null

        for (candidate in SU_CANDIDATES) {
            val process: Process
            try {
                // 坑 #1：三个独立参数——整条命令作为 su -c 的单个参数，绝不能拼成一个字符串
                process = ProcessBuilder(candidate, "-c", command)
                    .redirectErrorStream(true) // 坑 #2：stdout+stderr 合并，一起读
                    .start()
            } catch (e: Exception) {
                // 坑 #6/#11：只有「起不来」才换下一个候选；超时不会发生在这里，不会 4×3s。
                // 宽捕 Exception：IOException（找不到 su）+ SecurityException（SELinux 拒绝 exec）都算起不来。
                lastFailure = e
                log("su 候选不可用（$candidate）：${e.javaClass.simpleName}: ${e.message}")
                continue
            }

            // 坑 #3：立刻关 stdin——部分 su 会读 stdin 做二次确认，不关会卡满超时
            try {
                process.outputStream.close()
            } catch (e: Exception) {
                log("关闭 stdin 失败（$candidate）：${e.javaClass.simpleName}: ${e.message}")
            }

            // 坑 #4：先起读流线程再 waitFor——不消费 stdout 会在 64K 管道处死锁
            val buffer = ByteArrayOutputStream()
            // S4：解码放在读线程内完成，主线程只读「已发布的结果」，避免 join 超时后
            // 主线程并发读一个正被写的 ByteArrayOutputStream。
            val captured = AtomicReference<String?>(null)
            val truncated = AtomicBoolean(false)
            val reader = Thread {
                try {
                    drain(process.inputStream, buffer) { truncated.set(true) }
                    captured.set(buffer.toString("UTF-8"))
                } catch (e: Exception) {
                    log("读取输出失败（$candidate）：${e.javaClass.simpleName}: ${e.message}")
                }
            }.apply { isDaemon = true }
            reader.start()

            // 坑 #5：waitFor 超时 → destroyForcibly() 再 waitFor() 兜底（不给无限等）
            val timedOut = try {
                if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    false
                } else {
                    log("命令超时（$candidate），强制结束：$command")
                    process.destroyForcibly()
                    try {
                        if (!process.waitFor(FORCED_WAIT_MS, TimeUnit.MILLISECONDS)) {
                            log("强杀后进程仍未退出（$candidate），放弃等待")
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    true
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                true
            }

            // 坑 #4：waitFor 之后 join 读线程（有界，不无限等）
            try {
                reader.join(JOIN_READER_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            // 坑 #6：只在 waitFor 之后取 exitValue；超时分支用 -1（坑 #7：不抛）
            val exitCode = if (timedOut) -1 else try {
                process.exitValue()
            } catch (e: IllegalThreadStateException) {
                log("读取退出码失败（$candidate）：${e.javaClass.simpleName}")
                -1
            }

            val output = captured.get()
            if (output == null) {
                log("读线程未在 ${JOIN_READER_MS}ms 内结束（$candidate），输出不完整")
            }
            if (truncated.get()) log("输出超过 $MAX_OUTPUT_BYTES 字节，已截断（$candidate）：$command")

            // 坑 #12：只记 cmd + 退出码 + 输出前 200 字，不打印完整输出
            log(
                "完成（$candidate）cmd=$command exit=$exitCode timedOut=$timedOut " +
                    "outputHead=${(output ?: "").take(RootProbe.LOG_HEAD_CHARS)}"
            )
            return ShellOutcome(exitCode, output ?: "", timedOut)
        }

        val summary = lastFailure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "无候选"
        log("全部 ${SU_CANDIDATES.size} 个 su 候选都不可用：$summary")
        // 坑 #6：全失败也不抛，用 -1 表达「没能起进程」
        return ShellOutcome(-1, summary, false)
    }

    /** 一直读到 EOF（防子进程 write 阻塞），但只保留前 MAX_OUTPUT_BYTES 字节。 */
    private fun drain(input: InputStream, out: ByteArrayOutputStream, onTruncate: () -> Unit) {
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            val remaining = MAX_OUTPUT_BYTES - out.size()
            if (remaining <= 0) {
                onTruncate()
                continue
            }
            if (n <= remaining) {
                out.write(chunk, 0, n)
            } else {
                out.write(chunk, 0, remaining)
                onTruncate()
            }
        }
    }

    private companion object {
        /** su 二进制候选：Magisk 老版本装在 /sbin，部分 ROM 在 /system/xbin。 */
        val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")

        /** 单个进程 stdout 最多保留多少字节，防 `dumpsys` 打爆内存。 */
        const val MAX_OUTPUT_BYTES = 64 * 1024

        /** 强杀后 waitFor / join 读线程的兜底超时，都不无限等。 */
        const val FORCED_WAIT_MS = 1_000L
        const val JOIN_READER_MS = 1_000L
    }
}
