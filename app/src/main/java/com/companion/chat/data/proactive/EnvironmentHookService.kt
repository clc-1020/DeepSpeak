package com.companion.chat.data.proactive

import com.companion.chat.data.environment.LocationCache
import com.companion.chat.data.environment.WeatherBundle
import com.companion.chat.data.environment.WeatherCache
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.PendingMessageType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.domain.environment.EnvironmentHook
import com.companion.chat.domain.environment.EnvironmentHooks
import com.companion.chat.domain.environment.EnvironmentRuleConfig
import com.companion.chat.domain.environment.EnvironmentRuleEngine
import com.companion.chat.domain.proactive.PendingMessageContent
import java.time.Instant
import java.time.ZoneId

/**
 * 环境钩子投递 + 同日去重（V2.1.4 / V2.1.5）。
 *
 * 职责（计划书 §4 V2.1.4）：
 * 1. [recall]：`evaluate()` → **逐条**找第一条「今天没发过、队列里也没有」的钩子
 * 2. [enqueue]：`LateNight → LATE_NIGHT`，其余 → `ENVIRONMENT`；入队即达（`triggerTime = now`）
 *
 * **三条与「每小时检查」直接相关的约束**（V2.1.5 加）：
 * - [recall] 先看**环境总开关**：关掉就返回 null，不做任何天气/定位开销
 * - [recall] **逐条**判断而不是只看 `first()`：熬夜发过之后，同一夜还剩雨/降温/高温该不该发
 *   由各自的去重键决定，不能被第一条挡住整夜
 * - [recall] 还要看**队列里是否已有同 content 的待发消息**：去重键只在送达成功后写，
 *   若连续几小时命中同一钩子却未送达，每轮都会投一条新的 → 队列堆副本
 *
 * @param appStateDao 去重键存储（app_state 表）
 * @param enabled 环境总开关；关闭时 [recall] 直接返回 null（默认恒开，测试可注入）
 * @param zone 用户时区
 * @param nowMillis 时钟注入（测试钉边界）
 * @param cfg 环境规则阈值
 */
class EnvironmentHookService(
    private val ruleEngine: EnvironmentRuleEngine,
    private val appStateDao: AppStateDao,
    private val weatherCache: WeatherCache,
    private val locationCache: LocationCache,
    private val queue: PendingMessageQueue,
    private val enabled: () -> Boolean = { true },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val cfg: EnvironmentRuleConfig = EnvironmentRuleConfig(),
) {

    /**
     * 取该发的一条环境钩子。
     *
     * 返回 null 的四种情形：
     * 1. 环境总开关关着
     * 2. 规则引擎没算出任何钩子（既非熬夜时段、无天气异常、也非饭点）
     * 3. 所有钩子今天都已发过（去重键存在）
     * 4. 所有钩子都已在队列里等着（防副本）
     *
     * **只读不写**：去重键由送达回调写。这里查到了也不动状态——「投递」和「送达」之间
     * 隔着闸门，若这里先记账，被拦的那轮就永久丢了这个钩子。
     */
    suspend fun recall(): EnvironmentHook? {
        if (!enabled()) return null

        val bundle = weatherBundle()
        val now = nowMillis()
        val nowLdt = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        val hooks = ruleEngine.evaluate(
            now = nowLdt,
            today = bundle.today,
            yesterday = bundle.yesterday,
            cfg = cfg,
        )

        for (hook in hooks) {
            if (appStateDao.get(dedupKey(hook)) != null) continue // 今天已发过
            if (queue.hasPendingWithContent(contentOf(hook))) continue // 已在队列等着
            return hook
        }
        return null
    }

    /** 投队列。`LateNight → LATE_NIGHT`，其余 → `ENVIRONMENT`；入队即达。 */
    suspend fun enqueue(hook: EnvironmentHook): Long = queue.enqueue(
        type = typeOf(hook),
        content = PendingMessageContent.Environment(hook.stableId),
        triggerTime = nowMillis(),
    )

    private fun typeOf(hook: EnvironmentHook): PendingMessageType =
        if (hook is EnvironmentHook.LateNight) PendingMessageType.LATE_NIGHT
        else PendingMessageType.ENVIRONMENT

    /** 与 [enqueue] 写入的 content 完全一致——查重必须同源，否则永远查不到。 */
    private fun contentOf(hook: EnvironmentHook): String =
        PendingMessageContent.Environment(hook.stableId).encode()

    /**
     * 取天气 bundle。
     *
     * 2026-11-22：**先只读缓存**。缓存新鲜时 today/yesterday 都在里面，完全不需要坐标，
     * 也就完全不碰定位——而定位在后台必然拿不到（Android 10+ 只给了「仅使用时」权限），
     * 原来每小时白调一次。只有缓存缺失/过期才去取坐标并联网。
     *
     * 「今天」必须走**注入时钟**，不能用 `LocalDate.now()`（2026-11-21 00:0x 踩到）：
     * 去重键 `dateKeyOf(nowMillis(), …)` 用的是注入时钟，两者一旦不一致就会出现
     * 「天气按 21 号取、去重键按 20 号算」的错位；测试里更直接 —— 跨过午夜后
     * 注入 11-20 10:00 却去查 11-21 的天气，假数据里没有那天 → 钩子永远为空。
     */
    private suspend fun weatherBundle(): WeatherBundle {
        val today = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()

        val cached = weatherCache.peekBundle(today)
        if (cached.today != null) return cached

        // 缓存不可用 → 取坐标再联网；定位失败（无坐标）时 load 返回空 bundle，
        // 规则引擎就只返回时间类钩子
        return weatherCache.load(locationCache.current().point, today)
    }

    private fun dedupKey(hook: EnvironmentHook): String =
        "env_sent_${hook.stableId}_${EnvironmentHooks.dateKeyOf(hook.stableId, nowMillis(), zone)}"
}