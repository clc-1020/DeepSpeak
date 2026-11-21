package com.companion.chat.data.system

import com.companion.chat.domain.system.RootProbe
import com.companion.chat.domain.system.ShellOutcome
import com.companion.chat.domain.system.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 对外门面单测（任务 V2.4.1）：假 ShellRunner 记录入参，验证命令/超时/失败语义/不缓存/取消透传。 */
class RootPermissionCheckerTest {

    private class FakeShellRunner : ShellRunner {
        var calls = 0
        var lastCommand: String? = null
        var lastTimeoutMs: Long = -1L
        var outcomes = mutableListOf<ShellOutcome>()

        override suspend fun run(command: String, timeoutMs: Long): ShellOutcome {
            calls++
            lastCommand = command
            lastTimeoutMs = timeoutMs
            // 队列取完则重复最后一条，便于「连调两次」场景
            val index = (calls - 1).coerceAtMost(outcomes.lastIndex.coerceAtLeast(0))
            return outcomes[index]
        }
    }

    /** runner 直接抛异常，验证 probe / isRooted 的兜底与取消透传。 */
    private class ThrowingShellRunner(private val error: Exception) : ShellRunner {
        override suspend fun run(command: String, timeoutMs: Long): ShellOutcome = throw error
    }

    private fun checker(runner: ShellRunner) = RootPermissionChecker(runner = runner)

    @Test
    fun probe_uses_id_command_and_default_three_second_timeout() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(0, "uid=0(root) gid=0(root)", false))
        }
        checker(fake).isRooted()
        assertEquals("id", fake.lastCommand)
        assertEquals(RootProbe.DEFAULT_TIMEOUT_MS, fake.lastTimeoutMs)
        assertEquals(3_000L, fake.lastTimeoutMs)
    }

    @Test
    fun is_rooted_true_for_uid_zero_false_for_nonzero_exit() = runBlocking {
        val rooted = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(0, "uid=0(root) gid=0(root)", false))
        }
        assertTrue(checker(rooted).isRooted())

        val denied = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(1, "", false))
        }
        assertFalse(checker(denied).isRooted())
    }

    @Test
    fun run_success_returns_trimmed_output_without_cr() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(0, "  com.companion.chat\r\nsystem\r\n  ", false))
        }
        val result = checker(fake).run("dumpsys deviceidle whitelist")
        val body = result.getOrNull()
        assertNotNull(body)
        assertEquals("com.companion.chat\nsystem", body)
        assertFalse(body!!.contains('\r'))
        assertEquals("dumpsys deviceidle whitelist", fake.lastCommand)
    }

    @Test
    fun run_timeout_is_failure_with_timeout_message() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(-1, "", true))
        }
        val result = checker(fake).run("id")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertNotNull(message)
        assertTrue(message!!.contains("超时"))
        assertTrue(message.contains("3000ms"))
    }

    @Test
    fun run_nonzero_exit_is_failure_with_code_and_output_head() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(2, "error: bad command\n第二行输出", false))
        }
        val result = checker(fake).run("whatever")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertNotNull(message)
        assertTrue(message!!.contains("退出码 2"))
        assertTrue(message.contains("error: bad command"))
    }

    @Test
    fun is_rooted_is_never_cached_each_call_hits_runner() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(0, "uid=0(root) gid=0(root)", false))
        }
        val checker = checker(fake)
        assertTrue(checker.isRooted())
        assertTrue(checker.isRooted())
        assertEquals(2, fake.calls)
    }

    // ---- S1 / S2：probe() 原样透传，供 V2.4.3 区分「没 su / 被拒 / 超时」 ----

    @Test
    fun probe_returns_raw_outcome_unchanged() = runBlocking {
        val timeoutOutcome = ShellOutcome(exitCode = -1, output = "", timedOut = true)
        val fake = FakeShellRunner().apply { outcomes.add(timeoutOutcome) }
        val outcome = checker(fake).probe()
        assertSame(timeoutOutcome, outcome) // 未复制、未加工
    }

    // ---- S1：取消必须向上传播，不能吞成 false / Result.failure ----

    @Test
    fun probe_propagates_cancellation_instead_of_swallowing_it() = runBlocking {
        val checker = checker(ThrowingShellRunner(CancellationException("取消测试")))
        try {
            checker.probe()
            fail("取消必须向上传播，不能被吞")
        } catch (e: CancellationException) {
            assertEquals("取消测试", e.message)
        }
    }

    // ---- S1：非取消异常仍兜底（probe 合成结果 / isRooted 归 false） ----

    @Test
    fun probe_never_throws_and_synthesizes_outcome_on_runner_exception() = runBlocking {
        val checker = checker(ThrowingShellRunner(SecurityException("selinux denied")))
        val outcome = checker.probe()
        assertEquals(-1, outcome.exitCode)
        assertFalse(outcome.timedOut)
        assertTrue(outcome.output.contains("SecurityException"))
    }

    @Test
    fun is_rooted_is_false_when_runner_throws_non_cancellation() = runBlocking {
        val checker = checker(ThrowingShellRunner(RuntimeException("boom")))
        assertFalse(checker.isRooted())
    }

    // ---- S3：run() 支持单条命令超时覆盖 ----

    @Test
    fun run_uses_per_call_timeout_override() = runBlocking {
        val fake = FakeShellRunner().apply {
            outcomes.add(ShellOutcome(0, "ok", false))
        }
        val result = checker(fake).run("id", timeoutMs = 7_000L)
        assertEquals("ok", result.getOrNull())
        assertEquals(7_000L, fake.lastTimeoutMs)
    }
}
