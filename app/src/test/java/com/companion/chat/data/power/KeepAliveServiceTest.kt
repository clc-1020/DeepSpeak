package com.companion.chat.data.power

import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.domain.power.KeepAliveTarget
import com.companion.chat.domain.power.RomFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeepAliveService 状态机单测（假 DAO / 假白名单）。任务 6.2 */
class KeepAliveServiceTest {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
        override suspend fun getAll(): List<AppStateEntity> = map.map { AppStateEntity(it.key, it.value) }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
    }

    private fun service(dao: FakeAppState, ignoring: Boolean, notifications: Boolean = true) =
        KeepAliveService(
            appStateDao = dao,
            rom = RomFamily.MIUI,
            ignoringBatteryOptimizations = { ignoring },
            notificationsEnabled = { notifications }
        )

    @Test
    fun after_second_launch_guide_is_due() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false)
        // 6.2 审查改进：首次启动不弹，第 2 次启动才「该弹」
        assertFalse(s.shouldShowGuide())
        s.recordLaunchAndShouldGuide()
        s.recordLaunchAndShouldGuide()
        assertTrue(s.shouldShowGuide())
        assertFalse(s.guideShown())
    }

    @Test
    fun already_whitelisted_skips_guide() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = true)
        assertFalse(s.shouldShowGuide())
        // 不写库：白名单判定与「弹过没」是两件事
        assertFalse(s.guideShown())
    }

    @Test
    fun mark_guide_shown_persists_and_stops_nagging() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false)
        s.markGuideShown()
        assertTrue(s.guideShown())
        assertFalse(s.shouldShowGuide())
        assertEquals("true", dao.map[AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN])
    }

    @Test
    fun reset_guide_allows_showing_again() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_APP_LAUNCH_COUNT] = "2" // 已过「启动次数」门槛
        val s = service(dao, ignoring = false)
        s.markGuideShown()
        assertFalse(s.shouldShowGuide())
        s.resetGuide()
        assertFalse(s.guideShown())
        assertTrue(s.shouldShowGuide())
        assertFalse(dao.map.containsKey(AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN))
    }

    @Test
    fun status_reports_rom_whitelist_and_flag() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false)
        val st = s.status()
        assertEquals(RomFamily.MIUI, st.rom)
        assertFalse(st.ignoringBatteryOptimizations)
        assertFalse(st.guideShown)
        assertTrue(st.needsGuide)
        assertFalse(st.rootAutoHandleSupported)

        s.markGuideShown()
        val st2 = s.status()
        assertTrue(st2.guideShown)
        assertFalse(st2.needsGuide)
    }

    @Test
    fun steps_follow_plan_order_with_whitelist_first() = runBlocking {
        val s = service(FakeAppState(), ignoring = false)
        assertEquals(
            listOf(
                KeepAliveTarget.BATTERY_WHITELIST,
                KeepAliveTarget.AUTOSTART,
                KeepAliveTarget.BACKGROUND_SETTINGS
            ),
            s.steps()
        )
    }

    @Test
    fun tolerance_other_flag_values_are_not_treated_as_shown() = runBlocking {
        val dao = FakeAppState()
        dao.map[AppStateEntity.KEY_APP_LAUNCH_COUNT] = "2"
        dao.map[AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN] = "1" // 历史脏值
        val s = service(dao, ignoring = false)
        assertFalse(s.guideShown())
        assertTrue(s.shouldShowGuide())
    }

    // ---- 6.2 审查改进：弹窗时机（启动计数 + 通知权限） ----

    @Test
    fun record_launch_counts_and_defers_until_second_launch() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false, notifications = true)

        assertFalse(s.recordLaunchAndShouldGuide())   // 第 1 次启动：不弹
        assertEquals(1, s.launchCount())
        assertTrue(s.recordLaunchAndShouldGuide())    // 第 2 次启动：开始弹
        assertEquals(2, s.launchCount())
    }

    @Test
    fun record_launch_never_pops_without_notification_permission() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false, notifications = false)
        repeat(5) { assertFalse(s.recordLaunchAndShouldGuide()) }
        assertEquals(5, s.launchCount())
    }

    @Test
    fun record_launch_respects_whitelist_and_shown_flag() = runBlocking {
        val whitelisted = service(FakeAppState(), ignoring = true, notifications = true)
        repeat(3) { assertFalse(whitelisted.recordLaunchAndShouldGuide()) }

        val shown = service(FakeAppState(), ignoring = false, notifications = true)
        shown.markGuideShown()
        repeat(3) { assertFalse(shown.recordLaunchAndShouldGuide()) }
    }

    @Test
    fun launch_count_survives_and_is_separate_from_guide_flag() = runBlocking {
        val dao = FakeAppState()
        val s = service(dao, ignoring = false, notifications = true)
        s.recordLaunchAndShouldGuide()
        s.markGuideShown()
        assertEquals(1, s.launchCount())
        assertEquals("true", dao.map[AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN])
        assertEquals("1", dao.map[AppStateEntity.KEY_APP_LAUNCH_COUNT])
    }

    @Test
    fun app_state_keys_are_spelled_as_documented() {
        // 审查项：键名必须与注释/文档一致，别一个下划线写错就永远读不到
        assertEquals("keepalive_guide_shown", AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN)
        assertEquals("app_launch_count", AppStateEntity.KEY_APP_LAUNCH_COUNT)
        assertEquals(
            "keepalive_guide_shown",
            AppStateEntity.KEY_KEEPALIVE_GUIDE_SHOWN.trim().lowercase()
        )
    }
}
