package com.companion.chat.domain.power

/**
 * 国产 ROM 家族识别（任务 6.2）。纯逻辑，可 JVM 单测。
 *
 * 输入 `Build.MANUFACTURER` / `Build.BRAND`（归一化到小写再匹配），输出厂商设置页家族，
 * 用于挑选「自启动管理 / 后台运行」跳转目标。
 */
enum class RomFamily {
    MIUI,        // 小米 / 红米 / POCO
    HUAWEI,      // 华为 / 荣耀（EMUI / HarmonyOS）
    COLOROS,     // OPPO / realme
    ONEPLUS,     // 一加（ColorOS/OxygenOS，另有一加自己的链式启动页）
    VIVO,        // vivo / iQOO
    FLYME,       // 魅族
    SAMSUNG,     // 三星
    GENERIC;     // 其它（原生 / 国际版 / 识别不出）

    companion object {

        /**
         * 识别 ROM 家族。
         *
         * 两个入参都可能为 null/空（非 Android 环境或异常），一律回落 [GENERIC]，绝不抛错。
         */
        fun detect(manufacturer: String?, brand: String?): RomFamily {
            val m = manufacturer?.trim()?.lowercase().orEmpty()
            val b = brand?.trim()?.lowercase().orEmpty()
            val hay = "$m $b"
            return when {
                hay.isBlank() -> GENERIC
                containsAny(hay, "xiaomi", "redmi", "poco", "miui") -> MIUI
                containsAny(hay, "huawei", "honor", "harmony") -> HUAWEI
                containsAny(hay, "oneplus") -> ONEPLUS
                containsAny(hay, "oppo", "realme", "coloros") -> COLOROS
                containsAny(hay, "vivo", "iqoo") -> VIVO
                containsAny(hay, "meizu", "flyme") -> FLYME
                containsAny(hay, "samsung") -> SAMSUNG
                else -> GENERIC
            }
        }

        private fun containsAny(hay: String, vararg needles: String): Boolean =
            needles.any { hay.contains(it) }
    }
}