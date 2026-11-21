package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/** MemoryCategory 归一化单测。任务 2.3 审查修复·问题 4 */
class MemoryCategoryTest {

    @Test
    fun english_standard_values() {
        assertEquals(MemoryCategory.ALLERGY, MemoryCategory.normalize("allergy"))
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.normalize("preference"))
        assertEquals(MemoryCategory.FACT, MemoryCategory.normalize("fact"))
        assertEquals(MemoryCategory.RELATIONSHIP, MemoryCategory.normalize("relationship"))
    }

    @Test
    fun chinese_values() {
        assertEquals(MemoryCategory.ALLERGY, MemoryCategory.normalize("过敏"))
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.normalize("偏好"))
        assertEquals(MemoryCategory.RELATIONSHIP, MemoryCategory.normalize("关系"))
        assertEquals(MemoryCategory.FACT, MemoryCategory.normalize("个人信息"))
    }

    @Test
    fun typo_variants() {
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.normalize("preferrence"))
        assertEquals(MemoryCategory.ALLERGY, MemoryCategory.normalize("allergies"))
    }

    @Test
    fun blank_and_null_are_fact() {
        assertEquals(MemoryCategory.FACT, MemoryCategory.normalize("  "))
        assertEquals(MemoryCategory.FACT, MemoryCategory.normalize(null))
    }

    @Test
    fun unknown_kept_lowercased() {
        assertEquals("misc2", MemoryCategory.normalize("Misc2"))
    }

    @Test
    fun keyword_fallback() {
        assertEquals(MemoryCategory.ALLERGY, MemoryCategory.normalize("food_allergy_info"))
        assertEquals(MemoryCategory.FACT, MemoryCategory.normalize("info"))
    }
}
