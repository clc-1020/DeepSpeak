package com.companion.chat.domain.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ReminderMemoryMatcher` 单测（2026-11-20）。
 *
 * 样本全部取自真机 `memory_short` 的原文 + `reminders.taskDesc` 原文，别自己编：
 * 判定放宽就会误删无关记忆，收紧就删不掉「已结束的提醒」。
 */
class ReminderMemoryMatcherTest {

    @Test
    fun exact_and_containment_match() {
        // 指令提醒：memory 内容就是提醒原文
        assertTrue(
            ReminderMemoryMatcher.shouldClear(
                "现在我要背单词，提醒我。每隔5分钟提醒一次",
                "现在我要背单词，提醒我。每隔5分钟提醒一次"
            )
        )
        // 短任务名靠包含判定（≤3 字只认包含，不猜 2-gram）
        assertTrue(ReminderMemoryMatcher.shouldClear("喝水，每隔1分钟提醒我一次", "喝水"))
        assertTrue(ReminderMemoryMatcher.shouldClear("用户要求1分钟后提醒喝水，之后每隔1分钟提醒一次", "喝水"))
    }

    @Test
    fun extractor_paraphrase_of_long_task_matches_by_bigrams() {
        // 记忆提取器写的措辞与 taskDesc 不同，但同主题
        assertTrue(
            ReminderMemoryMatcher.shouldClear(
                "用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）",
                "背30个单词"
            )
        )
    }

    @Test
    fun unrelated_memory_is_not_deleted() {
        // 同为「喝」但不同主题，且与「吃药」无关
        assertFalse(ReminderMemoryMatcher.shouldClear("用户喜欢喝冰美式，下午常去楼下咖啡店", "吃药"))
        assertFalse(ReminderMemoryMatcher.shouldClear("用户今天心情不错，升职了", "吃药"))
        // 短任务名不做猜测：「喝了热水」不该被「喝水」删掉
        assertFalse(ReminderMemoryMatcher.shouldClear("用户感冒了，今天喝了热水", "喝水"))
    }

    /**
     * 真机踩到的误删：长任务名（一条指令提醒的原文）与「喝水」记忆的公共片段其实只有
     * 「分钟提醒」这类套话 —— 不剥停用词就会把别的主题一起删掉。
     */
    @Test
    fun long_task_name_does_not_hit_other_topics_through_stopwords() {
        val longTask = "现在我要背单词，提醒我。每隔5分钟提醒一次"
        assertFalse(
            "不许因为「分钟提醒」把喝水记忆删掉",
            ReminderMemoryMatcher.shouldClear("喝水，每隔1分钟提醒我一次", longTask)
        )
        assertFalse(
            ReminderMemoryMatcher.shouldClear("用户要求1分钟后提醒喝水，之后每隔1分钟提醒一次", longTask)
        )
        // 但同主题的必须仍然命中
        assertTrue(
            ReminderMemoryMatcher.shouldClear("现在我要背单词，提醒我。每隔5分钟提醒一次", longTask)
        )
        assertTrue(
            ReminderMemoryMatcher.shouldClear("用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）", longTask)
        )
    }

    @Test
    fun blank_inputs_never_match() {
        assertFalse(ReminderMemoryMatcher.shouldClear("", "吃药"))
        assertFalse(ReminderMemoryMatcher.shouldClear("提醒我吃药", ""))
        assertFalse(ReminderMemoryMatcher.shouldClear("   ", "吃药"))
    }

    @Test
    fun punctuation_and_spaces_are_normalized() {
        assertTrue(
            ReminderMemoryMatcher.shouldClear(
                "提醒我：吃药，晚上九点一次（别忘了带水）",
                "吃药，晚上九点一次"
            )
        )
    }
}