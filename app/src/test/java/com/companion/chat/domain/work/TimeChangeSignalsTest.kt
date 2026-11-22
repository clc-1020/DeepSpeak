package com.companion.chat.domain.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimeChangeSignals 纯函数单测（1.9.4.2）。 */
class TimeChangeSignalsTest {

    @Test
    fun manual_time_change_is_handled() {
        assertTrue(TimeChangeSignals.isTimeChangeAction("android.intent.action.TIME_SET"))
    }

    @Test
    fun timezone_change_is_handled() {
        assertTrue(TimeChangeSignals.isTimeChangeAction("android.intent.action.TIMEZONE_CHANGED"))
    }

    /** 每天零点的 DATE_CHANGED 不算——否则会每天重置一次排程。 */
    @Test
    fun date_changed_is_not_handled() {
        assertFalse(TimeChangeSignals.isTimeChangeAction("android.intent.action.DATE_CHANGED"))
    }

    @Test
    fun unrelated_or_null_not_handled() {
        assertFalse(TimeChangeSignals.isTimeChangeAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(TimeChangeSignals.isTimeChangeAction(null))
        assertFalse(TimeChangeSignals.isTimeChangeAction(""))
    }
}
