package com.companion.chat.domain.proactive

/**
 * 主动消息「决策闸门」抽象。任务 3.2（接口）/ 3.4（真实规则实现）
 *
 * 每轮定时检查先过闸门：勿扰 / 冷却 / 一天上限 / 回复率降频 / 无钩子 → 决定本轮是否允许投递。
 * 3.2 只依赖 3.1，先用 [AllowAllGate] 占位；3.4 会换成 `DefaultProactiveGate` 接入真实规则。
 *
 * **必须按类别评估**（[ProactiveMessageCategory]）：闲聊吃全部闸门，环境/节日/追问/指令
 * 只受「总开关 + 勿扰」。早前[allow] 无参版本把一切当闲聊，导致环境钩子被冷却拦、节日祝福
 * 被每日上限挤掉（BUG-2）。
 */
interface ProactiveGate {
    /** 按消息类别评估本轮是否允许产生/送达。true=放行，false=拦截。 */
    suspend fun allow(category: ProactiveMessageCategory): Boolean

    /** 兼容写法：无参等价于按「自发闲聊」评估。 */
    suspend fun allow(): Boolean = allow(ProactiveMessageCategory.SPONTANEOUS)
}

/** 全放行占位闸门（测试/未来用，3.4 起不再作为默认）。 */
class AllowAllGate : ProactiveGate {
    override suspend fun allow(category: ProactiveMessageCategory): Boolean = true
}

/**
 * 全拦截闸门。任务 3.2 审查改进⑤：主动消息框架在 3.4（真实闸门）/3.5（送达）/7.1（总开关）
 * 完成前**默认关闭**，避免真机每 2h 收到无意义主动消息。3.4 完成后由真实闸门 + 用户总开关接管。
 */
class NeverAllowGate : ProactiveGate {
    override suspend fun allow(category: ProactiveMessageCategory): Boolean = false
}