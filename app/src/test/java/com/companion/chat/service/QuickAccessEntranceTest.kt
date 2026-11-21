package com.companion.chat.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常驻入口启停判据的 JVM 单测（sync 涉及 Context，不做 JVM 测试）。 */
class QuickAccessEntranceTest {

    @Test
    fun shouldRun_whenEnabledAndNotificationGranted() {
        assertTrue(QuickAccessEntrance.shouldRun(true, true))
    }

    @Test
    fun shouldRun_falseWhenNotificationNotGranted() {
        assertFalse(QuickAccessEntrance.shouldRun(true, false))
    }

    @Test
    fun shouldRun_falseWhenDisabled() {
        assertFalse(QuickAccessEntrance.shouldRun(false, true))
    }

    @Test
    fun shouldRun_falseWhenBothOff() {
        assertFalse(QuickAccessEntrance.shouldRun(false, false))
    }
}
