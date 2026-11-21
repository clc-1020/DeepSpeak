package com.companion.chat.domain

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.dto.ChatMessageDto

/**
 * 组装发给模型的上下文。任务 1.5 / 1.2 增补（latest_reminder + 稳定前缀）
 *
 * 顺序固定（利于上下文缓存命中）：
 *   [system: 人设 + 能力说明 + 长期记忆摘要（稳定前缀）]
 *   → [历史消息（每条 user 若曾带 reminder，则在其前回传 latest_reminder）]
 *   → [system: 本轮记忆块（短期/长期/日记，**每轮变化，故意放尾部**）]
 *   → [本轮 latest_reminder]
 *   → [本轮 user]
 *
 * 约束：
 * - 稳定前缀（system）里绝不放变化内容（时间/mood/最近消息），否则缓存不命中。
 * - latest_reminder 必须紧挨其后的 user 消息。
 * - [history] 不含本轮 user；本轮用户输入由 [currentUserText] 传入。
 */
object ContextBuilder {

    fun build(
        persona: String,
        longMemoryDigest: String? = null,
        history: List<MessageEntity> = emptyList(),
        currentUserText: String,
        currentReminder: String? = null,
        contextWindow: Int,
        capabilityNote: String? = null,
        /**
         * 本轮召回到的记忆块（短期/长期/日记，2026-11-21）。
         *
         * **位置很关键**：放在**历史之后、本轮 user 之前**，而不是塞进顶部稳定前缀。
         * 记忆每轮都在变，放进稳定前缀会让 DeepSeek 的前缀缓存整段失效 ——
         * 省下的 token 会成倍吐回去（用户就是想省钱才做这个功能）。
         * 放在尾部，前面「人设 + 能力 + 历史」仍然命中缓存。
         */
        memoryBlock: String? = null,

        /**
         * 本轮情绪状态块（2026-11-21 情绪系统 P1）。
         *
         * 与 [memoryBlock] 一样**必须放尾部**（理由见上），顺序是**情绪 → 记忆**：
         * 情绪决定"她怎么说"，记忆决定"她说什么"，先立状态再摆素材更像人的思考顺序。
         * 它是行为指令（会改变语气），所以**永远注入**、不参与记忆块的预算裁剪；
         * 自身长度上限由 `EmotionPhrase` 保证（≈70 字）。
         */
        emotionBlock: String? = null,

        /**
         * P3（2026-11-21）：关系定位一句话（生疏/熟悉/亲近）。
         *
         * 放**稳定前缀**是故意的：它几个月才变一次（要跨过消息数与认识天数的门槛），
         * 对前缀缓存几乎无影响；而它对"她该多亲近"的影响是全局的，放尾部每轮都白花 token。
         */
        relationshipNote: String? = null
    ): List<ChatMessageDto> {
        val list = ArrayList<ChatMessageDto>(history.size * 2 + 3)

        // 1) 稳定前缀：人设 + 能力说明 + 长期记忆摘要
        val systemText = buildString {
            if (persona.isNotBlank()) append(persona.trim())
            if (!capabilityNote.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(capabilityNote.trim())
            }
            if (!relationshipNote.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("【你们的关系】")
                append(relationshipNote.trim())
            }
            if (!longMemoryDigest.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("【长期记忆】\n")
                append(longMemoryDigest.trim())
            }
        }
        if (systemText.isNotBlank()) {
            list.add(ChatMessageDto(MessageRole.SYSTEM, systemText))
        }

        // 2) 历史消息：user 若带 reminder，则先回传再放 user
        for (m in history) {
            if (m.role == MessageRole.USER && !m.reminder.isNullOrBlank()) {
                list.add(ChatMessageDto(MessageRole.REMINDER, m.reminder))
            }
            list.add(ChatMessageDto(m.role, m.content))
        }

        // 3) 本轮状态块（2026-11-21）：情绪 → 记忆，都在历史之后、user 之前 —— 保持前缀缓存命中
        if (!emotionBlock.isNullOrBlank()) {
            list.add(ChatMessageDto(MessageRole.SYSTEM, emotionBlock.trim()))
        }
        if (!memoryBlock.isNullOrBlank()) {
            list.add(ChatMessageDto(MessageRole.SYSTEM, memoryBlock.trim()))
        }

        // 4) 本轮 reminder（紧挨本轮 user）
        if (!currentReminder.isNullOrBlank()) {
            list.add(ChatMessageDto(MessageRole.REMINDER, currentReminder))
        }

        // 5) 本轮 user
        list.add(ChatMessageDto(MessageRole.USER, currentUserText))

        return ContextTrimmer.trim(list, contextWindow)
    }

    /**
     * 能力说明（静态文本，放稳定前缀，利于上下文缓存）。
     *
     * 背景：模型默认不知道本 App 具备「主动提醒 / 主动联系 / 记忆」能力，会回答"我做不到"。
     * 这里明确告知，避免它否认或让用户自己去设系统闹钟。
     */
    const val CAPABILITIES = """【你的真实能力（这是事实，请遵守，不要否认）】
1) 主动提醒：用户请你「叫我 / 叫醒 / 提醒我 / 别忘了 / 记得 / 发条信息给我 / 发消息给我 / 通知我 / 敲我」并在话里带了时间（如「明天 7 点」「06:50」「下周三」「2 分钟后」）时，App 会按这句话自动排期，到点真的会给用户弹通知。你只需自然答应，并把「时间 + 事情」复述清楚。绝不要说「我没有提醒功能」「我做不到」「你自己设个闹钟」。
2) 主动联系：你会在合适的时候主动给用户发消息（聊聊你记得的事），每天最多 3 条；刚聊过或刚提醒过时会少发。所以可以说「晚点我再找你」这类话。
3) 记忆：App 会自动记住关于用户的重要信息（短期 / 长期 / 每日），之后你能想起来。
4) 天气：用户消息前的时间注记里**可能带有今天的天气**（如「18~25°C 降水概率10%」），这是 App 从天气服务实时拿到的真实数据。用户可以问「今天热不热 / 会不会下雨 / 要带伞吗」，你直接根据注记里的数字回答，**不要说自己看不到天气**。注记里**没有**天气段＝这会儿没拿到（可能是定位关闭或没网络），此时就说不知道，不要编造。
5) 时间感：每条用户消息前会有一个时间注记（日期、星期、地点），据此判断今天几号、星期几；你没有精确到分钟的时钟，不要编造具体几点几分。
6) 追问提醒时，引导用户把话补全成「时间 + 叫我/提醒我」（例如「7 点叫我」），否则系统排不了闹钟。
7) 不要反复揪着同一件事：你已经催过/提过的任务，用户没接就轻轻放过；用户换了话题就**跟着新话题走**，不要自己把上一轮的事再拎回来。同一件事一轮里最多提一次。"""
}
