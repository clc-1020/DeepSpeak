package com.companion.chat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知规格的 JVM 单测：把「不撞 id / 渠道隔离 / requestCode 隔离」钉死在断言里。 */
class QuickAccessNotificationSpecTest {

    @Test
    fun notificationId_isIntMaxValue() {
        assertEquals(Int.MAX_VALUE, QuickAccessNotificationSpec.NOTIFICATION_ID)
        assertEquals(Int.MAX_VALUE, QuickAccessNotificationSpec.default().notificationId)
    }

    @Test
    fun notificationId_neverCollidesWithProactiveDerivedIds() {
        // 与 ProactiveDeliveryRunner 同公式：`msg.id % Int.MAX_VALUE`。
        // 注意：本测试**复刻**该公式，不调用生产代码，因此它钉住的是「常量 + 值域」这一对约束，
        // 不是对 ProactiveDeliveryRunner 回归的守门测试（改生产公式这里不会红）。
        // 要做到两侧同源，需把公式抽成 ProactiveNotificationIds.of(msgId) 供两边共用；
        // 那会改动 data/proactive/**，超出本任务写作用域，故此处只保证断言本身成立。
        val reachableMax = (Int.MAX_VALUE - 1).toLong() % Int.MAX_VALUE
        assertEquals((Int.MAX_VALUE - 1).toLong(), reachableMax)
        assertTrue(reachableMax < QuickAccessNotificationSpec.NOTIFICATION_ID.toLong())

        listOf(1L, 7L, 100_000L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 5)
            .forEach { msgId ->
                val derived = (msgId % Int.MAX_VALUE).toInt()
                assertTrue("derived=$derived 必须落在 [0, MAX_VALUE-1]", derived in 0 until Int.MAX_VALUE)
                assertNotEquals(QuickAccessNotificationSpec.NOTIFICATION_ID, derived)
            }
    }

    @Test
    fun channelId_isQuickAccess() {
        assertEquals("quick_access", QuickAccessNotificationSpec.CHANNEL_ID)
        assertEquals("quick_access", QuickAccessNotificationSpec.default().channelId)
        assertEquals("快捷入口", QuickAccessNotificationSpec.default().channelName)
    }

    @Test
    fun flags_matchDefault() {
        val spec = QuickAccessNotificationSpec.default()
        assertTrue(spec.ongoing)
        assertTrue(spec.onlyAlertOnce)
        assertTrue(!spec.showWhen)
    }

    @Test
    fun requestCodes_areNegativeAndDistinct() {
        val spec = QuickAccessNotificationSpec.default()
        assertTrue(spec.tapRequestCode < 0)
        assertTrue(spec.voiceRequestCode < 0)
        assertNotEquals(spec.tapRequestCode, spec.voiceRequestCode)
        assertEquals(-1, spec.tapRequestCode)
        assertEquals(-2, spec.voiceRequestCode)
    }
}
