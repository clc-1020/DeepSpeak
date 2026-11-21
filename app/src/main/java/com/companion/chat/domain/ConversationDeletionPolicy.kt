package com.companion.chat.domain

/**
 * 删除会话后的「当前会话」切换决策（纯逻辑，可单测）。任务 1.8
 *
 * 规则：
 * - 删的不是当前会话 → 界面不动（None）
 * - 删的是当前会话 → 切到剩余里最近的一个（SwitchTo）；一个都不剩 → 新建空会话（CreateNew）
 */
object ConversationDeletionPolicy {

    sealed interface Action {
        /** 切到该会话 */
        data class SwitchTo(val id: Long) : Action
        /** 一个都不剩，需要新建空会话 */
        object CreateNew : Action
        /** 删的不是当前会话，界面不动 */
        object None : Action
    }

    /**
     * @param deletedId           被删除的会话 id
     * @param currentId           删除前的当前会话 id（可能为 null）
     * @param remainingOrderedIds 删除后剩余会话 id，按最近活跃降序
     */
    fun decide(deletedId: Long, currentId: Long?, remainingOrderedIds: List<Long>): Action {
        if (currentId == null || currentId != deletedId) return Action.None
        val next = remainingOrderedIds.firstOrNull()
        return if (next != null) Action.SwitchTo(next) else Action.CreateNew
    }
}
