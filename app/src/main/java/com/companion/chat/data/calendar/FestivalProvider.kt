package com.companion.chat.data.calendar

import android.content.Context
import com.companion.chat.domain.calendar.Festival
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.time.MonthDay

/**
 * 加载内置节日表。任务 V2.1.3。
 *
 * 数据源是 `assets/calendar/festivals_2026_2036.json`（**构建期用农历库生成、提交入库**，
 * App 运行时只读，不依赖任何农历计算）。
 *
 * 解析用 **Gson**（`com.google.gson`）而不是 `org.json`：后者是 Android SDK 类，纯 JVM 单测
 * 里会抛 `not mocked`（本项目全部测试都是纯 JVM，无 Robolectric）。Gson 已在依赖里
 * （天气解析也在用），且纯 JVM 可跑。
 *
 * 默认 asset 路径是「debug 与 release 同源」的简单方案；测试时用 [FestivalProvider(loader)]
 * 注入自定义加载器，避免依赖真 asset（本机单测没有 assets）。
 *
 * @param assetPath asset 内路径，默认 `calendar/festivals_2026_2036.json`
 */
class FestivalProvider(
    private val loader: () -> String,
    private val assetPath: String = DEFAULT_ASSET_PATH,
) {

    constructor(context: Context, assetPath: String = DEFAULT_ASSET_PATH) : this(
        loader = {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        },
        assetPath = assetPath,
    )

    /** 惰性解析一次（asset 是只读常量，反复解析纯浪费）。 */
    private val festivals: List<Festival> by lazy {
        parse(loader())
    }

    /**
     * 指定日期的全部节日。
     *
     * 用 [Festival.occursOn]（农历精确匹配、公历按月日）。同一天可能命中多个
     * （比如某年国庆恰逢中秋），所以返回列表；调用方按需取第一条。
     */
    fun getTodayFestivals(date: java.time.LocalDate): List<Festival> =
        festivals.filter { it.occursOn(date) }

    /** 解析失败抛 [IllegalStateException]——asset 是打包进 APK 的，坏掉是构建事故，不该静默。 */
    private fun parse(json: String): List<Festival> = try {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IllegalArgumentException("root not object")

        val out = ArrayList<Festival>(80) // 6×11 农历 + 3 公历 = 69，给足余量避免扩容拷贝

        val lunar = root.asJsonObject.getAsJsonArray("lunar")
        if (lunar != null) {
            for (el in lunar) {
                val obj = el.asJsonObject
                val name = obj.get("name").asString
                val dates = obj.getAsJsonArray("dates")
                for (date in dates) {
                    out += Festival.lunar(name, date.asString)
                }
            }
        }

        val solar = root.asJsonObject.getAsJsonArray("solar")
        if (solar != null) {
            for (el in solar) {
                val obj = el.asJsonObject
                out += Festival.solar(
                    obj.get("name").asString,
                    obj.get("month").asInt,
                    obj.get("day").asInt,
                )
            }
        }
        out
    } catch (e: Exception) {
        throw IllegalStateException("festivals asset 解析失败: ${e.message}", e)
    }

    companion object {
        const val DEFAULT_ASSET_PATH = "calendar/festivals_2026_2036.json"
    }
}