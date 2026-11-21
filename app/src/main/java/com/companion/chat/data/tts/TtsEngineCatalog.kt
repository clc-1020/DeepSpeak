package com.companion.chat.data.tts

import android.content.Context
import android.content.Intent

/**
 * 系统 TTS 引擎枚举（任务 4.1）：供设置页「引擎选择」+「无引擎提示」用。
 *
 * 通过 PackageManager 查询声明了 `android.intent.action.TTS_SERVICE` 的服务
 * （比创建 TextToSpeech 实例再 getEngines() 轻量、无初始化副作用）。失败返回空表（视为无引擎）。
 */
object TtsEngineCatalog {

    data class Info(val name: String, val label: String)

    /** 已安装的系统 TTS 引擎；查询失败返回空表。 */
    fun available(context: Context): List<Info> = try {
        val pm = context.packageManager
        pm.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            .mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
                val label = ri.serviceInfo.loadLabel(pm).toString().ifBlank { pkg }
                Info(pkg, label)
            }
            .distinctBy { it.name }
    } catch (_: Exception) {
        emptyList()
    }

    /** 是否有可用系统引擎（用于「请安装语音引擎」提示）。 */
    fun hasAnyEngine(context: Context): Boolean = available(context).isNotEmpty()
}