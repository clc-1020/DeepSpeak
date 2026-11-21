package com.companion.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** ConversationDeletionPolicy 单测（JVM）。任务 1.8 */
class ConversationDeletionPolicyTest {

    @Test
    fun delete_non_current_keeps_ui() {
        val a = ConversationDeletionPolicy.decide(
            deletedId = 2, currentId = 1, remainingOrderedIds = listOf(1, 3)
        )
        assertEquals(ConversationDeletionPolicy.Action.None, a)
    }

    @Test
    fun delete_current_switches_to_most_recent_remaining() {
        val a = ConversationDeletionPolicy.decide(
            deletedId = 1, currentId = 1, remainingOrderedIds = listOf(5, 4, 3)
        )
        assertEquals(ConversationDeletionPolicy.Action.SwitchTo(5), a)
    }

    @Test
    fun delete_current_no_remaining_creates_new() {
        val a = ConversationDeletionPolicy.decide(
            deletedId = 1, currentId = 1, remainingOrderedIds = emptyList()
        )
        assertEquals(ConversationDeletionPolicy.Action.CreateNew, a)
    }

    @Test
    fun null_current_is_none() {
        val a = ConversationDeletionPolicy.decide(
            deletedId = 2, currentId = null, remainingOrderedIds = listOf(1)
        )
        assertEquals(ConversationDeletionPolicy.Action.None, a)
    }
}
