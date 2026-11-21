package com.companion.chat.domain.mood

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.domain.reminder.ActiveFollowup

/**
 * mood 判定的提示词构建（纯函数，可 JVM 单测）。任务 5.1 / V2.2.2
 *
 * system 要求只输出 JSON；user 给最近几轮对话 + 当前 mood。
 * withFollowup = false 时输出与任务 5.1 逐字一致（老测试零改动）。
 */
object MoodPromptBuilder {

    fun system(withFollowup: Boolean = false, withEmotion: Boolean = false): String = buildString {
        append("你是情绪分析器。根据下面的对话判断「用户」此刻的心情。")
        append("\n只输出一个 JSON，不要任何解释或代码块标记，格式：")
        val fields = buildList {
            add("\"mood\":\"normal|happy|low\"")
            add("\"reason\":\"简短原因\"")
            if (withEmotion) {
                add("\"emotions\":{\"happy\":0.5}")
                add("\"trigger\":\"引起变化的那件事，一句话\"")
            }
            if (withFollowup) {
                add("\"followups\":[{\"id\":1,\"status\":\"completed|continue|abandoned\"}]")
                add("\"followup_reason\":\"简短原因\"")
            }
        }
        append("{")
        append(fields.joinToString(","))
        append("}")
        append("\nmood 只能是 normal（平静）、happy（开心）、low（低落/疲惫/烦躁）之一。")
        if (withEmotion) {
            append("\n")
            append(
                "emotions 表示**助手自己**此刻的情绪变化，只写这次有变化的 1~3 项（不要输出全部、不要输出 0）：" +
                    "happy(开心) excited(兴奋) proud(得意) missing(想你) low(低落) hurt(委屈) annoyed(烦躁) guilty(心虚)。" +
                    "数值 0~1，0.5 以上算明显。**没有变化就写 {}，不要硬凑。**"
            )
            append("\ntrigger：引起这次变化的具体事情（≤24 字，如「他说在测试我」）；没有变化就空串。")
            append("\n负面情绪（low/hurt/annoyed/guilty）**必须有 trigger** 才会被采信。")
        }
        if (withFollowup) {
            append("\nfollowups：对**每一个**给出的任务都输出一条，id 必须原样照抄题目里给的 id，不要自己编号。")
            append("\nstatus：completed = 用户说这件已经做完了；continue = 还没做/还在做，需要继续催；abandoned = 用户明确表示不做了、不想再被问，或明确说这是测试/取消。")
        }
    }

    /** 单链便捷重载（老调用点/老测试零改动）。 */
    fun build(
        recent: List<MessageEntity>,
        currentMood: String = Mood.NORMAL,
        activeFollowup: ActiveFollowup? = null
    ): String = build(recent, currentMood, listOfNotNull(activeFollowup))

    /** 多链版本：把每一条任务的 id / 任务 / 停止条件都列出来，让模型逐条判断。 */
    fun build(
        recent: List<MessageEntity>,
        currentMood: String,
        activeFollowups: List<ActiveFollowup>
    ): String = buildString {
        append("当前心情：")
        append(currentMood)
        val lines = recent
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(10)
            .map { if (it.role == MessageRole.USER) "用户：${it.content.trim()}" else "你：${it.content.trim()}" }
        if (lines.isNotEmpty()) {
            append("\n最近对话：\n")
            append(lines.joinToString("\n"))
        }
        append("\n请判断用户此刻心情并输出 JSON。")
        if (activeFollowups.isNotEmpty()) {
            // 追问上下文放 user 内容里，不塞进 system（system 只定义输出格式）
            append("\n另有 ${activeFollowups.size} 个待追踪的任务（**每一个都要判断**）：")
            activeFollowups.forEachIndexed { i, f ->
                append("\n${i + 1}. id=")
                append(f.reminderId)
                append(" ｜ 任务：")
                append(f.task)
                append(" ｜ 停止条件：")
                append(f.stopCondition ?: "未指定")
            }
            append("\n请为上面每一个 id 输出 followups 数组项（id 原样照抄，不要合并、不要漏）。")
        }
    }
}
