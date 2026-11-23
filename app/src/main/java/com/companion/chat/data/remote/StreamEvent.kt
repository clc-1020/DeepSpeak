package com.companion.chat.data.remote

import com.companion.chat.domain.chat.Citation

/** 流式聊天事件。任务 1.2 */
sealed interface StreamEvent {
    /** 一段增量文本 */
    data class Delta(val text: String) : StreamEvent

    /** 联网搜索引用（2026-11-23）：本帧从 `delta.annotations` 解析出的来源，交由上层按 url 去重落库。 */
    data class Citations(val citations: List<Citation>) : StreamEvent

    /** 会话标题（仅首条开 title 的请求会发，落 conversations.title） */
    data class Title(val text: String) : StreamEvent

    /** 流正常结束，携带 usage（可能为 null，如 API 未返回） */
    data class Completed(val usage: UsageInfo?) : StreamEvent

    /** 出错（断网 / HTTP 错误 / 解析异常等），调用方据此提示，不崩溃 */
    data class Failed(val error: Throwable) : StreamEvent
}
