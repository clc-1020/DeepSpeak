package com.companion.chat.data.proactive

import com.companion.chat.domain.proactive.ProactiveDebugFlags
import com.companion.chat.domain.proactive.ProactiveGate
import com.companion.chat.domain.proactive.ProactiveGateContext
import com.companion.chat.domain.proactive.ProactiveGateRules
import com.companion.chat.domain.proactive.ProactiveMessageCategory
import java.time.ZoneId

/**
 * 决策闸门可配置项（默认值即计划要求）。任务 3.4
 * 后续 7.1 设置页可把这里替换为用户可配值（勿扰区间、每日上限、回复率阈值）。
 */
data class ProactiveGateConfig(
    /** 主动消息总开关（任务 7.1）：false 时闸门直接拦截（reason = MASTER_OFF）。 */
    val enabled: Boolean = true,
    val dailyLimit: Int = ProactiveGateRules.DEFAULT_DAILY_LIMIT,
    val replyRateThreshold: Double = 0.2,
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 23 * 60, // 23:00
    val dndEndMinute: Int = 7 * 60     // 07:00
)

/**
 * 默认决策闸门实现（自发消息路径）。任务 3.4
 *
 * 从各 provider 收集运行态，交给纯规则 [ProactiveGateRules] 判定。
 * 「无钩子」在自发性路径由 3.2 的 recall 返回 null（NO_HOOK）处理，这里 [ProactiveGateContext.hookAvailable]
 * 传 true；若将来在出队时需前置判断，可注入 hookAvailableProvider。
 *
 * 数据来源（生产接线见 CompanionApp）：
 *  - lastInteractionProvider：app_state[KEY_LAST_INTERACTION]
 *  - spontaneousSentTodayProvider：今日已发自发消息数（3.5 送达成功后累加，当前默认 0 占位）
 *  - replyRateProvider：近 7 天回复率（5.x 回复统计后提供，当前默认 null）
 */
class DefaultProactiveGate(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val configProvider: () -> ProactiveGateConfig = { ProactiveGateConfig() },
    private val lastInteractionProvider: suspend () -> Long? = { null },
    private val spontaneousSentTodayProvider: suspend () -> Int = { 0 },
    private val replyRateProvider: suspend () -> Double? = { null },
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * 1.9.4.1：当前关系档（生疏/熟悉/亲近）。null = 用默认 1h 冷却（旧行为，测试零改动）。
     * 生产传 `moodService.currentRelationshipStage()`，据此把冷却换成"关系档随机间隔"。
     */
    private val relationshipStageProvider: suspend () -> String? = { null },
    /**
     * 1.9.4.2：上一条主动消息送达时刻（app_state[KEY_PROACTIVE_LAST_DELIVERED]）。
     * 与 [lastInteractionProvider] 一起构成冷却锚点（取较晚者），修"沉默时随机间隔失效"。
     */
    private val lastProactiveAtProvider: suspend () -> Long? = { null },
    /** 临时调试绕过（[ProactiveDebugFlags]）：生产由 CompanionApp 装配时传入，默认关闭，单测不受影响。 */
    private val debugBypass: Boolean = false,
    private val log: (String) -> Unit = { android.util.Log.w("ProactiveGate", it) }
) : ProactiveGate {

    override suspend fun allow(category: ProactiveMessageCategory): Boolean {
        // 临时调试：绕过冷却/上限/回复率，否则 1 分钟一轮也会被拦，联调看不到消息
        if (debugBypass) {
            log("调试模式：绕过闸门 category=$category")
            return true
        }
        val cfg = configProvider()
        val sentToday = spontaneousSentTodayProvider()
        val lastUser = lastInteractionProvider()
        val lastProactive = lastProactiveAtProvider()
        // 1.9.4.2：锚点取「用户上次说话」与「她上次主动送达」较晚者——
        // 否则她主动发过一条后锚点不动，随机间隔只在用户每说一句话后生效一次、之后退化成每2h一条。
        val anchor = when {
            lastUser == null -> lastProactive
            lastProactive == null -> lastUser
            else -> maxOf(lastUser, lastProactive)
        }
        // 关系档随机间隔（种子=锚点，同一段互动内稳定不跳）；null 档或从无锚点 → 旧 1h。
        val gap = runCatching {
            val stage = relationshipStageProvider()
            if (stage != null && anchor != null) {
                com.companion.chat.domain.emotion.RelationshipStage.proactiveGapMillis(stage, anchor)
            } else null
        }.getOrNull() ?: ProactiveGateRules.COOLDOWN_MILLIS
        val ctx = ProactiveGateContext(
            nowMillis = now(),
            // 类别由消息类型决定（BUG-2）：环境/节日/追问不再被当闲聊吃冷却和上限
            category = category,
            lastInteractionMillis = lastUser,
            lastProactiveAtMillis = lastProactive,
            spontaneousSentToday = sentToday,
            dailyLimit = cfg.dailyLimit,
            replyRate7d = replyRateProvider(),
            replyRateThreshold = cfg.replyRateThreshold,
            sentAnySpontaneousToday = sentToday > 0,
            hookAvailable = true, // 无钩子由 recall 后 NO_HOOK 处理（3.2 runner）
            dndEnabled = cfg.dndEnabled,
            dndStartMinute = cfg.dndStartMinute,
            dndEndMinute = cfg.dndEndMinute,
            enabled = cfg.enabled,
            proactiveGapMillis = gap
        )
        val decision = ProactiveGateRules.evaluate(ctx, zone)
        if (!decision.allowed) {
            // 用 W 级：真机上 D 级日志常被 ROM 吞掉，排查「为什么不发」全靠这行
            val sinceLastMin = if (anchor != null) (ctx.nowMillis - anchor) / 60_000 else null
            log(
                "Blocked=${decision.reason} sentToday=$sentToday/${cfg.dailyLimit} " +
                    "sinceLastInteraction=${sinceLastMin}min category=${ctx.category}"
            )
        }
        return decision.allowed
    }
}
