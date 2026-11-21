package com.companion.chat.data.environment

import android.content.SharedPreferences
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.entity.AppStateEntity

/**
 * 键值存储端口。任务 V2.1.1。
 *
 * 存在的唯一理由：**让环境层的判断逻辑能在主机上跑单测**。Room 的 `AppStateDao` 和
 * `SharedPreferences` 都依赖 Android 运行时，直接用它们会让 V2.1.1 的全部用例变成
 * instrumented 测试（本机没有可跑的设备上下文）。
 *
 * 两个实现各管一摊：
 * - [RoomKeyValueStore]：手动城市等**要长期活着**的状态（进 `app_state`）
 * - [SharedPrefsKeyValueStore]：天气缓存这种**随时可丢**的临时数据
 */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

/** `app_state` 表实现（手动城市、刷新日期）。任务 V2.1.1。 */
class RoomKeyValueStore(private val dao: AppStateDao) : KeyValueStore {

    override suspend fun get(key: String): String? = dao.get(key)

    override suspend fun put(key: String, value: String) {
        dao.put(AppStateEntity(key, value))
    }

    override suspend fun remove(key: String) {
        dao.delete(key)
    }
}

/** `SharedPreferences` 实现（天气缓存）。任务 V2.1.1。 */
class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    override suspend fun get(key: String): String? = prefs.getString(key, null)

    override suspend fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}