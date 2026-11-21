package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「想你」曲线（P2）：2 小时内不产生、单调递增、24 小时封顶 0.7。 */
class MissingEmotionTest {

    @Test
    fun under_two_hours_produces_nothing() {
        assertNull(MissingEmotion.target(0.0))
        assertNull(MissingEmotion.target(1.9))
    }

    @Test
    fun grows_with_away_time() {
        val h3 = MissingEmotion.target(3.0)!!
        val h8 = MissingEmotion.target(8.0)!!
        val h20 = MissingEmotion.target(20.0)!!
        assertTrue("3h 应>=0.3，实际 $h3", h3 >= 0.30)
        assertTrue(h8 > h3)
        assertTrue(h20 > h8)
    }

    @Test
    fun capped_at_seventy_percent() {
        assertEquals(0.70, MissingEmotion.target(24.0)!!, 1e-6)
        assertEquals(0.70, MissingEmotion.target(100.0)!!, 1e-6)
    }

    @Test
    fun just_over_two_hours_is_small_not_zero() {
        val v = MissingEmotion.target(2.1)!!
        assertTrue("刚过 2 小时应是很轻的想你（$v）", v in 0.29..0.35)
    }
}