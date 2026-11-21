package com.companion.chat.domain.power

import org.junit.Assert.assertEquals
import org.junit.Test

/** RomFamily 识别单测。任务 6.2 */
class RomFamilyTest {

    @Test
    fun detects_xiaomi_family() {
        assertEquals(RomFamily.MIUI, RomFamily.detect("Xiaomi", "Xiaomi"))
        assertEquals(RomFamily.MIUI, RomFamily.detect("Xiaomi", "Redmi"))
        assertEquals(RomFamily.MIUI, RomFamily.detect("Xiaomi", "POCO"))
    }

    @Test
    fun detects_huawei_and_honor() {
        assertEquals(RomFamily.HUAWEI, RomFamily.detect("HUAWEI", "HUAWEI"))
        assertEquals(RomFamily.HUAWEI, RomFamily.detect("HONOR", "HONOR"))
    }

    @Test
    fun oneplus_is_detected_before_oppo() {
        // 一加现在用 ColorOS，但有一加自己的链式启动页 → 必须优先判成一加
        assertEquals(RomFamily.ONEPLUS, RomFamily.detect("OnePlus", "OnePlus"))
    }

    @Test
    fun detects_oppo_realme() {
        assertEquals(RomFamily.COLOROS, RomFamily.detect("OPPO", "OPPO"))
        assertEquals(RomFamily.COLOROS, RomFamily.detect("realme", "realme"))
    }

    @Test
    fun detects_vivo_iqoo_meizu_samsung() {
        assertEquals(RomFamily.VIVO, RomFamily.detect("vivo", "vivo"))
        assertEquals(RomFamily.VIVO, RomFamily.detect("vivo", "iQOO"))
        assertEquals(RomFamily.FLYME, RomFamily.detect("Meizu", "meizu"))
        assertEquals(RomFamily.SAMSUNG, RomFamily.detect("samsung", "samsung"))
    }

    @Test
    fun unknown_or_null_is_generic() {
        assertEquals(RomFamily.GENERIC, RomFamily.detect("Google", "google"))
        assertEquals(RomFamily.GENERIC, RomFamily.detect(null, null))
        assertEquals(RomFamily.GENERIC, RomFamily.detect("", ""))
        assertEquals(RomFamily.GENERIC, RomFamily.detect("  ", null))
    }

    @Test
    fun matching_is_case_insensitive_and_trimmed() {
        assertEquals(RomFamily.MIUI, RomFamily.detect("  XIAOMI ", null))
        assertEquals(RomFamily.HUAWEI, RomFamily.detect(null, " Honor "))
    }
}