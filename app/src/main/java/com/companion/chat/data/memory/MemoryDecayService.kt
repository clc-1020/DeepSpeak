package com.companion.chat.data.memory

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.domain.memory.MemoryDecay
import java.time.LocalDate
import java.time.ZoneId

/**
 * 短期记忆淡忘衰减的执行器。任务 2.5
 *
 * 每天跑一次：所有短期记忆 `fadeScore += 天数差 × 因子`（**单条 SQL 原子自增**），并把
 * 「上次衰减 epochDay」写进 app_state。用游标天数差补齐未运行空档；同一天重复调用幂等（天数差 0 → 不动）。
 * 只调 fadeScore，**从不删除记忆**（旧记忆在记忆管理界面仍可查看）。
 *
 * 原子性：`incrementAllFadeScore + put 游标` 包在 [runInTransaction] 里（真实环境用 Room `withTransaction`），
 * 崩溃时要么都生效要么都不生效，避免「游标已前移但记忆没加衰减」导致下轮漏衰减/或重复衰减。
 */
class MemoryDecayService(
    private val shortDao: MemoryShortDao,
    private val appStateDao: AppStateDao,
    private val factor: Double = MemoryDecay.DAILY_FACTOR,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
    /** 事务包装：真实环境传 `database.withTransaction { it() }`；单测默认直接执行。 */
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() }
) {

    /** 执行一次衰减，返回被更新的记忆条数（0 = 今天已处理过或首次初始化游标）。 */
    suspend fun runOnce(): Int {
        val todayEpochDay = today().toEpochDay()
        val last = appStateDao.get(AppStateEntity.KEY_LAST_DECAY)?.toLongOrNull()
        if (last == null) {
            // 首次：只落游标，不做衰减（避免新装当天就衰减刚建的记忆）
            appStateDao.put(AppStateEntity(AppStateEntity.KEY_LAST_DECAY, todayEpochDay.toString()))
            return 0
        }
        val days = MemoryDecay.daysBetween(last, todayEpochDay)
        if (days <= 0) return 0 // 同一天已处理 → 幂等

        val inc = MemoryDecay.increment(days, factor)
        val count = shortDao.count()
        runInTransaction {
            shortDao.incrementAllFadeScore(inc)
            appStateDao.put(AppStateEntity(AppStateEntity.KEY_LAST_DECAY, todayEpochDay.toString()))
        }
        return count
    }
}
