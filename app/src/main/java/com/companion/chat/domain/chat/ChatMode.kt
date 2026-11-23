package com.companion.chat.domain.chat

/**
 * 对话模式（2026-11-22）。
 *
 * - [COMPANION] **陪聊模式（默认）**：注入人设、能力说明、关系定位、记忆块、情绪块，
 *   并回写记忆/情绪/追问/提醒排钟。就是原来的行为。
 * - [NORMAL] **正常模式**：干净的问答。不注入任何陪伴向内容，也不回写任何东西
 *   （记忆抽取 / 情绪判定 / 追问抽取 / 提醒排钟 全停）。
 *
 * **两种模式用各自的会话**（`app_state.companion_conversation_id` / `normal_conversation_id`）：
 * 历史消息是 user/assistant 正文，system 关掉也挡不住它，只有物理隔离才能让正常模式
 * 不带着"宝宝想你"的语感去答技术问题。
 */
enum class ChatMode(val storageValue: String) {

    COMPANION("companion"),
    NORMAL("normal");

    /** UI 文案。 */
    val label: String
        get() = when (this) {
            COMPANION -> "陪聊模式"
            NORMAL -> "正常模式"
        }

    /**
     * 一句话区别（2026-11-22 精简）。
     *
     * **设置页会把两个模式的两句话同时列出来**——只显示当前模式那句会让人误读
     * （开关关着 = 陪聊，下面却写着"她有人设"，看着像"关掉才有"）。
     */
    val description: String
        get() = when (this) {
            COMPANION -> "有人设和记忆，会看情绪和关系，也会主动找你。"
            NORMAL -> "只当问答助手，不带人设·记忆·情绪·关系，也不记这里说的话。"
        }

    companion object {

        /** 解析存储值。缺失 / 脏数据一律回落到 [COMPANION]（默认值，也是老用户的行为）。 */
        fun fromStorage(raw: String?): ChatMode =
            values().firstOrNull { it.storageValue == raw } ?: COMPANION
    }
}