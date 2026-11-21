package com.companion.chat.domain.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FollowupExtractionParser 容错解析单测（V2.2.3，纯 JVM）。 */
class FollowupExtractionParserTest {

    @Test
    fun full_json_all_fields() {
        val ex = FollowupExtractionParser.parse(
            """{"is_followup":true,"task":"背30个单词","start":"今晚8点","interval_minutes":15,"stop_condition":"用户说背完了"}"""
        )!!
        assertEquals("背30个单词", ex.task)
        assertEquals(15, ex.intervalMinutes)
        assertEquals("今晚8点", ex.startHint)
        assertEquals("用户说背完了", ex.stopCondition)
    }

    @Test
    fun is_followup_false_returns_null() {
        assertNull(FollowupExtractionParser.parse("""{"is_followup":false,"task":"x"}"""))
    }

    @Test
    fun blank_task_returns_null() {
        assertNull(FollowupExtractionParser.parse("""{"is_followup":true,"task":"  "}"""))
        assertNull(FollowupExtractionParser.parse("""{"is_followup":true}"""))
    }

    @Test
    fun missing_zero_or_negative_interval_defaults_to_15() {
        assertEquals(15, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x"}""")!!.intervalMinutes)
        assertEquals(15, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":0}""")!!.intervalMinutes)
        assertEquals(15, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":-3}""")!!.intervalMinutes)
        assertEquals(15, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":"abc"}""")!!.intervalMinutes)
    }

    @Test
    fun huge_interval_is_clamped_to_1440() {
        assertEquals(1440, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":100000}""")!!.intervalMinutes)
        assertEquals(1440, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":1440}""")!!.intervalMinutes)
        assertEquals(30, FollowupExtractionParser.parse("""{"is_followup":true,"task":"x","interval_minutes":30}""")!!.intervalMinutes)
    }

    @Test
    fun invalid_json_returns_null_without_throwing() {
        assertNull(FollowupExtractionParser.parse("not a json"))
        assertNull(FollowupExtractionParser.parse(""))
        assertNull(FollowupExtractionParser.parse("有花括号但没闭合 {"))
        assertNull(FollowupExtractionParser.parse("""{"is_followup":null,"task":"x"}"""))
        assertNull(FollowupExtractionParser.parse("[1,2,3]"))
    }

    @Test
    fun markdown_fence_and_surrounding_text_are_tolerated() {
        val raw = """好的，这是结果：
```json
{"is_followup":true,"task":"喝水","interval_minutes":20}
```
以上。"""
        val ex = FollowupExtractionParser.parse(raw)!!
        assertEquals("喝水", ex.task)
        assertEquals(20, ex.intervalMinutes)
        assertNull(ex.startHint)
        assertNull(ex.stopCondition)
    }

    @Test
    fun string_true_and_string_interval_accepted() {
        val ex = FollowupExtractionParser.parse(
            """{"is_followup":"true","task":"跑步","interval_minutes":"45"}"""
        )!!
        assertEquals("跑步", ex.task)
        assertEquals(45, ex.intervalMinutes)
    }

    @Test
    fun blank_optional_fields_become_null() {
        val ex = FollowupExtractionParser.parse(
            """{"is_followup":true,"task":" 背单词 ","start":"  ","stop_condition":"   "}"""
        )!!
        assertEquals("背单词", ex.task)
        assertNull(ex.startHint)
        assertNull(ex.stopCondition)
    }
}
