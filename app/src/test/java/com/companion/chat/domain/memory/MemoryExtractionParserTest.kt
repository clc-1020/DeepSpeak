package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryExtractionParser 纯解析单测。任务 2.2 */
class MemoryExtractionParserTest {

    @Test
    fun parses_string_arrays() {
        val ex = MemoryExtractionParser.parse(
            """{"short":["明天面试"],"long":["对花生过敏"],"daily":["去了公司"]}"""
        )!!
        assertEquals(listOf("明天面试"), ex.short)
        assertEquals(listOf(LongMemory("对花生过敏", "fact")), ex.long)
        assertEquals(listOf("去了公司"), ex.daily)
    }

    @Test
    fun long_objects_with_category_and_text() {
        val ex = MemoryExtractionParser.parse(
            """{"long":[{"content":"对花生过敏","category":"allergy"},{"text":"喜欢安静"}]}"""
        )!!
        assertEquals(
            listOf(LongMemory("对花生过敏", "allergy"), LongMemory("喜欢安静", "fact")),
            ex.long
        )
    }

    @Test
    fun short_objects_pick_content() {
        val ex = MemoryExtractionParser.parse(
            """{"short":[{"content":"周五交报告"},{"text":"明天面试"}]}"""
        )!!
        assertEquals(listOf("周五交报告", "明天面试"), ex.short)
    }

    @Test
    fun missing_keys_are_empty() {
        val ex = MemoryExtractionParser.parse("""{"short":["a"]}""")!!
        assertEquals(listOf("a"), ex.short)
        assertTrue(ex.long.isEmpty())
        assertTrue(ex.daily.isEmpty())
    }

    @Test
    fun empty_object_is_valid_empty_extraction() {
        val ex = MemoryExtractionParser.parse("{}")!!
        assertTrue(ex.isEmpty)
    }

    @Test
    fun invalid_json_returns_null() {
        assertNull(MemoryExtractionParser.parse("not a json"))
        assertNull(MemoryExtractionParser.parse(""))
        assertNull(MemoryExtractionParser.parse(null))
        assertNull(MemoryExtractionParser.parse("[1,2,3]")) // 顶层是数组不是对象
    }

    @Test
    fun blank_and_duplicates_dropped() {
        val ex = MemoryExtractionParser.parse("""{"short":["a","a","  ","b"]}""")!!
        assertEquals(listOf("a", "b"), ex.short)
    }

    @Test
    fun wrong_types_ignored() {
        val ex = MemoryExtractionParser.parse("""{"short":"notarray","long":123,"daily":[1,2]}""")!!
        assertTrue(ex.short.isEmpty())
        assertTrue(ex.long.isEmpty())
        assertEquals(listOf("1", "2"), ex.daily)
    }

    @Test
    fun default_category_override() {
        val ex = MemoryExtractionParser.parse("""{"long":[{"content":"x"}]}""", defaultCategory = "other")!!
        assertEquals(listOf(LongMemory("x", "other")), ex.long)
    }
}
