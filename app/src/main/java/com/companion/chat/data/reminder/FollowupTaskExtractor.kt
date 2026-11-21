package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.alarm.ReminderParser
import com.companion.chat.domain.memory.TimeAnchorConverter
import com.companion.chat.domain.reminder.FollowupExtraction
import com.companion.chat.domain.reminder.FollowupExtractionParser
import com.companion.chat.domain.reminder.FollowupIntent
import com.companion.chat.domain.reminder.FollowupPrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 追问任务创建（V2.2.3）：逻辑线交付，UI 线在 ChatViewModel.handleSend 里调用。
 *
 * 执行顺序（不可调换）：
 * ① 意图闸门（零成本，不命中连模型都不调）
 * ② 模型调用（锁外）→ ③ 解析（锁外）→ ④ 时间计算（锁外，纯计算）
 * ⑤ 只把「查 + 建 + 排钟」放进实例 Mutex：查与建之间是 suspend 缝隙，
 *    双击会让两条协程都查到空、各建一条链（双链每天各催一遍）。
 *
 * **去重键 = 任务文本**（2026-11-20 收口）：同一任务最多一条链，不同任务可以并存。
 * 原来是「全局最多一条」，导致「已有链时新任务被静默丢弃、而模型嘴上已经答应了」。
 *
 * 返回：success(null)=没建（非监督类/已有链）；success(id)=建了；failure(e)=出错（不建行不排钟）。
 *
 * @param callJson 仅为 JVM 单测提供的注入点（计数/失败注入）；生产走 apiClient.callJson，
 *                 行为完全一致。末位可选参数，不影响 CompanionApp 的具名实参装配。
 */
class FollowupTaskExtractor(
    private val apiClient: ChatApiClient,
    private val dao: ReminderDao,
    private val alarms: FollowupAlarms,
    private val now: () -> Long = System::currentTimeMillis,
    private val referenceDate: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val callJson: suspend (ApiConfig, List<ChatMessageDto>, String) -> Result<String> =
        { config, messages, hint -> apiClient.callJson(config, messages, schemaHint = hint) }
) {
    /** 实例级（不是 object 单例）：测试之间互不干扰。 */
    private val mutex = Mutex()

    suspend fun extractAndSchedule(config: ApiConfig, recent: List<MessageEntity>): Result<Long?> {
        // ① 零成本闸门：只看最后一条用户消息
        val lastUser = recent.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        if (!FollowupIntent.matches(lastUser)) return Result.success(null)

        val transcript = FollowupPrompt.build(recent)
        if (transcript.isBlank()) return Result.success(null)

        // ② 模型调用（锁外）
        //
        // **这里曾经有一道「已有链就零成本返回」的预检，2026-11-20 移除**：
        // 它挡的是「全局任意一条链」，于是用户已有「背单词」链时再说「监督我喝水」，
        // 新请求被**静默**丢弃（无日志、无提示），而模型已经在对话里答应「一分钟后开始」。
        // 真机表现为「说好了却一条提醒都不来」。要在预检阶段判断「是不是同一条任务」就得先调模型，
        // 所以预检消失，正确性交给 ⑤ 锁内的 `pendingFollowupByTask`（同一任务才拦）。
        val raw = callJson(
            config,
            listOf(ChatMessageDto("user", transcript)),
            FollowupPrompt.SYSTEM
        ).getOrElse { return Result.failure(it) }

        // ③ 解析（锁外）
        val ex = FollowupExtractionParser.parse(raw) ?: return Result.success(null)

        // ④ 时间（锁外，纯计算）
        val triggerAt = resolveTriggerTime(ex)

        // ⑤ 只包「查 + 建」（+ 排钟，避免建了行却没闹钟）
        return mutex.withLock {
            // 去重键 = 任务文本：同一任务不重复建链（防双链各催一遍）；
            // 不同任务允许并存（背单词 + 喝水互不影响）。
            if (dao.pendingFollowupByTask(ex.task) != null) return@withLock Result.success(null)

            val entity = ReminderEntity(
                type = ReminderType.FOLLOWUP,
                taskDesc = ex.task,
                triggerTime = triggerAt,
                state = ReminderState.PENDING,
                followupInterval = ex.intervalMinutes,
                stopCondition = ex.stopCondition,
                priority = 4,
                attemptCount = 0,
                maxAttempts = 5
            )
            // insert 抛了就不排钟，避免闹钟指向不存在的行
            val id = runCatching { dao.insert(entity) }
                .getOrElse { return@withLock Result.failure(it) }
            val scheduled = runCatching { alarms.scheduleFollowup(id, triggerAt) }
            if (scheduled.isFailure) {
                return@withLock Result.failure(
                    scheduled.exceptionOrNull() ?: IllegalStateException("追问闹钟排程失败")
                )
            }
            Result.success(id)
        }
    }

    /**
     * 首次触发时间，三级优先：
     *
     * ① **相对时长**（`1分钟后` / `半小时后` / `3个小时后`）—— 走 [ReminderParser.relativeTriggerAt]，
     *    与任务 3.7 的提醒链共用同一套正则。
     * ② `startHint` 能解析出「日期+时刻」且严格晚于 now → 用它（`明天早上8点` / `今晚8点`）。
     * ③ 否则（无 hint / 解析失败 / 指向过去）→ `now + intervalMinutes`。
     *
     * **为什么必须有 ①**（2026-11-20 真机）：`TimeAnchorConverter.parseComponents` 只认日期与钟点，
     * 用户说「1分钟后开始，每5分钟问一次」时它解析不出来 → 静默落到 ③，5 分钟后才响。
     * 用户看到的是「说好 1 分钟后，结果没通知」，而 DB 里 everything 都正常 —— 这种「静默降级」
     * 是最难排查的一类，所以相对时长必须显式优先处理，不能靠兜底。
     */
    private fun resolveTriggerTime(ex: FollowupExtraction): Long {
        val nowMs = now()
        val fallback = nowMs + ex.intervalMinutes * 60_000L
        val hint = ex.startHint
        if (hint.isNullOrBlank()) return fallback

        // ① 相对时长：now + N 分钟/小时。`relativeTriggerAt` 已保证 > now，无需再判。
        ReminderParser.relativeTriggerAt(hint.trim(), nowMs)?.let { return it }

        // ② 绝对时刻
        val c = runCatching { TimeAnchorConverter.parseComponents(hint.trim(), referenceDate()) }
            .getOrNull() ?: return fallback
        val hour = c.hour ?: return fallback
        val minute = c.minute ?: return fallback
        val date = c.date ?: referenceDate()
        val at = runCatching { date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull() ?: return fallback
        return if (at <= nowMs) fallback else at
    }
}
