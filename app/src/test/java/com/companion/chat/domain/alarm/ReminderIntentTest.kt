package com.companion.chat.domain.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReminderIntent 提醒意图判断单测。任务 3.3 */
class ReminderIntentTest {

    @Test
    fun reminder_cues_detected() {
        assertTrue(ReminderIntent.matches("明天7点叫我起床"))
        assertTrue(ReminderIntent.matches("提醒我明天开会"))
        assertTrue(ReminderIntent.matches("别忘记带伞"))
        assertTrue(ReminderIntent.matches("9点记得吃药"))
        assertTrue(ReminderIntent.matches("设个闹钟"))
    }

    @Test
    fun casual_chat_not_matched() {
        assertFalse(ReminderIntent.matches("今天天气不错"))
        assertFalse(ReminderIntent.matches("你在干嘛"))
        assertFalse(ReminderIntent.matches(""))
        assertFalse(ReminderIntent.matches("   "))
    }

    @Test
    fun message_request_cues_detected() {
        // 任务 3.8：用户想「到点让它主动发一条」，也算可排期指令
        assertTrue(ReminderIntent.matches("1分钟后发条信息给我"))
        assertTrue(ReminderIntent.matches("2分钟后发条消息给我"))
        assertTrue(ReminderIntent.matches("晚点发消息给我"))
        assertTrue(ReminderIntent.matches("10点通知我"))
    }

    @Test
    fun expanded_cues_detected() {
        assertTrue(ReminderIntent.matches("9点记得吃药"))
        assertTrue(ReminderIntent.matches("8点提醒下我"))
        assertTrue(ReminderIntent.matches("10点敲我一下"))
        assertTrue(ReminderIntent.matches("7点喊我起床"))
        assertTrue(ReminderIntent.matches("6点给我发信息"))
        assertTrue(ReminderIntent.matches("5点吱一声"))
        assertTrue(ReminderIntent.matches("4点帮我记着开会"))
    }

    @Test
    fun narration_about_past_not_matched() {
        // 闲聊在提过去，不是让系统排期
        assertFalse(ReminderIntent.matches("我记得昨天7点你说过"))
        assertFalse(ReminderIntent.matches("你还记得吗"))
        assertFalse(ReminderIntent.matches("我记不清了"))
    }
}
