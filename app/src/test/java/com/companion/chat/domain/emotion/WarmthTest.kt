package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 热络度（P3.1）：14 天半衰、不低于 0.05、聊天回暖、偏冷时注入提示行。 */
class WarmthTest {

    @Test
    fun fourteen_days_halves_warmth() {
        val v = Warmth.valueAt(0.8, 14.0 * 24 * 60)
        assertEquals(0.4, v, 0.01)
    }

    @Test
    fun never_drops_below_floor() {
        assertEquals(Warmth.FLOOR, Warmth.valueAt(0.5, 3650.0 * 24 * 60), 1e-9)
    }

    @Test
    fun chatting_warms_up_and_caps_at_one() {
        assertEquals(0.5 + Warmth.PER_TURN, Warmth.afterTurn(0.5), 1e-9)
        assertEquals(1.0, Warmth.afterTurn(0.95), 1e-9)
        assertEquals("从 0 聊一轮就是 +0.12", 0.12, Warmth.afterTurn(0.0), 1e-9)
    }

    @Test
    fun labels_read_naturally() {
        assertEquals("很热络", Warmth.label(0.9))
        assertEquals("还行", Warmth.label(0.6))
        assertEquals("有点生分", Warmth.label(0.3))
        assertEquals("很久没聊了", Warmth.label(0.1))
    }

    @Test
    fun cold_warmth_produces_a_hint_line() {
        val line = Warmth.line(0.35)
        assertNotNull(line)
        assertTrue(line!!.contains("有点生分（0.4）".take(4)) || line.contains("有点生分"))
        assertTrue(line.contains("别撒娇"))
    }

    @Test
    fun warm_warmth_produces_no_line() {
        assertNull("热络时不占一个字", Warmth.line(0.6))
        assertNull(Warmth.line(0.9))
    }
}