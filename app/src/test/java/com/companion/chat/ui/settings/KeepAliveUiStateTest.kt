package com.companion.chat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一键保活界面状态映射单测（任务 V2.4.3）。
 *
 * 本项目无 `compose-ui-test` 依赖，按钮交互无法自动化；能钉死的就是
 * 「`Result<List<String>>` → [KeepAliveUiState]」这段纯映射。
 */
class KeepAliveUiStateTest {

    private val logs = listOf(
        "[1/5] ok   电池优化白名单 → dumpsys deviceidle whitelist +com.companion.chat",
        "[2/5] ok   appops RUN_ANYWHERE → cmd appops set com.companion.chat RUN_ANYWHERE allow",
        "[3/5] ok   appops RUN_IN_BACKGROUND → cmd appops set com.companion.chat RUN_IN_BACKGROUND allow",
        "[4/5] ok   standby-bucket active → am set-standby-bucket com.companion.chat active",
        "[5/5] fail appops AUTO_START（MIUI 专有） → cmd appops set com.companion.chat AUTO_START allow → 退出码 1（可选，失败可忽略）",
    )

    @Test
    fun success_with_five_logs_maps_to_done() {
        val state = keepAliveResultToUi(Result.success(logs))
        assertTrue(state is KeepAliveUiState.Done)
        val done = state as KeepAliveUiState.Done
        assertEquals(5, done.logs.size)
        assertEquals(logs, done.logs) // 不增不减不改写
    }

    @Test
    fun success_with_empty_list_does_not_produce_empty_dialog() {
        val state = keepAliveResultToUi(Result.success(emptyList()))
        assertTrue(state is KeepAliveUiState.Done)
        val done = state as KeepAliveUiState.Done
        assertTrue("空日志要给兜底行，否则对话框一片空白", done.logs.isNotEmpty())
        assertTrue(done.logs.single().isNotBlank())
    }

    @Test
    fun failure_keeps_original_message() {
        val raw = "需要 root 权限（su 探测：exit=1 timedOut=false output=su: permission denied）"
        val state = keepAliveResultToUi(Result.failure(IllegalStateException(raw)))
        assertTrue(state is KeepAliveUiState.Failed)
        val failed = state as KeepAliveUiState.Failed
        assertEquals(raw, failed.message) // 原文，不是「执行失败」这类改写
        assertTrue(failed.message.contains("需要 root 权限"))
        assertTrue(failed.message.contains("exit=1"))
        assertTrue(failed.message.contains("timedOut=false"))
    }

    @Test
    fun failure_with_null_message_falls_back_to_class_name() {
        val state = keepAliveResultToUi(Result.failure(RuntimeException()))
        assertTrue(state is KeepAliveUiState.Failed)
        val failed = state as KeepAliveUiState.Failed
        assertTrue("message 不能是空白", failed.message.isNotBlank())
        assertTrue("退到异常类名", failed.message.contains("RuntimeException"))
        assertFalse(failed.message.contains("null"))
    }
}