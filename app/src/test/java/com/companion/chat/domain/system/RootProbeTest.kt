package com.companion.chat.domain.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** root 判据单测（任务 V2.4.1）：纯函数，覆盖每个失败方向 + 超时常量。 */
class RootProbeTest {

    private fun outcome(exit: Int, out: String, timedOut: Boolean = false) =
        ShellOutcome(exitCode = exit, output = out, timedOut = timedOut)

    @Test
    fun uid_zero_root_with_exit_zero_is_rooted() {
        assertTrue(
            RootProbe.isRooted(
                outcome(0, "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0")
            )
        )
    }

    @Test
    fun shell_uid_with_exit_zero_is_not_rooted() {
        assertFalse(RootProbe.isRooted(outcome(0, "uid=2000(shell) gid=2000(shell)")))
    }

    @Test
    fun uid_zero_but_nonzero_exit_is_not_rooted() {
        assertFalse(RootProbe.isRooted(outcome(1, "uid=0(root) gid=0(root)")))
    }

    @Test
    fun timeout_beats_every_other_signal() {
        // 超时优先：即便 exit=0 且有 uid=0，也判 false
        assertFalse(RootProbe.isRooted(outcome(0, "uid=0(root) gid=0(root)", timedOut = true)))
    }

    @Test
    fun empty_output_with_exit_zero_is_not_rooted() {
        // Magisk 点「拒绝」时某些实现也返回 0，但输出为空
        assertFalse(RootProbe.isRooted(outcome(0, "")))
    }

    @Test
    fun permission_denied_only_is_not_rooted() {
        assertFalse(RootProbe.isRooted(outcome(0, "Permission denied")))
    }

    @Test
    fun app_uid_is_not_fooled_by_uid_zero_substring() {
        // uid=10000(...) 不含连续子串 "uid=0"，不能误判
        assertFalse(RootProbe.isRooted(outcome(0, "uid=10000(app_10000) gid=10000(app_10000)")))
    }

    @Test
    fun default_timeout_is_three_seconds() {
        assertEquals(3_000L, RootProbe.DEFAULT_TIMEOUT_MS)
    }
}
