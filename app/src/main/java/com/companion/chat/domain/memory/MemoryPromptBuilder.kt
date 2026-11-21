package com.companion.chat.domain.memory

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole

/**
 * 记忆提取的提示词构造（纯函数，可 JVM 单测）。任务 2.2
 *
 * 固定 system 指令（放到 callJson 的 schemaHint，作为首条 system），明确要求"只输出 JSON"，
 * 且必须包含 "JSON" 字样（json_object 模式要求消息里出现 json 关键字）。
 */
object MemoryPromptBuilder {

    /** system 指令；category 枚举取自 [MemoryCategory.ALL]（单一真源），故这里用 val 而非 const。 */
    val SYSTEM: String = """你是一个记忆提取器。请阅读下面「最近一段对话」，从中提取值得记住的信息，只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码块。

输出格式（严格）：
{"short":["..."],"long":[{"content":"...","category":"CATEGORY_ENUM"}],"daily":["..."]}

分类规则：
- short（短期记忆，几天内会用到）：临时计划、待办、近期事件，如「明天要面试」「周五前交报告」。
- long（长期记忆，关于用户的稳定信息）：稳定事实、性格/偏好、过敏/忌口、重要关系、长期目标，如「对花生过敏」「喜欢安静」。
- daily（每日日志，当天流水事件）：今天发生的值得记一笔的事，如「去了公司」「面试通过」。

要求：
- long 的 category 只能取固定枚举之一（务必用英文小写）：${MemoryCategory.ALL.joinToString("|")}
- 每条一句话、主语明确、可独立理解（不要把问答原样搬过来）。
- 涉及具体时刻时，尽量保留「上午/下午/晚上/凌晨/中午」等限定词（如「明天晚上8点」，不要只写「明天8点」），便于换算。
- 没有可提取内容的类别输出空数组 []。
- 不要编造对话里没有的信息。
- 全部为空也要输出 {"short":[],"long":[],"daily":[]}。"""

    /** 把最近若干条消息拼成对话文本（只取 user/assistant 正文，按时间正序）。 */
    fun buildTranscript(messages: List<MessageEntity>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val roleLabel = when (m.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                else -> continue // 跳过 system / latest_reminder 等
            }
            val content = m.content.trim()
            if (content.isEmpty()) continue
            sb.append(roleLabel).append('：').append(content).append('\n')
        }
        return sb.toString().trim()
    }
}
