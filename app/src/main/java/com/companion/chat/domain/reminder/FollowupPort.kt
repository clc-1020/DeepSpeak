package com.companion.chat.domain.reminder

/**
 * 追问状态（mood 模型返回的 `followup_status` 三值）。
 * 解析不出 → null，由调用方按 continue 处理。
 */
enum class FollowupStatus {
    COMPLETED, CONTINUE, ABANDONED;

    companion object {
        /** 只接受 completed / continue / abandoned（trim + 大小写不敏感），其他一律 null。 */
        fun fromRaw(raw: String?): FollowupStatus? {
            val value = raw?.trim()?.lowercase() ?: return null
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}

/** 一条活跃追问链（供 prompt 构建 + 决策）。 */
data class ActiveFollowup(
    val reminderId: Long,
    val task: String,
    val stopCondition: String?
)

/**
 * 追问端口（V2.2.2）。纯 JVM 抽象，生产实现见 `data/reminder/FollowupPortImpl`。
 *
 * 2026-11-20：从「最多一条链」改成「可多条链并存」（不同任务各催各的），
 * 所以这里的主入口是 [activeAll]；[active] 只是「最早那条」的便捷包装，
 * 保留它是为了老的单链调用点与测试不必全改。
 */
interface FollowupPort {
    /** 全部活跃追问链（`pending + followup + attemptCount>0`），按 triggerTime 升序。 */
    suspend fun activeAll(): List<ActiveFollowup>

    /** 最早的一条（无链 → null）。默认实现走 [activeAll]。 */
    suspend fun active(): ActiveFollowup? = activeAll().firstOrNull()

    suspend fun complete(reminderId: Long)
    suspend fun abandon(reminderId: Long)
    suspend fun advance(reminderId: Long)

    /** 某条链的任务文本（用于「点完成 → 按任务名删短期记忆」）；不存在返回 null。 */
    suspend fun taskOf(reminderId: Long): String?
}
