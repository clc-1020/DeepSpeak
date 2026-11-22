package com.companion.chat.domain.proactive

import java.time.Instant
import java.time.ZoneId

/**
 * 主动消息类别：决定哪些闸门适用。任务 3.4
 *
 * **必须由消息本身的类型决定**（[PendingMessageType] → 这里的映射见 [ofPendingType]），
 * 不能一律按 [SPONTANEOUS] 评估：环境/节日/追问若被当成闲聊，就会被「冷却 1h + 每日上限
 * + 回复率」拦掉，而它们本来该绕过这些限制（V2.1.4 验收项「环境钩子消息绕过冷却时间」）。
 */
enum class ProactiveMessageCategory {
    SPONTANEOUS,   // 纯自发闲聊（受全部闸门）
    INSTRUCTION,   // 指令提醒（叫起床，仅受总开关 + 勿扰）
    FOLLOWUP,      // 追问链（仅受总开关 + 勿扰，不计入每日上限）
    SPECIAL_DATE,  // 节日/生日祝福（仅受总开关 + 勿扰；被拦时必须保留，不能当天丢弃）
    ENVIRONMENT,   // 环境钩子（仅受总开关 + 勿扰；绕过冷却/上限）
    /**
     * 熬夜提醒（V2.1.4 新增）。
     *
     * 单独一类而不是复用 [ENVIRONMENT]，是因为它的**勿扰语义相反**：熬夜提醒本身就发生在
     * 23 点之后，而默认勿扰区间是 23:00–07:00 —— 若按 ENVIRONMENT 处理，
     * 这个功能会被自己的勿扰规则 100% 拦死。所以闸门层给它 DND 豁免；
     * 用户不想被夜间打扰就关 `late_night_enabled` 设置，冲突有明确归属。
     */
    LATE_NIGHT_ALERT;

    companion object {
        /**
         * 待发消息 type code → 闸门类别。
         *
         * 用字符串而不是直接 import `PendingMessageType`：domain 层不反向依赖 data 层。
         * 未知 code 一律按 [SPONTANEOUS]（宁可多拦，不漏发）。
         */
        fun ofPendingType(code: String): ProactiveMessageCategory = when (code) {
            "instruction" -> INSTRUCTION
            "followup" -> FOLLOWUP
            "special_date" -> SPECIAL_DATE
            "environment" -> ENVIRONMENT
            "late_night" -> LATE_NIGHT_ALERT
            else -> SPONTANEOUS
        }
    }
}

/** 闸门拦截原因。任务 3.4 */
enum class ProactiveBlockReason {
    NONE,            // 放行
    MASTER_OFF,      // 主动消息总开关关闭（任务 7.1）
    DO_NOT_DISTURB,  // 勿扰区间
    COOLDOWN,        // 距上次交互不足「本轮要求间隔」（1.9.4.1：关系档随机 3~4h/2~4h/2~3h，默认兜底 1h）
    DAILY_LIMIT,     // 今日自发消息已达上限
    LOW_REPLY_RATE,  // 近 7 天回复率 < 20% → 降频一天一次
    NO_HOOK          // 记忆/环境钩子都召不出
}

/** 一次闸门评估结果。任务 3.4 */
data class ProactiveDecision(
    val allowed: Boolean,
    val reason: ProactiveBlockReason
) {
    companion object {
        fun allow() = ProactiveDecision(true, ProactiveBlockReason.NONE)
        fun block(reason: ProactiveBlockReason) = ProactiveDecision(false, reason)
    }
}

/**
 * 决策闸门的输入快照（纯数据，便于 JVM 单测）。任务 3.4
 *
 * @param nowMillis 评估时刻
 * @param category 消息类别
 * @param lastInteractionMillis 上次用户交互时间（null=从未/未知）
 * @param spontaneousSentToday 今日已发「自发」消息条数（指令/追问/手动测试不计）
 * @param dailyLimit 每日自发上限（默认 8 = 亲近档最密 2h 一条时 16 小时清醒内的封顶）
 * @param replyRate7d 近 7 天主动消息回复率（null=数据不足，按不拦截处理）
 * @param replyRateThreshold 回复率阈值（默认 0.2）
 * @param sentAnySpontaneousToday 今日是否已发过任意自发消息（用于「降频到一天一次」判断）
 * @param hookAvailable 是否有可用钩子（记忆/环境）
 * @param dndEnabled 是否启用勿扰
 * @param dndStartMinute 勿扰开始（当天第几分钟，0..1439）
 * @param dndEndMinute 勿扰结束（当天第几分钟；可小于 start 表示跨午夜）
 * @param enabled 主动消息总开关（任务 7.1；false 时直接拦截）
 */
data class ProactiveGateContext(
    val nowMillis: Long,
    val category: ProactiveMessageCategory = ProactiveMessageCategory.SPONTANEOUS,
    val lastInteractionMillis: Long? = null,
    val spontaneousSentToday: Int = 0,
    val dailyLimit: Int = ProactiveGateRules.DEFAULT_DAILY_LIMIT,
    val replyRate7d: Double? = null,
    val replyRateThreshold: Double = 0.2,
    val sentAnySpontaneousToday: Boolean = false,
    val hookAvailable: Boolean = true,
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 23 * 60,
    val dndEndMinute: Int = 7 * 60,
    val enabled: Boolean = true,
    /**
     * 1.9.4.1：本轮最低间隔（毫秒），默认 = [ProactiveGateRules.COOLDOWN_MILLIS]（向后兼容）。
     * 生产由关系档随机算出（生疏 3~4h / 熟悉 2~4h / 亲近 2~3h），从「上次交互」起算。
     */
    val proactiveGapMillis: Long = ProactiveGateRules.COOLDOWN_MILLIS,

    /**
     * 1.9.4.2：上一条主动消息**送达**时刻（epoch millis，null=从未主动发过）。
     *
     * 冷却锚点 = max([lastInteractionMillis], [lastProactiveAtMillis])。1.9.4.1 只用
     * [lastInteractionMillis]（仅用户发消息时更新），她主动发过一条后锚点不动、`now-锚点`
     * 持续变大 → 之后每 2h 检查全部放行，随机间隔退化成"每 2h 一条"。纳入主动送达时刻后，
     * 每条主动消息发出即重新起算，随机间隔才真正生效。
     */
    val lastProactiveAtMillis: Long? = null
)

/**
 * 决策闸门纯规则（不调模型）。任务 3.4
 *
 * 出队/生成前的判定次序：
 *  0. **总开关**：主动消息关闭 → 一律拦截。
 *  1. **勿扰**：区间内一律拦截（对指令提醒/追问也生效）。
 *  2. 指令提醒 / 追问：仅受勿扰，其余闸门全部绕过 → 放行。
 *  3. **冷却/间隔**：距上次交互不足「本轮要求间隔」→ 拦截（1.9.4.1 起间隔由关系档随机给出，默认兜底 1h）。
 *  4. **每日上限**：今日自发消息 ≥ 上限 → 拦截（指令/追问不计入，前面已放行）。
 *  5. **回复率降频**：近 7 天回复率 < 阈值且今日已发过 → 拦截（降到一天一次）。
 *  6. **无钩子**：记忆/环境钩子都召不出 → 拦截。
 *  全部通过 → 放行。
 */
object ProactiveGateRules {

    /**
     * 每日自发消息默认上限。
     *
     * **1.9.4.1 起**：最密档是"亲近"（间隔 2~3h），16 小时清醒 ÷ 2h ≈ 8 条 = 封顶。
     * 生疏/熟悉更疏，实际条数远低于 8，所以这个上限天然不会被撞满。
     * （历史上此值 8 是按"每 2 小时一条"算的；现在含义变成"最密档封顶"，数值不变。）
     */
    const val DEFAULT_DAILY_LIMIT: Int = 8

    /**
     * 兜底冷却窗口：拿不到关系档 / 从未交互时，"距上次交互不足 1 小时"就拦。
     *
     * **注意（1.9.4.1）**：真正生效的间隔是 [ProactiveGateContext.proactiveGapMillis]（关系档随机 3~4h/2~4h/2~3h）。
     * 关系档随机间隔**可以大于** 2h 检查周期——因为它是从"上次互动"（用户说话或她上次主动送达，取较晚者）起算，只要用户不再开口也一定会等到、不会吞轮。
     * 这里保留 1h 仅作无关系数据时的安全默认；**改 [com.companion.chat.worker.ProactiveCheckWorker.PERIOD_HOURS] 仍必须 bump SCHEDULE_SCHEMA**。
     */
    const val COOLDOWN_MILLIS: Long = 3_600_000L

    fun evaluate(ctx: ProactiveGateContext, zone: ZoneId = ZoneId.systemDefault()): ProactiveDecision {
        // 0. 总开关（任务 7.1）：关掉后自发消息一律不发（指令提醒仍走送达层，不受此限）
        if (!ctx.enabled) {
            return ProactiveDecision.block(ProactiveBlockReason.MASTER_OFF)
        }
        // 1. 勿扰（作用于 AI 主动消息：自发/节日/环境）。
        //    指令/追问**不进勿扰判断**（见下面第 2 步直接 allow），它们由送达层
        //    DND_DEFERRED 分支顺延到区间结束——这是 7.2 定好的语义，不是这里拦。
        //    熬夜提醒豁免勿扰（它天然发生在 23 点后，不豁免等于功能永久失效）。
        val dndExempt = ctx.category == ProactiveMessageCategory.LATE_NIGHT_ALERT
        if (!dndExempt && ctx.dndEnabled && inQuietHours(ctx.nowMillis, ctx.dndStartMinute, ctx.dndEndMinute, zone)) {
            return ProactiveDecision.block(ProactiveBlockReason.DO_NOT_DISTURB)
        }
        // 2. 指令提醒 / 追问：仅受勿扰（到点必发，不计入上限）
        if (ctx.category == ProactiveMessageCategory.INSTRUCTION ||
            ctx.category == ProactiveMessageCategory.FOLLOWUP
        ) {
            return ProactiveDecision.allow()
        }
        // 2.5 节日 / 环境 / 熬夜：**绕过冷却与回复率**（计划 V2.1.4 验收「环境钩子绕过冷却时间」），
        //     但仍受「每日上限」约束（计划：「但受勿扰 / 一天上限约束」）。
        if (ctx.category == ProactiveMessageCategory.SPECIAL_DATE ||
            ctx.category == ProactiveMessageCategory.ENVIRONMENT ||
            ctx.category == ProactiveMessageCategory.LATE_NIGHT_ALERT
        ) {
            return if (ctx.spontaneousSentToday >= ctx.dailyLimit) {
                ProactiveDecision.block(ProactiveBlockReason.DAILY_LIMIT)
            } else {
                ProactiveDecision.allow()
            }
        }
        // 3. 冷却（1.9.4.1：间隔按关系档；1.9.4.2：锚点=用户说话与她主动送达中较晚者）
        val anchor = cooldownAnchorMillis(ctx)
        if (anchor != null && ctx.nowMillis - anchor < ctx.proactiveGapMillis) {
            return ProactiveDecision.block(ProactiveBlockReason.COOLDOWN)
        }
        // 4. 每日上限
        if (ctx.spontaneousSentToday >= ctx.dailyLimit) {
            return ProactiveDecision.block(ProactiveBlockReason.DAILY_LIMIT)
        }
        // 5. 回复率降频（低回复 → 一天一次）
        val rate = ctx.replyRate7d
        if (rate != null && rate < ctx.replyRateThreshold && ctx.sentAnySpontaneousToday) {
            return ProactiveDecision.block(ProactiveBlockReason.LOW_REPLY_RATE)
        }
        // 6. 无钩子
        if (!ctx.hookAvailable) {
            return ProactiveDecision.block(ProactiveBlockReason.NO_HOOK)
        }
        return ProactiveDecision.allow()
    }

    /**
     * 冷却锚点：取「用户上次说话」与「上一条主动消息送达」里较晚的一个。
     * 两者都为 null（全新用户/从未主动发过）→ null（跳过冷却）。
     */
    fun cooldownAnchorMillis(ctx: ProactiveGateContext): Long? {
        val user = ctx.lastInteractionMillis
        val proactive = ctx.lastProactiveAtMillis
        return when {
            user == null -> proactive
            proactive == null -> user
            else -> maxOf(user, proactive)
        }
    }

    /** 当前时刻是否落在勿扰区间（支持跨午夜，如 23:00–07:00）。end 视为开区间（到点即恢复）。 */
    fun inQuietHours(nowMillis: Long, startMinute: Int, endMinute: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (startMinute == endMinute) return false // 空区间
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
        val nowMinute = t.hour * 60 + t.minute
        return if (startMinute < endMinute) {
            nowMinute in startMinute until endMinute
        } else {
            // 跨午夜：start..1439 或 0..end
            nowMinute >= startMinute || nowMinute < endMinute
        }
    }
}
