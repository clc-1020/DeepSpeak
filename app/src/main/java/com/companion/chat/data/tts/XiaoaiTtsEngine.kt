package com.companion.chat.data.tts

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay

/**
 * 小爱同学音色通道。
 *
 * 原理：本类**不直接**调用小爱，而是给手机上的 Xposed 桥模块
 * （`com.companion.xposedprobe`，见 `samples/xposed_probe/`）发一条私有广播；
 * 模块注入在 `com.miui.voiceassist` 进程里，反射调用小爱内部
 * `com.xiaomi.voiceassistant.definevendor.t0#speakTts(String)`，
 * 从而用**当前选中的音色**（含"由他人分享"的第三方音色，如洛琪希）合成并播放。
 *
 * 为什么不用标准 TTS API：小爱没有注册 `android.intent.action.TTS_SERVICE`，
 * 它是 `VoiceInteractionService`；其 TTS 走 AIVS/思必驰云端合成（内部协议 + 加密 token），
 * 且 `TTSVendorActivity` 是 `exported=false`。桥模块是目前唯一可行的入口。
 *
 * 已知限制（详见 samples/xposed_probe/README.md）：
 * - 音频走 `AUDIO_USAGE_ASSISTANT`，音量/路由不受本 App 控制；
 * - 小爱主进程被杀后广播无人接收（[isAvailable] 只能判断"装没装"，判断不了"进程在不在"）；
 * - 模块暂无"播完"回调，本类按中文语速**估时**挂起，避免下一条打断。
 */
class XiaoaiTtsEngine(
    private val context: Context,
    private val speechRateProvider: () -> Float = { 1.0f }
) : TtsEngine {

    @Volatile
    private var speaking = false

    override fun isAvailable(): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun speak(text: String): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        if (!isAvailable()) return Result.failure(IllegalStateException("小爱同学未安装"))
        context.sendBroadcast(
            Intent(ACTION).putExtra(EXTRA_TEXT, text).setPackage(PKG)
        )
        speaking = true
        // 模块没有 done 回调：按 4.5 字/秒估时，语速倍率越大越短
        val rate = speechRateProvider().coerceIn(0.5f, 2.0f)
        delay((text.length * MS_PER_CHAR / rate).toLong() + TAIL_MS)
        speaking = false
        return Result.success(Unit)
    }

    override fun stop() {
        if (!speaking) return
        context.sendBroadcast(
            Intent(ACTION).putExtra(EXTRA_CMD, "stop").setPackage(PKG)
        )
        speaking = false
    }

    companion object {
        /** 与 Xposed 桥模块约定的广播 action（模块侧 HookTask.ACTION）。 */
        const val ACTION = "com.companion.xposedprobe.CMD"
        const val PKG = "com.miui.voiceassist"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CMD = "cmd"

        /** 中文正常语速约 4.5 字/秒 → 每字约 220ms。 */
        const val MS_PER_CHAR = 220L

        /** 收尾余量：等小爱把最后一帧放完。 */
        const val TAIL_MS = 500L
    }
}