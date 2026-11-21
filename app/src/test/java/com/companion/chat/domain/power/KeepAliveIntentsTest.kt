package com.companion.chat.domain.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeepAliveIntents 候选表单测。任务 6.2 */
class KeepAliveIntentsTest {

    private val pkg = "com.companion.chat"

    private fun List<IntentSpec>.keys() = map { it.action to it.packageName to (it.className to it.data) }

    @Test
    fun battery_whitelist_requests_ignore_first_then_list() {
        val list = KeepAliveIntents.batteryWhitelist(pkg)
        assertEquals(2, list.size)
        assertEquals(
            KeepAliveIntents.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            list[0].action
        )
        assertEquals("package:$pkg", list[0].data)
        assertEquals(KeepAliveIntents.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, list[1].action)
    }

    @Test
    fun every_target_chain_ends_with_app_details() {
        for (rom in RomFamily.values()) {
            for (target in KeepAliveTarget.values()) {
                val chain = KeepAliveIntents.specsFor(target, rom, pkg)
                assertTrue("$rom/$target 候选链为空", chain.isNotEmpty())
                val last = chain.last()
                assertEquals(
                    "目标 $target 的兜底不是应用详情页",
                    KeepAliveIntents.ACTION_APPLICATION_DETAILS_SETTINGS,
                    last.action
                )
                assertEquals("package:$pkg", last.data)
            }
        }
    }

    @Test
    fun generic_rom_has_no_vendor_pages_but_still_usable() {
        assertTrue(KeepAliveIntents.autostart(RomFamily.GENERIC).isEmpty())
        assertTrue(KeepAliveIntents.backgroundSettings(RomFamily.GENERIC).isEmpty())
        val chain = KeepAliveIntents.specsFor(KeepAliveTarget.AUTOSTART, RomFamily.GENERIC, pkg)
        assertEquals(1, chain.size) // 只有应用详情页
    }

    @Test
    fun vendor_pages_are_explicit_components() {
        val miui = KeepAliveIntents.autostart(RomFamily.MIUI)
        assertTrue(miui.isNotEmpty())
        assertTrue(miui.all { it.isExplicit })
        assertEquals("com.miui.securitycenter", miui.first().packageName)
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            miui.first().className
        )

        for (rom in RomFamily.values()) {
            for (spec in KeepAliveIntents.autostart(rom) + KeepAliveIntents.backgroundSettings(rom)) {
                assertTrue("$rom 出现了非显式组件候选：$spec", spec.isExplicit)
                assertTrue("${spec.packageName} 应以 com. 开头", spec.packageName!!.startsWith("com."))
            }
        }
    }

    @Test
    fun chains_have_no_duplicates() {
        for (rom in RomFamily.values()) {
            for (target in KeepAliveTarget.values()) {
                val keys = KeepAliveIntents.specsFor(target, rom, pkg).keys()
                assertEquals("$rom/$target 有重复候选", keys.size, keys.toSet().size)
            }
        }
    }

    @Test
    fun all_specs_have_non_blank_action_and_no_self_reference() {
        for (rom in RomFamily.values()) {
            for (target in KeepAliveTarget.values()) {
                for (spec in KeepAliveIntents.specsFor(target, rom, pkg)) {
                    assertTrue(spec.action.isNotBlank())
                    assertFalse("不该跳自己：$spec", spec.packageName == pkg)
                }
            }
        }
    }

    @Test
    fun guide_order_starts_with_battery_whitelist() {
        assertEquals(
            KeepAliveTarget.BATTERY_WHITELIST,
            KeepAliveTarget.GUIDE_ORDER.first()
        )
        assertEquals(KeepAliveTarget.values().size, KeepAliveTarget.GUIDE_ORDER.size)
        assertEquals(KeepAliveTarget.GUIDE_ORDER.size, KeepAliveTarget.GUIDE_ORDER.toSet().size)
    }

    @Test
    fun withData_copies_without_mutating_original() {
        val base = IntentSpec(KeepAliveIntents.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val withUri = base.withData("package:$pkg")
        assertEquals(null, base.data)
        assertEquals("package:$pkg", withUri.data)
        assertEquals(base.action, withUri.action)
    }
}