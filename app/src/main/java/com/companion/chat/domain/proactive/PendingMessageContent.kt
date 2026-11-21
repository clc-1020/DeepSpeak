package com.companion.chat.domain.proactive

/**
 * 待发消息内容的类型安全封装。任务 3.2（审查改进①）
 *
 * 队列的 `content` 列是字符串（wire 层，利于 SQL），但生产/消费两端统一走本类编解码，
 * 避免 3.5 忘记解析前缀把 `"memory:123"` 当正文发出去。
 *
 * 编码用紧凑 tag 前缀（比 JSON 轻，无需 Gson，纯 JVM 可测）：
 *  - [Memory]    → `"memory:<id>"`
 *  - [LongMemory] → `"longmem:<id>"`（1.9.4.0：长期情绪记忆话题，通知"别提"回执用）
 *  - [PlainText] → `"text:<text>"`
 *  - [Instruction] → `"instr:<memoryId|0>:<task>"`
 *  - [Environment] → `"env:<stableId>"`
 *  - [Festival]  → `"fest:<name>"`
 *  - [Followup]  → `"followup:<reminderId>:<attempt>:<task>"`（V2.2.4）
 *  - 无法识别/无前缀的历史值 → 一律按 [PlainText] 原样处理（向后兼容）。
 */
sealed class PendingMessageContent {

    /** 记忆话题驱动的消息，正文由 3.5 用该记忆生成。 */
    data class Memory(val memoryId: Long) : PendingMessageContent()

    /**
     * 1.9.4.0：长期情绪记忆话题（无短期记忆时的兜底）。正文由 hook 文本生成，
     * 这里只带 id，供通知"别提这个"回执调用 [com.companion.chat.data.mood.EmotionalMemoryService.dismissById]。
     */
    data class LongMemory(val memoryId: Long) : PendingMessageContent()

    /** 纯文本内容（指令提醒、节日文案等直接可发的文本）。 */
    data class PlainText(val text: String) : PendingMessageContent()

    /**
     * 用户指令提醒（如「7 点叫我喝水」）。[memoryId] 为排钟时为其创建的短期记忆 id，
     * 用于「已完成」回执删除该记忆；null 表示无关联（旧数据或创建失败）。
     */
    data class Instruction(val task: String, val memoryId: Long? = null) : PendingMessageContent()

    /**
     * 环境钩子（V2.1.4 新增）。**只带 [stableId]，不带正文，也不带数值**。
     *
     * 三条约束（计划书 §3.4）：
     * 1. 正文由送达层生成 —— 在过闸门之前生成等于给「可能被拦掉的消息」白烧一次 LLM 调用
     * 2. `Rain(80)` / `TempDrop(6)` 这类数值**只作触发条件**，不进编码；正文走泛化措辞
     *    （「今天有雨，记得带伞」）。真需要数字时走 `stableId + hint` 的升级路径
     * 3. **绝不能把数值拼进 [stableId]**（如 `rain_80`）：去重键是
     *    `env_sent_<stableId>_<dateKey>`，数值一波动（78%→80%→82%）就会被当成三件事各发一条
     */
    data class Environment(val stableId: String) : PendingMessageContent()

    /**
     * 节日/生日祝福名（内置节日或用户自存日期）。任务 V2.1.7
     *
     * **只带名字，不带正文、不带年份**：去重键是 `fes_sent_<name>_<dateKey>`，
     * 年份一进去每年都成新键，用户存的生日就永远只能收到一次。
     * 名字来自 [SpecialDateHit.name][com.companion.chat.domain.proactive.SpecialDateHit]，
     * 两个子类（用户自存 / 内置节日）都走这里。
     */
    data class Festival(val name: String) : PendingMessageContent()

    /**
     * 追问链（V2.2.4）。**自带 [task] 与 [attempt]**，不靠送达层回查 DB——
     * `LlmProactiveMessageGenerator` 里没有 `ReminderDao`，回查会引入新依赖。
     * V2.2.5 取 `reminderId` 挂通知按钮（`decode(...) as? Followup`）。
     *
     * [attempt] 是**本次追问的序号**（1 起，= 到点时的 `attemptCount + 1`），
     * 不是剩余次数；[task] 是 `ReminderEntity.taskDesc` 原文。
     */
    data class Followup(
        val reminderId: Long,
        val attempt: Int,
        val task: String
    ) : PendingMessageContent()

    /** 编码为写入 `pending_messages.content` 的字符串。 */
    fun encode(): String = when (this) {
        is Memory -> MEMORY_PREFIX + memoryId
        is LongMemory -> LONGMEM_PREFIX + memoryId
        is PlainText -> TEXT_PREFIX + text
        is Instruction -> INSTR_PREFIX + (memoryId ?: 0L) + ":" + task
        is Environment -> ENV_PREFIX + stableId
        is Festival -> FEST_PREFIX + name
        // 任务原文可能自带 ':'，所以只切前两段，剩下的整段当 task（解码侧同解析顺序）
        is Followup -> FU_PREFIX + reminderId + ":" + attempt + ":" + task
    }

    companion object {
        const val MEMORY_PREFIX = "memory:"
        const val LONGMEM_PREFIX = "longmem:"
        const val TEXT_PREFIX = "text:"
        const val INSTR_PREFIX = "instr:"
        const val ENV_PREFIX = "env:"
        const val FEST_PREFIX = "fest:"
        const val FU_PREFIX = "followup:"

        /** 解析存储字符串为类型化内容；未知格式按 [PlainText] 兜底。 */
        fun decode(raw: String): PendingMessageContent = when {
            raw.startsWith(MEMORY_PREFIX) ->
                raw.removePrefix(MEMORY_PREFIX).toLongOrNull()?.let { Memory(it) } ?: PlainText(raw)
            // 1.9.4.0：longmem 必须放在 memory 之后判断（前缀不同不冲突，但保持顺序清晰）
            raw.startsWith(LONGMEM_PREFIX) ->
                raw.removePrefix(LONGMEM_PREFIX).toLongOrNull()?.let { LongMemory(it) } ?: PlainText(raw)
            raw.startsWith(TEXT_PREFIX) -> PlainText(raw.removePrefix(TEXT_PREFIX))
            raw.startsWith(INSTR_PREFIX) -> {
                val rest = raw.removePrefix(INSTR_PREFIX)
                val sep = rest.indexOf(':')
                if (sep < 0) {
                    PlainText(raw)
                } else {
                    val id = rest.substring(0, sep).toLongOrNull() ?: 0L
                    Instruction(rest.substring(sep + 1), if (id == 0L) null else id)
                }
            }
            // 放在 else 之前；只剩下的 stableId 为空时当 PlainText，避免造出 Environment("")
            raw.startsWith(ENV_PREFIX) ->
                raw.removePrefix(ENV_PREFIX).takeIf { it.isNotBlank() }
                    ?.let { Environment(it) } ?: PlainText(raw)
            // V2.1.7：与 env 同样的空值兜底，不改 else 契约（memoryIdOrNull / as? 调用点依赖它）
            raw.startsWith(FEST_PREFIX) ->
                raw.removePrefix(FEST_PREFIX).takeIf { it.isNotBlank() }
                    ?.let { Festival(it) } ?: PlainText(raw)
            // V2.2.4 追问链：`followup:<id>:<attempt>:<task>`，只有 id/attempt 非法才回落。
            // task 允许为空/含 ':'（只切前两段），否则任务原文带冒号时会被截断。
            raw.startsWith(FU_PREFIX) -> {
                val rest = raw.removePrefix(FU_PREFIX)
                val i1 = rest.indexOf(':')
                val i2 = if (i1 < 0) -1 else rest.indexOf(':', i1 + 1)
                val id = if (i1 < 0) null else rest.substring(0, i1).toLongOrNull()
                val attempt = if (i1 < 0 || i2 < 0) null else rest.substring(i1 + 1, i2).toIntOrNull()
                if (id == null || id <= 0L || attempt == null || attempt <= 0) PlainText(raw)
                else Followup(id, attempt, rest.substring(i2 + 1))
            }
            else -> PlainText(raw)
        }

        /** 便捷取记忆 id：Memory 或 LongMemory 都返回，否则 null。通知"别提"按钮用。 */
        fun memoryIdOrNull(raw: String): Long? = when (val c = decode(raw)) {
            is Memory -> c.memoryId
            is LongMemory -> c.memoryId
            else -> null
        }

        /** 指令提醒关联的短期记忆 id（「已完成」回执删除用）；非指令返回 null。 */
        fun completeMemoryIdOrNull(raw: String): Long? = (decode(raw) as? Instruction)?.memoryId

        /** 1.9.4.0：长期记忆兜底话题的 id（通知"别提"回执用）；非 LongMemory 返回 null。 */
        fun longMemoryIdOrNull(raw: String): Long? = (decode(raw) as? LongMemory)?.memoryId

        /** 追问内容取 id（V2.2.5 通知按钮用）；非追问返回 null。 */
        fun followupOrNull(raw: String): Followup? = decode(raw) as? Followup
    }
}
