package com.companion.chat.ui.settings

/**
 * 一键保活的界面状态（任务 V2.4.3）。
 *
 * 为什么抽出来：本项目没有 `compose-ui-test` 依赖，按钮本身无法自动化验证；
 * 唯一能 JVM 单测的是「`Result<List<String>>` → 界面状态」这段映射，所以把它做成纯函数。
 * 零 Android 依赖，纯数据。
 */
sealed interface KeepAliveUiState {
    data object Idle : KeepAliveUiState

    /** 正在跑（最长约 20s：probe 3s + 5s + 3s×4）。按钮禁用 + 「正在执行…」。 */
    data object Running : KeepAliveUiState

    /** 跑完了。logs 与命令条数一致（5 条）。 */
    data class Done(val logs: List<String>) : KeepAliveUiState

    /** 没跑成（大多是没 root）。message 是 Result.failure 的原文，直接给用户看。 */
    data class Failed(val message: String) : KeepAliveUiState
}

/**
 * 把 runner 的返回值映射成界面状态。
 *
 * failure 时必须把 message 原文带出来（区分「没 su / Magisk 拒绝 / 超时」全靠它），
 * 不能兜成一句「执行失败」；连 message 为 null 时也只退到异常类名，不编文案。
 *
 * 空日志兜一行占位，避免 LogDialog 里出现「空对话框」看不出到底跑没跑。
 */
fun keepAliveResultToUi(result: Result<List<String>>): KeepAliveUiState =
    result.fold(
        onSuccess = { logs -> KeepAliveUiState.Done(logs.ifEmpty { listOf("（没有任何输出）") }) },
        onFailure = { e ->
            KeepAliveUiState.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        },
    )