package com.companion.chat.domain.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeepAliveGuide 判据与文案单测。任务 6.2 */
class KeepAliveGuideTest {

    @Test
    fun shows_guide_only_on_first_run_and_when_not_whitelisted() {
        assertTrue(KeepAliveGuide.shouldShowGuide(guideShown = false, ignoringBatteryOptimizations = false))
        assertFalse(KeepAliveGuide.shouldShowGuide(guideShown = true, ignoringBatteryOptimizations = false))
        assertFalse(KeepAliveGuide.shouldShowGuide(guideShown = false, ignoringBatteryOptimizations = true))
        assertFalse(KeepAliveGuide.shouldShowGuide(guideShown = true, ignoringBatteryOptimizations = true))
    }

    @Test
    fun does_not_pop_before_notification_permission_granted() {
        assertFalse(
            KeepAliveGuide.shouldShowGuide(
                guideShown = false,
                ignoringBatteryOptimizations = false,
                notificationsEnabled = false,
                launchCount = 5
            )
        )
        assertTrue(
            KeepAliveGuide.shouldShowGuide(
                guideShown = false,
                ignoringBatteryOptimizations = false,
                notificationsEnabled = true,
                launchCount = 5
            )
        )
    }

    @Test
    fun does_not_pop_on_very_first_launch() {
        // 首次启动先让用户看到聊天界面（否则会和通知权限弹窗抢注意力）
        assertFalse(
            KeepAliveGuide.shouldShowGuide(
                guideShown = false,
                ignoringBatteryOptimizations = false,
                launchCount = 1
            )
        )
        assertTrue(
            KeepAliveGuide.shouldShowGuide(
                guideShown = false,
                ignoringBatteryOptimizations = false,
                launchCount = KeepAliveGuide.MIN_LAUNCH_COUNT
            )
        )
        assertEquals(2, KeepAliveGuide.MIN_LAUNCH_COUNT)
    }

    @Test
    fun every_target_has_title_and_description_for_every_rom() {
        for (rom in RomFamily.values()) {
            for (target in KeepAliveTarget.values()) {
                val title = KeepAliveGuide.title(target)
                val desc = KeepAliveGuide.description(target, rom)
                assertTrue("$target 标题为空", title.isNotBlank())
                assertTrue("$rom/$target 说明为空", desc.isNotBlank())
                assertTrue("$rom/$target 说明过短", desc.length > 10)
            }
        }
    }

    @Test
    fun titles_are_distinct() {
        val titles = KeepAliveTarget.values().map { KeepAliveGuide.title(it) }
        assertEquals(KeepAliveTarget.values().size, titles.toSet().size)
    }

    @Test
    fun descriptions_mention_rom_specific_hint() {
        assertTrue(KeepAliveGuide.description(KeepAliveTarget.AUTOSTART, RomFamily.MIUI).contains("小米"))
        assertTrue(KeepAliveGuide.description(KeepAliveTarget.AUTOSTART, RomFamily.HUAWEI).contains("华为"))
        assertTrue(KeepAliveGuide.description(KeepAliveTarget.AUTOSTART, RomFamily.COLOROS).contains("OPPO"))
        assertTrue(KeepAliveGuide.description(KeepAliveTarget.AUTOSTART, RomFamily.GENERIC).isNotBlank())
    }

    @Test
    fun root_auto_handle_is_placeholder_only() {
        // 计划 6.2：第一版不做，只占位
        assertFalse(KeepAliveGuide.rootAutoHandleSupported())
    }

    @Test
    fun status_derives_needs_guide_from_the_same_rule() {
        val first = KeepAliveStatus(RomFamily.MIUI, ignoringBatteryOptimizations = false, guideShown = false)
        assertTrue(first.needsGuide)
        assertFalse(first.rootAutoHandleSupported)

        val white = KeepAliveStatus(RomFamily.MIUI, ignoringBatteryOptimizations = true, guideShown = false)
        assertFalse(white.needsGuide)

        val done = KeepAliveStatus(RomFamily.MIUI, ignoringBatteryOptimizations = false, guideShown = true)
        assertFalse(done.needsGuide)
    }
}