package com.companion.chat.domain.settings

import com.companion.chat.domain.environment.EnvironmentRuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境感知设置的默认值 / 夹取 / 引擎映射。任务 V2.1.6。
 *
 * 这是「设置逻辑」里唯一能在主机上钉死的地方——`ConfigRepository` 依赖 Android prefs 跑不了，
 * 所以把会出错的部分都抽到了 [EnvironmentSettings]。
 */
class EnvironmentSettingsTest {

    // ---------- 默认值 ----------

    @Test
    fun defaults_match_spec() {
        val s = EnvironmentSettings()
        assertTrue(s.environmentEnabled)
        assertTrue(s.lateNightEnabled)
        assertEquals(23, s.lateNightHour)
        assertEquals(60, s.rainThreshold)
        assertEquals(5, s.tempDropThreshold)
    }

    // ---------- 夹取 ----------

    @Test
    fun sanitize_clamps_out_of_range() {
        val s = EnvironmentSettings(
            lateNightHour = 27,
            rainThreshold = 120,
            tempDropThreshold = -3,
        ).sanitized()

        assertEquals(23, s.lateNightHour)
        assertEquals(100, s.rainThreshold)
        assertEquals(EnvironmentSettings.MIN_TEMP_DROP_THRESHOLD, s.tempDropThreshold)
    }

    @Test
    fun sanitize_lower_bounds() {
        val s = EnvironmentSettings(
            lateNightHour = -5,
            rainThreshold = -10,
            tempDropThreshold = 0,     // 0 = 任何波动都触发，必须夹到 1
        ).sanitized()

        assertEquals(0, s.lateNightHour)
        assertEquals(0, s.rainThreshold)
        assertEquals(1, s.tempDropThreshold)
    }

    @Test
    fun sanitize_keeps_valid_values() {
        val s = EnvironmentSettings(
            lateNightHour = 1,
            rainThreshold = 99,
            tempDropThreshold = 8,
        ).sanitized()

        assertEquals(1, s.lateNightHour)
        assertEquals(99, s.rainThreshold)
        assertEquals(8, s.tempDropThreshold)
    }

    // ---------- 映射到引擎 ----------

    @Test
    fun to_rule_config_maps_editable_fields() {
        val s = EnvironmentSettings(
            lateNightHour = 22,
            rainThreshold = 70,
            tempDropThreshold = 6,
        )

        val cfg = s.toRuleConfig()

        assertEquals(22, cfg.lateNightHour)
        assertEquals(70, cfg.rainThreshold)
        assertEquals(6, cfg.tempDropThreshold)
        // 不可编辑的字段回落到引擎默认，不随用户设置漂移
        assertEquals(EnvironmentRuleConfig().nightEndHour, cfg.nightEndHour)
        assertEquals(EnvironmentRuleConfig().hotThreshold, cfg.hotThreshold)
        assertEquals(EnvironmentRuleConfig().mealtimeWindowMinutes, cfg.mealtimeWindowMinutes)
    }

    @Test
    fun to_rule_config_sanitizes_first() {
        val cfg = EnvironmentSettings(
            lateNightHour = 99,
            rainThreshold = 200,
            tempDropThreshold = 0,
        ).toRuleConfig()

        assertEquals(23, cfg.lateNightHour)
        assertEquals(100, cfg.rainThreshold)
        assertEquals(1, cfg.tempDropThreshold)
    }
}