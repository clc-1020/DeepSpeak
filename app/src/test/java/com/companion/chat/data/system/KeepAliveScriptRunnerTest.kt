package com.companion.chat.data.system

import com.companion.chat.domain.system.KeepAliveScripts
import com.companion.chat.domain.system.RootProbe
import com.companion.chat.domain.system.ShellOutcome
import com.companion.chat.domain.system.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 保活脚本库单测（任务 V2.4.2）。
 *
 * 假 `ShellRunner` 记录入参并按队列返回 outcome（取完重复最后一条），范式同
 * `RootPermissionCheckerTest.FakeShellRunner`；这里另写一份，避免跨 suite 依赖。
 */
class KeepAliveScriptRunnerTest {

    private val pkg = "com.companion.chat"

    private class FakeShellRunner : ShellRunner {
        var calls = 0
        var commands = mutableListOf<String>()
        var timeouts = mutableListOf<Long>()
        var outcomes = mutableListOf<ShellOutcome>()

        /** 第 N 次调用（1 起）抛这个异常；null 表示不抛。 */
        var throwOnCall: Int? = null
        var thrown: Exception = CancellationException("取消测试")

        override suspend fun run(command: String, timeoutMs: Long): ShellOutcome {
            calls++
            commands += command
            timeouts += timeoutMs
            if (calls == throwOnCall) throw thrown
            val index = (calls - 1).coerceAtMost(outcomes.lastIndex.coerceAtLeast(0))
            return outcomes[index]
        }
    }

    private fun rooted() = ShellOutcome(0, "uid=0(root) gid=0(root)", false)

    private fun ok() = ShellOutcome(0, "ok", false)

    private fun fail(code: Int = 1) = ShellOutcome(code, "error: nope", false)

    private fun runner(probeFirst: ShellOutcome, rest: List<ShellOutcome>): Pair<KeepAliveScriptRunner, FakeShellRunner> {
        val fake = FakeShellRunner().apply { outcomes.add(probeFirst); outcomes.addAll(rest) }
        return KeepAliveScriptRunner(RootPermissionChecker(runner = fake)) to fake
    }

    // ---- 1. 清单本身 ----

    @Test
    fun commands_has_five_steps_and_contains_pkg() {
        val steps = KeepAliveScripts.commands(pkg)
        assertEquals(5, steps.size)
        for (step in steps) {
            assertTrue("命令里缺包名：${step.command}", step.command.contains(pkg))
            assertTrue("标签不能为空", step.label.isNotBlank())
        }
        // 顺序固定：白名单 → RUN_ANYWHERE → RUN_IN_BACKGROUND → standby-bucket → AUTO_START
        assertTrue(steps[0].command.startsWith("dumpsys deviceidle whitelist +"))
        assertTrue(steps[1].command.contains("RUN_ANYWHERE"))
        assertTrue(steps[2].command.contains("RUN_IN_BACKGROUND"))
        assertTrue(steps[3].command.startsWith("am set-standby-bucket"))
        assertTrue(steps[4].command.contains("AUTO_START"))
    }

    // ---- 2. 计划书明确删掉 pm grant（install-time 普通权限，pm grant 会报错） ----

    @Test
    fun commands_never_contains_pm_grant() {
        val dump = KeepAliveScripts.commands(pkg).joinToString("\n") { "${it.command}\n${it.label}" }
        assertFalse("清单里不该出现 pm grant", dump.contains("pm grant"))
        assertFalse("也不该出现 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", dump.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse("也不该碰厂商私有 sqlite", dump.contains(".db"))
    }

    // ---- 3. 超时：dumpsys 单独 5s，其余 3s ----

    @Test
    fun commands_dumpsys_timeout_is_five_seconds() {
        val steps = KeepAliveScripts.commands(pkg)
        assertEquals(5_000L, steps[0].timeoutMs)
        assertEquals(KeepAliveScripts.DUMP_TIMEOUT_MS, steps[0].timeoutMs)
        for (step in steps.drop(1)) {
            assertEquals(RootProbe.DEFAULT_TIMEOUT_MS, step.timeoutMs)
            assertEquals(3_000L, step.timeoutMs)
        }
        // 只有 AUTO_START 标 optional
        assertEquals(listOf(false, false, false, false, true), steps.map { it.optional })
    }

    // ---- 4. 非 root：一条命令都不跑，只探测一次 ----

    @Test
    fun non_root_returns_failure_and_runs_only_probe() = runBlocking {
        val fake = FakeShellRunner().apply { outcomes.add(ShellOutcome(1, "", false)) }
        val result = KeepAliveScriptRunner(RootPermissionChecker(runner = fake)).run(pkg)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertNotNull(message)
        assertTrue("失败消息必须提示需要 root：$message", message!!.contains("需要 root"))
        assertTrue(message.contains("exit=1"))

        // 硬要求：非 root 时只探测一次（id），一条保活命令都不跑
        assertEquals(1, fake.calls)
        assertEquals(listOf("id"), fake.commands)
    }

    // ---- 4b. 非 root 失败消息：内容保留，只把换行压成空格 ----

    @Test
    fun non_root_failure_message_has_no_newlines() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(1, "su: permission denied\r\nsecond line\rmore", false))
        }
        val result = KeepAliveScriptRunner(RootPermissionChecker(runner = fake)).run(pkg)
        val message = result.exceptionOrNull()?.message
        assertNotNull(message)
        assertFalse("message 里不该有换行：$message", message!!.contains('\n'))
        assertFalse("message 里不该有回车：$message", message.contains('\r'))
        assertTrue("内容不能丢，只该被空格拼接：$message", message.contains("second line"))
    }

    // ---- 5. 单条失败：不中断后续，外层仍 success，日志 5 行 ----

    @Test
    fun one_failure_does_not_stop_others() = runBlocking {
        val (runner, fake) = runner(rooted(), listOf(ok(), fail(), ok(), ok(), ok()))
        val result = runner.run(pkg)

        assertTrue("单条失败不能让外层变 failure", result.isSuccess)
        val logs = result.getOrNull()
        assertNotNull(logs)
        assertEquals(5, logs!!.size)
        assertTrue(logs[0].contains("ok"))
        assertTrue(logs[1].contains("fail"))
        assertTrue(logs[2].contains("ok"))
        assertTrue(logs[3].contains("ok"))
        assertTrue(logs[4].contains("ok"))
        // 失败行带退出码与原因，便于 V2.4.3 对话框直接显示
        assertTrue(logs[1].contains("退出码 1"))

        // 5 条命令全跑（外加 1 次 id 探测），顺序与清单一致
        assertEquals(6, fake.calls)
        assertEquals(
            listOf("id") + KeepAliveScripts.commands(pkg).map { it.command },
            fake.commands,
        )
        // 每条命令用各自的超时
        assertEquals(listOf(3_000L, 5_000L, 3_000L, 3_000L, 3_000L, 3_000L), fake.timeouts)
    }

    // ---- 6. 取消必须透传，不能被吞进 Result.failure ----

    @Test
    fun cancellation_propagates() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(rooted())
            outcomes.add(ok())
            throwOnCall = 2 // 探测成功，第 1 条命令执行时被取消
        }
        try {
            KeepAliveScriptRunner(RootPermissionChecker(runner = fake)).run(pkg)
            fail("CancellationException 必须向上传播，不能被吞成 Result.failure")
        } catch (e: CancellationException) {
            assertEquals("取消测试", e.message)
        }
        assertEquals(2, fake.calls)
    }

    // ---- 7. optional 步骤失败：日志尾部标注，仍不影响外层 ----

    @Test
    fun optional_step_failure_is_tagged_optional() = runBlocking {
        val (runner, _) = runner(rooted(), listOf(ok(), ok(), ok(), ok(), fail()))
        val result = runner.run(pkg)

        assertTrue(result.isSuccess)
        val logs = result.getOrNull()!!
        assertEquals(5, logs.size)
        assertTrue("AUTO_START 失败行应标可选：${logs[4]}", logs[4].contains("（可选，失败可忽略）"))
        // 非 optional 的失败行不带这句
        val (runner2, _) = runner(rooted(), listOf(fail(), ok(), ok(), ok(), ok()))
        assertFalse(runner2.run(pkg).getOrNull()!![0].contains("（可选，失败可忽略）"))
    }
}