package com.companion.chat.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ProviderPresets
import com.companion.chat.domain.settings.DndSettings
import com.companion.chat.domain.proactive.ProactiveGateRules
import com.companion.chat.domain.settings.EnvironmentSettings

/**
 * API 配置持久化。任务 1.2
 *
 * 采用 EncryptedSharedPreferences 加密存储（含 API Key）；
 * 个别 ROM / Keystore 异常时降级为普通 SharedPreferences，保证功能可用。
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    fun load(): ApiConfig {
        val providerId = prefs.getString(KEY_PROVIDER, ProviderPresets.DEEPSEEK) ?: ProviderPresets.DEEPSEEK
        val baseUrl = prefs.getString(KEY_BASE_URL, ProviderPresets.defaultBaseUrl(providerId))
            ?: ProviderPresets.defaultBaseUrl(providerId)
        val rawModel = prefs.getString(KEY_MODEL, ProviderPresets.models(providerId).firstOrNull() ?: "deepseek-flash")
            ?: "deepseek-flash"
        // 旧模型名（deepseek-chat / deepseek-reasoner / deepseek-4.1-flash）兜底迁移为现役名
        val model = ProviderPresets.migrateModel(rawModel)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val contextWindow = prefs.getInt(KEY_CONTEXT_WINDOW, ProviderPresets.defaultContextWindow(model))
        // 识图模型归一：DeepSeek 只有 deepseek-flash 支持视觉，历史无效名一律纠正为它
        val visionModel = normalizeVisionModel(providerId, prefs.getString(KEY_VISION_MODEL, "") ?: "")
        return ApiConfig(
            providerId = providerId,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            contextWindow = contextWindow,
            visionModel = visionModel,
            thinkingEnabled = prefs.getBoolean(KEY_THINKING_ENABLED, false),
            deepSeekTtsEnabled = prefs.getBoolean(KEY_DEEPSEEK_TTS, false),
            ttsVoice = prefs.getString(KEY_TTS_VOICE, "stella") ?: "stella",
            latestReminderEnabled = prefs.getBoolean(KEY_LATEST_REMINDER, true),
            autoTitleEnabled = prefs.getBoolean(KEY_AUTO_TITLE, true),
            jsonModeDefault = prefs.getBoolean(KEY_JSON_MODE_DEFAULT, false)
        )
    }

    fun save(config: ApiConfig) {
        // 兜底：UI 若没带 visionModel，按服务商填默认识图模型，避免存成空导致带图失败；
        // DeepSeek 统一用 deepseek-flash（原生视觉），旧无效名一并纠正。
        val visionModel = normalizeVisionModel(config.providerId, config.visionModel)
        prefs.edit()
            .putString(KEY_PROVIDER, config.providerId)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_API_KEY, config.apiKey)
            .putInt(KEY_CONTEXT_WINDOW, config.contextWindow)
            .putString(KEY_VISION_MODEL, visionModel)
            .putBoolean(KEY_THINKING_ENABLED, config.thinkingEnabled)
            .putBoolean(KEY_DEEPSEEK_TTS, config.deepSeekTtsEnabled)
            .putString(KEY_TTS_VOICE, config.ttsVoice)
            .putBoolean(KEY_LATEST_REMINDER, config.latestReminderEnabled)
            .putBoolean(KEY_AUTO_TITLE, config.autoTitleEnabled)
            .putBoolean(KEY_JSON_MODE_DEFAULT, config.jsonModeDefault)
            .apply()
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    /**
     * 识图模型归一化：
     * - DeepSeek 只有 deepseek-flash 支持视觉（原生），历史无效名（deepseek-4.1-flash /
     *   deepseek-v4-flash-vision-exp 等）一律纠正为 deepseek-flash，避免带图发送用无效模型报错。
     * - 其它服务商：迁移已知旧名；空则取服务商默认（OpenAI → gpt-4o）。
     */
    private fun normalizeVisionModel(providerId: String, raw: String): String {
        if (providerId == ProviderPresets.DEEPSEEK) {
            return ProviderPresets.defaultVisionModel(ProviderPresets.DEEPSEEK)
        }
        return ProviderPresets.migrateModel(raw)
            .ifBlank { ProviderPresets.defaultVisionModel(providerId) }
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** 人设提示词（任务 1.5 会用界面编辑，这里先提供存取） */
    fun getPersona(): String = prefs.getString(KEY_PERSONA, "") ?: ""

    fun savePersona(persona: String) {
        prefs.edit().putString(KEY_PERSONA, persona).apply()
    }

    // ---------- 语音（TTS）设置。任务 4.1 ----------

    /** 语音总开关（默认开）。 */
    fun getTtsEnabled(): Boolean = prefs.getBoolean(KEY_TTS_ENABLED, true)
    fun setTtsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()

    /** 语速倍率（0.5~2.0，默认 1.0）。 */
    fun getTtsSpeechRate(): Float = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f)
    fun setTtsSpeechRate(rate: Float) =
        prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, rate.coerceIn(0.5f, 2.0f)).apply()

    /** 系统 TTS 引擎包名；null=默认引擎。 */
    fun getTtsSystemEngine(): String? = prefs.getString(KEY_TTS_SYSTEM_ENGINE, null)
    fun setTtsSystemEngine(name: String?) = prefs.edit().putString(KEY_TTS_SYSTEM_ENGINE, name).apply()

    // ---------- 语音输入（STT）设置。任务 V2.3.5 ----------

    /**
     * 语音输入总开关（默认开）。
     * 关闭后：麦克风按钮隐藏、长按不开麦、transcribe 直接返回「语音已关闭」。
     */
    fun getSttEnabled(): Boolean = prefs.getBoolean(KEY_STT_ENABLED, true)
    fun setSttEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_STT_ENABLED, enabled).apply()

    // ---------- 通知栏常驻快捷入口。任务 V2.3.6 ----------
    /** 常驻通知开关（默认关，计划书 §V2.3.6）。 */
    fun getQuickAccessEnabled(): Boolean = prefs.getBoolean(KEY_QUICK_ACCESS_ENABLED, false)
    fun setQuickAccessEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_QUICK_ACCESS_ENABLED, enabled).apply()

    // ---------- 主动消息语音 + 勿扰（任务 4.3） ----------

    /** 主动消息自动语音开关（默认关）。 */
    fun getProactiveVoiceEnabled(): Boolean = prefs.getBoolean(KEY_PROACTIVE_VOICE, false)
    fun setProactiveVoiceEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_PROACTIVE_VOICE, enabled).apply()

    /** 勿扰模式开关（默认关）。区间内的主动语音不念（通知仍发）。 */
    fun getDndEnabled(): Boolean = prefs.getBoolean(KEY_DND_ENABLED, false)
    fun setDndEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DND_ENABLED, enabled).apply()

    /** 勿扰开始（当天第几分钟，默认 23:00）。 */
    fun getDndStartMinute(): Int = prefs.getInt(KEY_DND_START_MINUTE, 23 * 60)
    fun setDndStartMinute(minute: Int) = prefs.edit().putInt(KEY_DND_START_MINUTE, minute).apply()

    /** 勿扰结束（当天第几分钟，默认 07:00）。 */
    fun getDndEndMinute(): Int = prefs.getInt(KEY_DND_END_MINUTE, 7 * 60)
    fun setDndEndMinute(minute: Int) = prefs.edit().putInt(KEY_DND_END_MINUTE, minute).apply()

    /**
     * 顶栏铃铛（一键勿扰）覆盖掉的**原始起点**快照（2026-11-20）。
     *
     * 铃铛开启时会把 [getDndStartMinute] 改成「现在」，好让它立刻生效；不存快照的话
     * 用户点一下就把自己配的 23:00 永久改成 22:47，再点关闭也回不去。null = 没被覆盖过。
     */
    fun getDndSavedStartMinute(): Int? =
        if (prefs.contains(KEY_DND_SAVED_START_MINUTE)) prefs.getInt(KEY_DND_SAVED_START_MINUTE, -1) else null

    fun setDndSavedStartMinute(minute: Int) =
        prefs.edit().putInt(KEY_DND_SAVED_START_MINUTE, minute).apply()

    fun clearDndSavedStart() = prefs.edit().remove(KEY_DND_SAVED_START_MINUTE).apply()

    /** 勿扰区间内是否**连通知都不发**（任务 7.2 独立开关，默认 true=彻底安静）。 */
    fun getDndSuppressNotification(): Boolean = prefs.getBoolean(KEY_DND_SUPPRESS_NOTIFICATION, true)
    fun setDndSuppressNotification(suppress: Boolean) =
        prefs.edit().putBoolean(KEY_DND_SUPPRESS_NOTIFICATION, suppress).apply()

    /**
     * 勿扰区间内是否**连用户自己设的指令提醒也压掉**（BUG-1，默认 **false**）。
     *
     * 默认关 = 闹钟准时。只有用户明确打开才顺延，避免「6:30 叫我吃早餐」变成 7:00 才响。
     */
    fun getDndSuppressInstruction(): Boolean = prefs.getBoolean(KEY_DND_SUPPRESS_INSTRUCTION, false)
    fun setDndSuppressInstruction(suppress: Boolean) =
        prefs.edit().putBoolean(KEY_DND_SUPPRESS_INSTRUCTION, suppress).apply()

    /** 汇总勿扰设置（任务 7.2）：一次读全，供闸门/送达/语音三处共用。 */
    fun getDndSettings(): DndSettings = DndSettings(
        enabled = getDndEnabled(),
        startMinute = getDndStartMinute(),
        endMinute = getDndEndMinute(),
        suppressNotification = getDndSuppressNotification(),
        suppressInstruction = getDndSuppressInstruction()
    )

    // ---------- 主动消息总开关 + 每日上限（任务 7.1） ----------

    /** 主动消息总开关（默认开）。关闭后自发消息不再生成（指令提醒仍照发）。 */
    fun getProactiveEnabled(): Boolean = prefs.getBoolean(KEY_PROACTIVE_ENABLED, true)
    fun setProactiveEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()

    /**
     * 每日自发消息上限（默认 8 = 与「每 2 小时一条」对齐；0 = 完全不自发，1..10 可调）。
     *
     * 早期默认 3 会让 2h 定时检查在第 4 次之后永久静默，实测表现为「主动消息根本不发」。
     */
    fun getProactiveDailyLimit(): Int =
        prefs.getInt(KEY_PROACTIVE_DAILY_LIMIT, ProactiveGateRules.DEFAULT_DAILY_LIMIT).coerceIn(0, 10)
    fun setProactiveDailyLimit(limit: Int) =
        prefs.edit().putInt(KEY_PROACTIVE_DAILY_LIMIT, limit.coerceIn(0, 10)).apply()

    // ---------- 环境感知（任务 V2.1.6） ----------

    /**
     * 读环境感知设置。
     *
     * 默认值与夹取规则都在 [EnvironmentSettings]（纯对象、有单测）；这里只做 prefs ↔ 值对象的搬运。
     * **手动城市不在其中**：它和 `manual_lat/lon` 一起存在 `app_state`，见 [EnvironmentSettings] 的说明。
     */
    fun getEnvironmentSettings(): EnvironmentSettings = EnvironmentSettings(
        environmentEnabled = prefs.getBoolean(
            EnvironmentSettings.KEY_ENVIRONMENT_ENABLED,
            EnvironmentSettings.DEFAULT_ENVIRONMENT_ENABLED
        ),
        lateNightEnabled = prefs.getBoolean(
            EnvironmentSettings.KEY_LATE_NIGHT_ENABLED,
            EnvironmentSettings.DEFAULT_LATE_NIGHT_ENABLED
        ),
        lateNightHour = prefs.getInt(
            EnvironmentSettings.KEY_LATE_NIGHT_HOUR,
            EnvironmentSettings.DEFAULT_LATE_NIGHT_HOUR
        ),
        rainThreshold = prefs.getInt(
            EnvironmentSettings.KEY_RAIN_THRESHOLD,
            EnvironmentSettings.DEFAULT_RAIN_THRESHOLD
        ),
        tempDropThreshold = prefs.getInt(
            EnvironmentSettings.KEY_TEMP_DROP_THRESHOLD,
            EnvironmentSettings.DEFAULT_TEMP_DROP_THRESHOLD
        ),
    ).sanitized()

    /** 保存环境感知设置（先夹取再落盘，避免把非法值写进去）。 */
    fun saveEnvironmentSettings(settings: EnvironmentSettings) {
        val v = settings.sanitized()
        prefs.edit()
            .putBoolean(EnvironmentSettings.KEY_ENVIRONMENT_ENABLED, v.environmentEnabled)
            .putBoolean(EnvironmentSettings.KEY_LATE_NIGHT_ENABLED, v.lateNightEnabled)
            .putInt(EnvironmentSettings.KEY_LATE_NIGHT_HOUR, v.lateNightHour)
            .putInt(EnvironmentSettings.KEY_RAIN_THRESHOLD, v.rainThreshold)
            .putInt(EnvironmentSettings.KEY_TEMP_DROP_THRESHOLD, v.tempDropThreshold)
            .apply()
        // 通知 App 重新同步整点闹钟（V2.1.5）：打开开关立即补排、关掉立即取消，
        // 都不必等进程重启。
        onEnvironmentSettingsChanged?.invoke()
    }

    /**
     * 环境设置变更回调（V2.1.5）。
     *
     * **为什么用回调而不是让 UI 线显式调 `syncEnvCheckSchedule()`**：
     * 设置页保存走的就是 [saveEnvironmentSettings]，在这里触发等于**所有保存入口全覆盖**，
     * 不会因为 UI 线漏接一行而留下「打开开关要重启才生效」的洞，也省掉一条跨线约定。
     *
     * 由 [com.companion.chat.CompanionApp.onCreate] 注册，回调内切到后台协程执行。
     */
    var onEnvironmentSettingsChanged: (() -> Unit)? = null

    companion object {
        private const val PREFS_NAME = "api_config_enc"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_CONTEXT_WINDOW = "context_window"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_PERSONA = "persona"
        private const val KEY_THINKING_ENABLED = "thinking_enabled"
        private const val KEY_DEEPSEEK_TTS = "deepseek_tts_enabled"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_STT_ENABLED = "stt_enabled"
        private const val KEY_QUICK_ACCESS_ENABLED = "quick_access_enabled"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_SYSTEM_ENGINE = "tts_system_engine"
        private const val KEY_PROACTIVE_VOICE = "proactive_voice_enabled"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_START_MINUTE = "dnd_start_minute"
        private const val KEY_DND_END_MINUTE = "dnd_end_minute"
    /** 铃铛一键勿扰覆盖前的原始起点；只有铃铛会写，设置页保存时清掉（用户已自己定过作息）。 */
    private const val KEY_DND_SAVED_START_MINUTE = "dnd_saved_start_minute"
        private const val KEY_DND_SUPPRESS_NOTIFICATION = "dnd_suppress_notification"
        private const val KEY_DND_SUPPRESS_INSTRUCTION = "dnd_suppress_instruction"
        private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        private const val KEY_PROACTIVE_DAILY_LIMIT = "proactive_daily_limit"
        private const val KEY_LATEST_REMINDER = "latest_reminder_enabled"
        private const val KEY_AUTO_TITLE = "auto_title_enabled"
        private const val KEY_JSON_MODE_DEFAULT = "json_mode_default"

        private fun createPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
