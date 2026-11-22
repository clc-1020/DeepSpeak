package com.companion.chat.domain.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深度回忆触发检测单测（2026-11-22）。
 *
 * 核心守住两件事：
 *  1. 明确过去回忆词要触发；
 *  2. 未来/待办句（"明天/答应/提醒我"）**绝不能**误判成回忆（坑1）。
 */
class RecallTriggerDetectorTest {

    @Test
    fun explicit_past_recall_words_trigger() {
        assertTrue(RecallTriggerDetector.detect("你还记不记得上次那家店").triggered)
        assertTrue(RecallTriggerDetector.detect("我上次跟你说的那个电影").triggered)
        assertTrue(RecallTriggerDetector.detect("之前说的那件事还记得吗").triggered)
        assertTrue(RecallTriggerDetector.detect("那次去海边你还记得吗").triggered)
        assertTrue(RecallTriggerDetector.detect("你还记得上周咱们吵架的事吗").triggered)
    }

    @Test
    fun future_or_todo_is_not_recall() {
        // 坑1：这些是"提醒未来"，不是"回忆过去"
        assertFalse(RecallTriggerDetector.detect("你还记不记得我明天要开会").triggered)
        assertFalse(RecallTriggerDetector.detect("记不记得答应我什么").triggered)
        assertFalse(RecallTriggerDetector.detect("上次说的，下次记得要带").triggered)
        assertFalse(RecallTriggerDetector.detect("你还记得下周要交作业吗").triggered)
    }

    @Test
    fun plain_reminder_is_not_recall() {
        // 裸"记得/提醒我"属于提醒路径，不该触发回忆（也没带过去回忆词）
        assertFalse(RecallTriggerDetector.detect("记得提醒我明天买药").triggered)
        assertFalse(RecallTriggerDetector.detect("7 点叫我起床").triggered)
    }

    @Test
    fun ordinary_chat_is_not_recall() {
        assertFalse(RecallTriggerDetector.detect("今天好热啊").triggered)
        assertFalse(RecallTriggerDetector.detect("我今晚想吃火锅").triggered)
    }

    @Test
    fun blank_or_short_text_is_not_recall() {
        assertFalse(RecallTriggerDetector.detect("").triggered)
        assertFalse(RecallTriggerDetector.detect("嗯").triggered)
        assertFalse(RecallTriggerDetector.detect("   ").triggered)
    }

    @Test
    fun matched_words_are_reported_for_diagnostics() {
        val s = RecallTriggerDetector.detect("你还记得上周咱们吵架的事吗")
        assertTrue(s.triggered)
        assertTrue("命中的词要回传，便于日志排查", s.matched.isNotEmpty())
    }
}
