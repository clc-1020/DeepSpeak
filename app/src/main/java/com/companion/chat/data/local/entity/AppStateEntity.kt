package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局状态键值表：存 mood、上次交互时间、近 7 天回复率统计等零散状态。
 * value 统一存字符串（数字/JSON 自行序列化）。
 * 任务 1.1
 */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey
    val key: String,
    val value: String
) {
    companion object {
        const val KEY_MOOD = "mood"                    // normal / happy / low
        const val KEY_MOOD_REASON = "mood_reason"
        const val KEY_MOOD_TURN_COUNT = "mood_turn_count" // 对话轮数计数器（任务 5.1，每 5 轮更新一次 mood）
        const val KEY_LAST_INTERACTION = "last_interaction" // epoch millis
        const val KEY_REPLY_STATS = "reply_stats"      // JSON: 近 7 天主动消息回复统计
        const val KEY_LAST_DECAY = "last_decay_epoch_day" // 上次淡忘衰减的 epochDay（任务 2.5，避免重复衰减）
        const val KEY_PROACTIVE_SENT = "proactive_sent_count" // 今日已发自发消息计数（任务 3.5，JSON: {day,count}）
        const val KEY_PROACTIVE_COUNT_MIGRATION = "proactive_count_migration_v3" // 一次性迁移标记：v2=测试污染，v3=调试快速链污染，各清一次
        const val KEY_KEEPALIVE_GUIDE_SHOWN = "keepalive_guide_shown" // 保活引导是否已弹过（任务 6.2，"true"）
        const val KEY_APP_LAUNCH_COUNT = "app_launch_count" // 累计启动次数（任务 6.2 弹窗时机：≥2 次才弹）
        const val KEY_VOICE_PENDING_TEXT = "voice_pending_text"   // V2.3.6：通知栏语音识别结果，MainActivity 读一次即删

        // ---- 2026-11-22：对话模式 ↔ 会话绑定 ----
        // 两种模式各用各的会话，历史才隔离得干净（system 关掉也挡不住历史里的语气）。
        const val KEY_COMPANION_CONVERSATION_ID = "companion_conversation_id"
        const val KEY_NORMAL_CONVERSATION_ID = "normal_conversation_id"

        /**
         * 情绪状态 JSON（2026-11-21 情绪系统 P1）：`{his:{mood,intensity,since}, hers:{emotions,trigger,since}, updated_at}`。
         * **衰减不需要定时任务**：读的时候按 `now - updated_at` 现算。
         */
        const val KEY_EMOTION_STATE = "emotion_state"

        /** 最近一次「示弱」发生的本地日期（yyyy-MM-dd）。护栏：同一天最多示弱一次。 */
        const val KEY_WEAKNESS_DATE = "emotion_weakness_date"

        /** P3.1（2026-11-21）：热络度存值（0~1）+ 上次更新时间（epoch millis）。读取时按 14 天半衰现算。 */
        const val KEY_WARMTH = "warmth"
        const val KEY_WARMTH_AT = "warmth_updated_at"

        /**
         * 周期任务「上次排程时刻」（epoch millis）。用于时钟跳变自愈：
         * 系统时间被改过（回拨/前跳）时，WorkManager 的 next 会算到很久以后，必须重排。
         * 见 `domain/work/WorkScheduleHealth`。
         */
        const val KEY_PROACTIVE_SCHEDULED_AT_V2 = "proactive_scheduled_at_v2"

        /** 上次 ProactiveCheckWorker **实际运行**的时刻（心跳）。每小时的环境整点检查靠它兜底。 */
        const val KEY_PROACTIVE_LAST_RUN = "proactive_last_run"
        const val KEY_DECAY_SCHEDULED_AT = "memory_decay_scheduled_at"

        /** 兜底话题：长期记忆最近被用过的时刻（CSV `id:epochMillis,...`，7 天冷却）。 */
        const val KEY_PROACTIVE_LONG_USED = "proactive_long_used"

        /** 兜底话题：纯关心上次发送的本地日期（yyyy-MM-dd，每天最多 1 次）。 */
        const val KEY_PROACTIVE_KINDNESS_DATE = "proactive_kindness_date"

        /** 上一条主动消息**送达**时刻（epoch millis）——纯关心的最小间隔靠它。 */
        const val KEY_PROACTIVE_LAST_DELIVERED = "proactive_last_delivered_at"
    }
}
