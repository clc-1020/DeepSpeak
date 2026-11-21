package com.companion.chat.data.mood

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.domain.emotion.Emotion
import com.companion.chat.domain.emotion.EmotionCue
import com.companion.chat.domain.emotion.EmotionDecay
import com.companion.chat.domain.emotion.EmotionMerge
import com.companion.chat.domain.emotion.EmotionPhrase
import com.companion.chat.domain.emotion.EmotionState
import com.companion.chat.domain.emotion.EmotionMemory
import com.companion.chat.domain.emotion.MissingEmotion
import com.companion.chat.domain.emotion.RelationshipStage
import com.companion.chat.domain.emotion.Warmth
import com.companion.chat.domain.emotion.HersState
import com.companion.chat.domain.emotion.HisState
import com.companion.chat.domain.proactive.EmotionProactivePolicy
import com.companion.chat.domain.mood.Mood
import com.companion.chat.domain.mood.MoodCounter
import com.companion.chat.domain.mood.MoodPromptBuilder
import com.companion.chat.domain.mood.MoodUpdateParser
import com.companion.chat.domain.reminder.FollowupPort
import com.companion.chat.domain.reminder.FollowupStatus
import java.time.Instant
import java.time.ZoneId

/**
 * mood + 情绪状态更新（任务 5.1 / V2.2.2 / **2026-11-21 情绪系统 P1**）。
 *
 * 每完成一轮对话 [onTurnCompleted] 调用一次：
 *  1. 轮数计数 +1（存 `app_state[mood_turn_count]`）；
 *  2. **刺激闸门** [EmotionCue]（零成本）：只有「这轮明显有情绪信号 / 有活跃追问链 / 到 5 轮」才调模型；
 *  3. 一次调用同时拿回：他的 mood（3 档）+ **她的情绪变化项**（1~3 个）；
 *  4. 她的情绪按 `max(旧值衰减后, 模型报的值)` 合并，再过三道护栏（单值 0.9 / 负面总和 0.9 / 负面须有 trigger）；
 *  5. 有活跃链时按 `followups[id].status` 逐条分派 complete/abandon/advance；
 *  6. **失败/JSON 非法 → 保持原状态**（不写库，绝不影响主对话）；有链则 advance（否则链卡死）。
 *
 * **衰减不在后台跑**：一切以 `emotion_state.updated_at` 为基准，读取时现算（[currentEmotionState]）。
 *
 * @param callJson 独立模型调用，接收消息列表（system + user），生产接 `ChatApiClient.callJson`。
 * @param followup 追问端口；生产必须传真实实现，留 null 会让追问链永不启动。
 * @param now 注入时钟（**不要用 LocalDate.now()** —— 本项目吃过「跨午夜两套时钟」的亏）。
 */
class MoodService(
    private val appStateDao: AppStateDao,
    private val callJson: suspend (ApiConfig, List<MessageEntity>) -> Result<String>,
    private val everyTurns: Int = MoodCounter.UPDATE_EVERY_TURNS,
    private val followup: FollowupPort? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * P3（2026-11-21）：关系阶段所需的全库统计（总消息数 + 认识第几天）。
     * 生产传 `MessageDao` 统计；null = 一律按「生疏」处理（最保守，绝不撒娇）。
     */
    private val relationshipProvider: (suspend () -> RelationshipStage.Stats?)? = null,
    /**
     * P3：情绪事件写回长期记忆（只在亲近阶段、强度 ≥0.6）。null = 不写（旧装配/测试零改动）。
     */
    private val memoryLongDao: MemoryLongDao? = null,
) {

    /** 当前 mood（默认 normal）。 */
    suspend fun currentMood(): String =
        appStateDao.get(AppStateEntity.KEY_MOOD)?.takeIf { it.isNotBlank() } ?: Mood.NORMAL

    /** 最近一次 mood 判定的原因（默认空串）。便于验证与日志。 */
    suspend fun currentReason(): String =
        appStateDao.get(AppStateEntity.KEY_MOOD_REASON).orEmpty()

    /** 已完成对话轮数（持久化）。 */
    suspend fun turnCount(): Int =
        appStateDao.get(AppStateEntity.KEY_MOOD_TURN_COUNT)?.toIntOrNull() ?: 0

    // ---------- 情绪状态（P1） ----------

    /**
     * 读取情绪状态，并**按时间衰减**到"此刻"。
     *
     * 返回的 `updatedAt` 已被改写成 `now`（视作"现值"），但**不落库** ——
     * 衰减是纯计算，落库只会白写一轮。
     */
    suspend fun currentEmotionState(): EmotionState {
        val raw = appStateDao.get(AppStateEntity.KEY_EMOTION_STATE)
        val st = EmotionState.fromJson(raw) ?: EmotionState()
        val nowMs = now()
        if (st.updatedAt <= 0L) return st.copy(updatedAt = nowMs)
        val minutes = (nowMs - st.updatedAt).toDouble() / 60_000.0
        val decayed = EmotionDecay.apply(st.hers.emotions, minutes)
        return st.copy(hers = st.hers.copy(emotions = decayed), updatedAt = nowMs)
    }

    /**
     * 渲染本轮注入块（`【我这会儿】…【他这会儿】…`）；没有可说的内容返回 null。
     *
     * 「示弱配额」在这里消耗：委屈 ≥0.35 时问一次 [claimWeaknessForToday]。
     * 注意配额按**注入**而非"模型真的用了"计算 —— 简单、可预测，代价是偶尔白用一次。
     *
     * P3（2026-11-21）：**撒娇只在亲近阶段**（[RelationshipStage.allowsAffection]）。
     * 生疏/熟悉一律走「语气软一点」的 P1 文案 —— 撒娇是关系奖励，不是默认行为。
     */
    suspend fun renderEmotionBlock(): String? {
        val st = currentEmotionState()
        val hurt = st.hers.emotions[Emotion.HURT] ?: 0.0
        val warmth = currentWarmth()
        // P3.1：撒娇要**两个条件**——关系亲近（资格）且热络度够（此刻够熟）
        val affectionAllowed = RelationshipStage.allowsAffection(currentRelationshipStage()) &&
            warmth >= Warmth.AFFECTION_MIN &&
            hurt >= EmotionPhrase.TWO_MIN &&
            claimWeaknessForToday()

        val base = EmotionPhrase.render(
            hers = st.hers.emotions,
            trigger = st.hers.trigger,
            hisMood = st.his.mood,
            hisIntensity = st.his.intensity,
            allowAffection = affectionAllowed,
        )
        // P3.1：偏冷时补一行「别撒娇、先随便聊聊」；热络时一个字都不加
        val warmthLine = Warmth.line(warmth)
        return listOfNotNull(base, warmthLine).joinToString("\n").ifBlank { null }
    }

    /** 当前关系阶段（生疏/熟悉/亲近）。拿不到统计时按「生疏」处理。 */
    suspend fun currentRelationshipStage(): String {
        val stats = runCatching { relationshipProvider?.invoke() }.getOrNull()
            ?: return RelationshipStage.STRANGER
        return RelationshipStage.of(stats)
    }

    /** 设置页展示用：`亲近（已聊 213 条，第 5 天）`。 */
    suspend fun relationshipSummary(): String {
        val stats = runCatching { relationshipProvider?.invoke() }.getOrNull()
            ?: return "${RelationshipStage.label(RelationshipStage.STRANGER)}（暂无聊天记录）"
        val stage = RelationshipStage.of(stats)
        return "${RelationshipStage.label(stage)}（已聊 ${stats.messageCount} 条，第 ${stats.daysKnown} 天）"
    }

    // ---------- 热络度（P3.1） ----------

    /**
     * 当前热络度 0~1（已按 14 天半衰算到"此刻"）。
     *
     * 没存过 → 默认 0.5（当作"还行"）：老用户升级上来不该一睁眼就被判成"生分"。
     * 注意**不在这里落库**：衰减是纯计算，落库只会白写。
     */
    suspend fun currentWarmth(): Double {
        val stored = appStateDao.get(AppStateEntity.KEY_WARMTH)?.toDoubleOrNull()
            ?: return Warmth.valueAt(DEFAULT_WARMTH, 0.0)
        val at = appStateDao.get(AppStateEntity.KEY_WARMTH_AT)?.toLongOrNull() ?: 0L
        val minutes = if (at <= 0L) 0.0 else (now() - at).toDouble() / 60_000.0
        return Warmth.valueAt(stored, minutes)
    }

    /** 聊完一轮：热络度回暖（+0.12，封顶 1.0），并记录更新时间。 */
    suspend fun touchWarmth() {
        val updated = Warmth.afterTurn(currentWarmth())
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_WARMTH, updated.toString()))
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_WARMTH_AT, now().toString()))
    }

    /** 设置页展示用：`0.35（有点生分，两周没怎么聊了）`。 */
    suspend fun warmthSummary(): String {
        val w = currentWarmth()
        val hint = if (w < Warmth.COLD) "，久没聊会有点生分" else ""
        return "${EmotionPhrase.fmt(w)}（${Warmth.label(w)}$hint）"
    }

    /** 今天是否**还能**示弱一次；第一次调用返回 true 并记下今天的日期。 */
    suspend fun claimWeaknessForToday(): Boolean {
        val today = todayKey()
        if (appStateDao.get(AppStateEntity.KEY_WEAKNESS_DATE) == today) return false
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_WEAKNESS_DATE, today))
        return true
    }

    private fun todayKey(): String =
        Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().toString()

    /**
     * P3：把「有温度」的情绪事件写进长期记忆（`category = relationship`）。
     *
     * 三道闸：只在亲近阶段 / 强度 ≥0.6 / 有 trigger；同一天同一件事只写一条；
     * 最多保留 [EmotionMemory.MAX_ENTRIES] 条（长期记忆是每轮全量注入的，攒多了会挤爆上下文）。
     */
    private suspend fun maybeWriteEmotionMemory(emotions: Map<String, Double>, trigger: String) {
        val dao = memoryLongDao ?: return
        val picked = EmotionMemory.pick(emotions, currentRelationshipStage(), trigger) ?: return
        val today = todayKey()
        val key = trigger.take(8)
        runCatching {
            val existing = dao.getByCategory(CATEGORY_EMOTION_MEMORY)
            if (existing.any { it.content.contains(today) && it.content.contains(key) }) return

            dao.insert(
                MemoryLongEntity(
                    content = EmotionMemory.format(picked.first, trigger, today),
                    category = CATEGORY_EMOTION_MEMORY,
                    createTime = now(),
                )
            )
            val all = dao.getByCategory(CATEGORY_EMOTION_MEMORY)
            if (all.size > EmotionMemory.MAX_ENTRIES) {
                all.sortedBy { it.createTime }
                    .take(all.size - EmotionMemory.MAX_ENTRIES)
                    .forEach { dao.delete(it) }
            }
        }
    }

    private suspend fun saveEmotion(st: EmotionState) {
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_EMOTION_STATE, st.toJson()))
        // P2：闸门的 configProvider 是**非 suspend**，读不了 app_state —— 这里把「上限增量」刷进缓存
        EmotionProactivePolicy.updateCache(st.hers.emotions)
    }

    /**
     * P2：「想你」只在**主动消息**路径积累（用户拍板 D2，聊天时不许黏人）。
     *
     * 依据 `app_state[last_interaction]` 算「他多久没说话」，按 [MissingEmotion] 的曲线取目标值，
     * 只有明显高于现值才写回（避免每次主动消息都无意义地写库/重置 since）。
     *
     * @return 合并后的情绪 map（**已含刚积累的想你**），供主动消息生成用
     */
    suspend fun emotionsWithAwayBoost(): Map<String, Double> {
        val st = currentEmotionState()
        val last = appStateDao.get(AppStateEntity.KEY_LAST_INTERACTION)?.toLongOrNull()
            ?: return st.hers.emotions
        val hours = (now() - last).toDouble() / 3_600_000.0
        // P3：越亲近越早开始想他（生疏 6h / 熟悉 3h / 亲近 1h）
        val floor = RelationshipStage.missingFloorHours(currentRelationshipStage())
        val target = MissingEmotion.target(hours, floor) ?: return st.hers.emotions

        val current = st.hers.emotions[Emotion.MISSING] ?: 0.0
        if (target <= current + 0.05) return st.hers.emotions

        val merged = EmotionMerge.merge(
            oldDecayed = st.hers.emotions,
            reported = mapOf(Emotion.MISSING to target),
            hasTrigger = true, // 想你属于正面，本身不需要 trigger；这里只为让 merge 不特殊处理
        )
        val hers = st.hers.copy(
            emotions = merged,
            trigger = if (st.hers.trigger.isBlank()) MissingEmotion.TRIGGER else st.hers.trigger,
            since = if (st.hers.since <= 0L) now() else st.hers.since,
        )
        saveEmotion(EmotionState(his = st.his, hers = hers, updatedAt = now()))
        return merged
    }

    // ---------- 每轮更新 ----------

    /**
     * 记录一轮对话完成；闸门通过时更新 mood 与情绪状态。
     * @return 是否**触发了更新尝试**（便于测试；失败也算尝试过，原状态不变）。
     */
    suspend fun onTurnCompleted(
        config: ApiConfig,
        recent: List<MessageEntity>
    ): Boolean {
        val count = turnCount() + 1
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_MOOD_TURN_COUNT, count.toString()))
        // P3.1：聊天本身就是"经营关系"——每轮回暖一点（与是否调模型无关）
        touchWarmth()

        val actives = followup?.activeAll().orEmpty() // 只查一次，后面复用
        val state = currentEmotionState()

        // ① 刺激闸门（零成本）：明显有情绪信号才值得调模型
        val users = recent.filter { it.role == MessageRole.USER }
        val lastUser = users.lastOrNull()?.content.orEmpty()
        val prevUserLen = users.dropLast(1).lastOrNull()?.content?.length ?: 0
        val stimulus = EmotionCue.isStimulus(lastUser, prevUserLen)

        val shouldCall = stimulus || actives.isNotEmpty() || MoodCounter.shouldUpdate(count, everyTurns)
        if (!shouldCall) return false

        // ② 一次调用同时拿 mood + 情绪变化
        val messages = listOf(
            MessageEntity(
                conversationId = 0L,
                role = MessageRole.SYSTEM,
                content = MoodPromptBuilder.system(
                    withFollowup = actives.isNotEmpty(),
                    withEmotion = true
                ),
                timestamp = now()
            ),
            MessageEntity(
                conversationId = 0L,
                role = MessageRole.USER,
                content = MoodPromptBuilder.build(recent, currentMood(), actives),
                timestamp = now()
            )
        )

        val raw = callJson(config, messages).getOrNull() ?: return true // 调用失败：保持原状态、不动追问

        val update = MoodUpdateParser.parse(raw) ?: run {
            actives.forEach { followup?.advance(it.reminderId) }
            return true
        }

        appStateDao.put(AppStateEntity(AppStateEntity.KEY_MOOD, update.mood))
        appStateDao.put(AppStateEntity(AppStateEntity.KEY_MOOD_REASON, update.reason))
        saveEmotion(mergeEmotion(state, update.mood, update.emotions, update.emotionTrigger))
        // P3：亲近阶段的"有温度"情绪写回长期记忆（她以后能提「那天你夸我我很开心」）
        maybeWriteEmotionMemory(update.emotions, update.emotionTrigger)

        // ③ 多链逐条分派（模型漏 id → 该条 advance，不重排就是永久卡死）
        actives.forEach { a ->
            val status = update.followupStatuses[a.reminderId]
                ?: if (actives.size == 1) update.followupStatus else null
            when (status) {
                FollowupStatus.COMPLETED -> followup?.complete(a.reminderId)
                FollowupStatus.ABANDONED -> followup?.abandon(a.reminderId)
                FollowupStatus.CONTINUE, null -> followup?.advance(a.reminderId)
            }
        }
        return true
    }

    /** 合并他的 mood 与她的情绪；全部低于存活线就回落到"平静"（空 map）。 */
    private fun mergeEmotion(
        old: EmotionState,
        newMood: String,
        reported: Map<String, Double>,
        trigger: String,
    ): EmotionState {
        val nowMs = now()
        val hasTrigger = trigger.isNotBlank()

        val merged = EmotionMerge.merge(old.hers.emotions, reported, hasTrigger)
        val changed = merged != old.hers.emotions

        val hers = HersState(
            emotions = merged,
            trigger = when {
                merged.isEmpty() -> ""
                hasTrigger -> EmotionMerge.trimTrigger(trigger)
                else -> old.hers.trigger
            },
            since = when {
                merged.isEmpty() -> 0L
                changed || old.hers.since <= 0L -> nowMs
                else -> old.hers.since
            }
        )

        val his = HisState(
            mood = newMood,
            intensity = intensityOf(newMood),
            since = if (newMood != old.his.mood) nowMs else old.his.since
        )

        return EmotionState(his = his, hers = hers, updatedAt = nowMs)
    }

    /**
     * 他的情绪强度：模型不单独给数字，先按档位近似。
     *
     * 简化理由：3 档本身就没有"程度"语义，让模型再编一个数字反而是假精度。
     * P2 若需要区分"有点烦"和"很烦"，再在提示词里加 `mood_intensity`。
     */
    private fun intensityOf(mood: String): Double = when (mood.trim().lowercase()) {
        Mood.HAPPY, Mood.LOW -> 0.7
        else -> 0.0
    }

    private companion object {
        /** 情绪事件在 memory_long 里的分类，便于只清理这一类、不误伤事实/偏好。 */
        const val CATEGORY_EMOTION_MEMORY = "relationship"

        /** 热络度默认值：没存过当作"还行"，避免老用户升级即被判"生分"。 */
        const val DEFAULT_WARMTH = 0.5
    }
}