package com.companion.chat.domain.mood

import org.junit.Assert.assertEquals
import org.junit.Test

/** MoodTone.mood → 标签/语气指引单测。任务 5.2 */
class MoodToneTest {

    @Test
    fun normalize_keeps_known_values() {
        assertEquals(Mood.NORMAL, MoodTone.normalize("normal"))
        assertEquals(Mood.HAPPY, MoodTone.normalize("happy"))
        assertEquals(Mood.LOW, MoodTone.normalize("low"))
    }

    @Test
    fun normalize_handles_case_space_null_and_unknown() {
        assertEquals(Mood.HAPPY, MoodTone.normalize(" HAPPY "))
        assertEquals(Mood.NORMAL, MoodTone.normalize(null))
        assertEquals(Mood.NORMAL, MoodTone.normalize(""))
        assertEquals(Mood.NORMAL, MoodTone.normalize("excited"))
    }

    @Test
    fun labels_are_chinese() {
        assertEquals("心情不错", MoodTone.label(Mood.HAPPY))
        assertEquals("有点低落", MoodTone.label(Mood.LOW))
        assertEquals("平常", MoodTone.label(Mood.NORMAL))
        assertEquals("平常", MoodTone.label(null))
    }

    @Test
    fun directive_differs_per_mood() {
        val happy = MoodTone.directive(Mood.HAPPY)
        val low = MoodTone.directive(Mood.LOW)
        val normal = MoodTone.directive(Mood.NORMAL)
        assertEquals(3, setOf(happy, low, normal).size)
        assertTrueContains(happy, "活泼")
        assertTrueContains(low, "温和")
        assertTrueContains(normal, "平和")
    }

    @Test
    fun directive_never_empty_even_for_unknown() {
        assertTrueContains(MoodTone.directive("???"), "语气")
        assertTrueContains(MoodTone.directive(null), "语气")
    }

    private fun assertTrueContains(text: String, part: String) {
        org.junit.Assert.assertTrue("期望包含「$part」，实际：$text", text.contains(part))
    }
}