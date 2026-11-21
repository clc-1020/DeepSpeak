package com.companion.chat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProviderPresets 单测：上下文窗口、DeepSeek 兼容判断、旧模型名迁移。任务 1.2 增补 */
class ProviderPresetsTest {

    @Test
    fun context_window() {
        assertEquals(1_000_000, ProviderPresets.defaultContextWindow("deepseek-flash"))
        assertEquals(1_000_000, ProviderPresets.defaultContextWindow("deepseek-v4-pro"))
        assertEquals(128000, ProviderPresets.defaultContextWindow("gpt-4o"))
    }

    @Test
    fun deepseek_compatible() {
        assertTrue(ProviderPresets.isDeepSeekCompatible(ProviderPresets.DEEPSEEK, "https://api.deepseek.com"))
        assertTrue(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://api.deepseek.com"))
        assertFalse(ProviderPresets.isDeepSeekCompatible(ProviderPresets.OPENAI, "https://api.openai.com/v1"))
        assertFalse(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://my-gateway.com/v1"))
    }

    @Test
    fun deepseek_compatible_strict_host() {
        // 只认解析后的 host，不应把相似域名/带参数误判为 deepseek
        assertFalse(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://notdeepseek.com/v1"))
        assertFalse(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://deepseek.com.evil.com/v1"))
        assertFalse(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://evil.com/?x=deepseek.com"))
        // 正确识别真正的 deepseek 域（含子域、缺 scheme）
        assertTrue(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "https://api.deepseek.com/v1"))
        assertTrue(ProviderPresets.isDeepSeekCompatible(ProviderPresets.CUSTOM, "deepseek.com"))
    }

    @Test
    fun legacy_model_migration() {
        assertEquals("deepseek-flash", ProviderPresets.migrateModel("deepseek-chat"))
        assertEquals("deepseek-flash", ProviderPresets.migrateModel("deepseek-reasoner"))
        assertEquals("deepseek-flash", ProviderPresets.migrateModel("deepseek-4.1-flash"))
        assertEquals("deepseek-flash", ProviderPresets.migrateModel("deepseek-v4-flash-vision-exp"))
        assertEquals("deepseek-flash", ProviderPresets.migrateModel("deepseek-flash"))
    }

    @Test
    fun default_vision_model_is_flash_for_deepseek() {
        assertEquals("deepseek-flash", ProviderPresets.defaultVisionModel(ProviderPresets.DEEPSEEK))
        assertEquals("gpt-4o", ProviderPresets.defaultVisionModel(ProviderPresets.OPENAI))
    }
}
