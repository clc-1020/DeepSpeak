package com.companion.chat.data.local.dao

import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.PendingMessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * `PendingMessageDao` 的 JVM 假实现（任务 3.1 起用于 `PendingMessageQueueTest`，
 * V2.2.4 提到共用位置给 `FollowupChainRunnerTest` 用）。
 *
 * 行为与真实 `@Query` 对齐：
 *  - `nextDue`: state=pending 且 triggerTime <= now，priority 降序 → triggerTime 升序 → id 升序
 *  - `transitionState`: 只在当前 state ∈ validFrom 时生效，返回受影响行数
 *  - `updateTriggerTime`: 只动 pending 行
 *
 * 额外提供 [failNextInsert]（V2.2.4）：`FollowupChainRunner` 必须做到「入队失败也先计数、
 * 且 TIMEOUT 已排」，没有可注入失败的 fake 就断言不了这条顺序。
 */
class FakePendingMessageDao : PendingMessageDao {

    /** 可变存储；测试可直接读写断言。 */
    val items = ArrayList<PendingMessageEntity>()
    private var seq = 0L

    /** 插入调用次数（断言「非法 id 时零交互」用）。 */
    var insertCalls = 0
        private set

    /**
     * 下一次（或此后每次）insert 抛这个异常；null = 正常。
     * 抛之前 `insertCalls` 已 +1，便于同时断言「确实尝试过」。
     */
    var failNextInsert: Throwable? = null

    /** 跨 fake 的调用顺序流水（V2.2.4），与 `FakeReminderDao.order` 塞同一个 list。 */
    var order: MutableList<String>? = null

    override suspend fun insert(message: PendingMessageEntity): Long {
        insertCalls++
        // 先记顺序再抛：失败那次也要能断言「计数发生在入队之前」
        order?.add("enqueue")
        failNextInsert?.let { throw it }
        val withId = message.copy(id = if (message.id == 0L) ++seq else message.id)
        items.add(withId)
        return withId.id
    }

    override suspend fun nextDue(now: Long): PendingMessageEntity? =
        items.filter { it.state == PendingMessageState.PENDING && it.triggerTime <= now }
            .sortedWith(
                compareByDescending<PendingMessageEntity> { it.priority }
                    .thenBy { it.triggerTime }.thenBy { it.id }
            )
            .firstOrNull()

    override suspend fun getById(id: Long): PendingMessageEntity? = items.firstOrNull { it.id == id }

    override suspend fun getByState(state: String): List<PendingMessageEntity> =
        items.filter { it.state == state }
            .sortedWith(
                compareByDescending<PendingMessageEntity> { it.priority }
                    .thenBy { it.triggerTime }.thenBy { it.id }
            )

    override fun observeAll(): Flow<List<PendingMessageEntity>> = emptyFlow()

    override suspend fun transitionState(id: Long, newState: String, validFrom: List<String>): Int {
        val i = items.indexOfFirst { it.id == id && it.state in validFrom }
        if (i < 0) return 0
        items[i] = items[i].copy(state = newState)
        return 1
    }

    override suspend fun updateState(id: Long, state: String): Int {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return 0
        items[i] = items[i].copy(state = state)
        return 1
    }

    override suspend fun updateTriggerTime(id: Long, triggerTime: Long): Int {
        val i = items.indexOfFirst { it.id == id && it.state == PendingMessageState.PENDING }
        if (i < 0) return 0
        items[i] = items[i].copy(triggerTime = triggerTime)
        return 1
    }

    override suspend fun countPending(): Int =
        items.count { it.state == PendingMessageState.PENDING }

    override suspend fun countPendingByContent(content: String): Int =
        items.count { it.state == PendingMessageState.PENDING && it.content == content }

    override suspend fun countAvailable(now: Long): Int =
        items.count { it.state == PendingMessageState.PENDING && it.triggerTime <= now }

    /**
     * V2.2.5：`cancelPendingByContentPrefix` 的调用记录（每行一条 `"<prefix>|<newState>"`）。
     * 用来断言「非法 id 时**一次 DAO 调用都没有**」，否则只能靠「行数不变」这种弱断言。
     */
    val cancelPrefixCalls = mutableListOf<String>()

    /**
     * 对齐真实 SQL：`state = 'pending' AND content LIKE :prefix || '%'`。
     *
     * 前缀只含数字与冒号，不存在 LIKE 通配符/大小写差异，故 [String.startsWith] 等价。
     * **尾冒号仍由调用方保证**——这条 fake 不替调用方补，否则 §10.3 第 1 条就测不出来了。
     */
    override suspend fun cancelPendingByContentPrefix(prefix: String, newState: String): Int {
        cancelPrefixCalls += "$prefix|$newState"
        // 与 insert 共用同一 order 流水：V2.2.5 要断言「complete 先于清理队列」
        order?.add("cancel")
        var changed = 0
        for (i in items.indices) {
            val row = items[i]
            if (row.state == PendingMessageState.PENDING && row.content.startsWith(prefix)) {
                items[i] = row.copy(state = newState)
                changed++
            }
        }
        return changed
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}