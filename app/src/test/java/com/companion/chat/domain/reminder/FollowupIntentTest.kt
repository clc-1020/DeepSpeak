package com.companion.chat.domain.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FollowupIntent 意图闸门单测（V2.2.3，费用保护：不命中就不调模型）。 */
class FollowupIntentTest {

    @Test
    fun cue_supervise() {
        assertTrue(FollowupIntent.matches("监督我每天背单词"))
    }

    @Test
    fun cue_daily_remind() {
        assertTrue(FollowupIntent.matches("每天提醒我喝水"))
        assertTrue(FollowupIntent.matches("提醒我每天背单词"))
    }

    @Test
    fun cue_urge() {
        assertTrue(FollowupIntent.matches("督促我早起"))
        assertTrue(FollowupIntent.matches("催我做作业"))
    }

    @Test
    fun cue_manage_and_watch() {
        assertTrue(FollowupIntent.matches("管着我点"))
        assertTrue(FollowupIntent.matches("盯着我别让我偷懒"))
        assertTrue(FollowupIntent.matches("看着我完成任务"))
    }

    @Test
    fun cue_call_me() {
        assertTrue(FollowupIntent.matches("每天叫我起床学习"))
        assertTrue(FollowupIntent.matches("叫我坚持"))
    }

    @Test
    fun cue_persist_plus_daily() {
        assertTrue(FollowupIntent.matches("每天都要坚持下去"))
    }

    @Test
    fun casual_chat_not_matched() {
        assertFalse(FollowupIntent.matches("今天好累"))
        assertFalse(FollowupIntent.matches("你在干嘛"))
    }

    @Test
    fun blank_not_matched() {
        assertFalse(FollowupIntent.matches(""))
        assertFalse(FollowupIntent.matches("   "))
        assertFalse(FollowupIntent.matches("\n\t "))
    }

    @Test
    fun negations_win() {
        assertFalse(FollowupIntent.matches("别监督我了"))
        assertFalse(FollowupIntent.matches("不用提醒我"))
        assertFalse(FollowupIntent.matches("不要催我"))
        assertFalse(FollowupIntent.matches("别催我"))
        assertFalse(FollowupIntent.matches("别再提醒我背单词"))
        assertFalse(FollowupIntent.matches("别看着我了"))
        assertFalse(FollowupIntent.matches("别盯着我"))
        assertFalse(FollowupIntent.matches("别管着我"))
        assertFalse(FollowupIntent.matches("不用管我"))
        // 否定词优先于正向线索
        assertFalse(FollowupIntent.matches("别监督我了，每天提醒我喝水"))
    }
}
