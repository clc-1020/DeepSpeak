package com.companion.chat.data.environment

/**
 * 坐标 → 城市名。2026-11-22（任务：自动定位也能填进注记第 3 段）。
 *
 * 为什么需要它：`latest_reminder` 的第 3 段「地点」原先只填**手填城市名**
 * （`LocationCache.manualCityName()`），自动定位模式下那段永远是空的 ——
 * 模型能报天气，却答不出「你在哪座城市」。
 *
 * 设计约束：
 * - **绝不在发送路径调用**。反查基本都要联网（系统 Geocoder 走服务商后端），
 *   放在点发送那一刻就是 1.1.0 那个「消息半天不上屏」的坑重演。
 * - 只由「每天首次前台刷新定位」在后台顺手调一次，结果存进 `app_state`，
 *   发送路径只读缓存。
 * - 拿不到（Geocoder 不可用 / 无网络 / 无该地地名）一律返回 null，**绝不抛**。
 */
interface CityNamePort {

    /**
     * @return 城市级地名（如「佛山市」）；失败返回 null —— 此时注记第 3 段保持空，
     *   与改动前行为一致。
     */
    suspend fun nameOf(point: GeoPoint): String?
}