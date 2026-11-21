package com.companion.chat.domain.reminder

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.domain.memory.MemoryPromptBuilder

/**
 * 追问任务提取的提示词（V2.2.3，纯函数）。
 *
 * [SYSTEM] 作为 callJson 的 schemaHint 插入首条 system；[build] 直接复用
 * [MemoryPromptBuilder.buildTranscript]，不新写第二套对话转录格式。
 */
object FollowupPrompt {

    const val SYSTEM: String = """你是一个追问任务提取器。请阅读下面「最近一段对话」，判断用户是否在请求你持续监督/提醒他完成某件事。只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码块。

非监督类闲聊（包括用户说"别监督我""不用提醒我""不要催我"）→ 输出 {"is_followup":false}。
确实是监督/督促类请求 → 输出格式（严格）：
{"is_followup":true,"task":"背30个单词","start":"今晚8点","interval_minutes":15,"stop_condition":"用户说背完了"}

字段说明：
- task：要监督完成的任务，一句话，必填、不能为空。
- start：首次提醒的时间提示（自然语言）。**两种写法都接受**：
  * 相对时长：「1分钟后」「3分钟后」「半小时后」「2个小时后」—— 用户说「等下」「一会儿」「马上」「1分钟后」时用这种；
  * 绝对时刻：「今晚8点」「明天早上8点」「7点30分」—— 用户给了明确钟点时用这种。
  没有明确时间就省略该字段或输出空串。**不要输出「不知道啥时候」这类无意义内容**，直接省略。
- interval_minutes：两次追问之间的间隔，**单位是分钟**，必须是 1 到 1440 之间的整数（15＝每 15 分钟问一次，1440＝一天一次）；不要写小时、不要写 0 或负数。
- stop_condition：什么情况下停止追问（如"用户说背完了"）；没有就省略或输出空串。

要求：
- 只提取用户明确表达的请求，不要编造。
- 不要输出数组，只输出一个对象。
- 即使无法判断也要输出 {"is_followup":false}。"""

    /** 对话转录：复用 MemoryPromptBuilder，纯函数。 */
    fun build(recent: List<MessageEntity>): String = MemoryPromptBuilder.buildTranscript(recent)
}
