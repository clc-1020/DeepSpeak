package com.companion.chat.domain.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试（1.13.3）：指令提醒必须走独立「定时提醒」渠道，
 * 免得用户静音闲聊主动消息时，把自己设的定时提醒一起静音（「定时任务没有弹窗通知」根因）。
 */
class ProactiveChannelPolicyTest {

    @Test
    fun instructionUsesReminderChannel() {
        assertTrue(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.INSTRUCTION))
    }

    @Test
    fun otherCategoriesUseProactiveChannel() {
        assertFalse(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.SPONTANEOUS))
        assertFalse(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.FOLLOWUP))
        assertFalse(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.SPECIAL_DATE))
        assertFalse(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.ENVIRONMENT))
        assertFalse(ProactiveChannelPolicy.usesReminderChannel(ProactiveMessageCategory.LATE_NIGHT_ALERT))
    }
}
