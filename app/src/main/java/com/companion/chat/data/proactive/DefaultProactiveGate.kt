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
        val ctx = ProactiveGateContext(
            nowMillis = now(),
            // 类别由消息类型决定（BUG-2）：环境/节日/追问不再被当闲聊吃冷却和上限
            category = category,
            lastInteractionMillis = lastInteractionProvider(),
            spontaneousSentToday = sentToday,
            dailyLimit = cfg.dailyLimit,
            replyRate7d = replyRateProvider(),
            replyRateThreshold = cfg.replyRateThreshold,
            sentAnySpontaneousToday = sentToday > 0,
            hookAvailable = true, // 无钩子由 recall 后 NO_HOOK 处理（3.2 runner）
            dndEnabled = cfg.dndEnabled,
            dndStartMinute = cfg.dndStartMinute,
            dndEndMinute = cfg.dndEndMinute,
            enabled = cfg.enabled
        )
        val decision = ProactiveGateRules.evaluate(ctx, zone)
        if (!decision.allowed) {
            // 用 W 级：真机上 D 级日志常被 ROM 吞掉，排查「为什么不发」全靠这行
            val last = ctx.lastInteractionMillis
            val sinceLastMin = if (last != null) (ctx.nowMillis - last) / 60_000 else null
            log(
                "Blocked=${decision.reason} sentToday=$sentToday/${cfg.dailyLimit} " +
                    "sinceLastInteraction=${sinceLastMin}min category=${ctx.category}"
            )
        }
        return decision.allowed
    }
}
