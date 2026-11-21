package com.companion.chat.domain.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 环境规则引擎 + 去重键纯函数单测。任务 V2.1.2。
 *
 * 全部在主机跑（无 Robolectric、无 IO、不读系统时间）。时间全部注入。
 */
class EnvironmentRuleEngineTest {

    private val engine = EnvironmentRuleEngine()
    private val cfg = EnvironmentRuleConfig()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 11, 20)

    /** 当天某时刻。 */
    private fun at(hour: Int, minute: Int): LocalDateTime = day.atTime(LocalTime.of(hour, minute))

    /** 当天某时刻的 epoch millis（上海时区）。 */
    private fun ms(hour: Int, minute: Int): Long =
        at(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 次日的 epoch millis。 */
    private fun msNextDay(hour: Int, minute: Int): Long =
        day.plusDays(1).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** 一个不含任何天气数据的快照（所有可空字段均为 null）。 */
    private fun emptyDay(date: LocalDate = day) = WeatherSnapshot(date = date)

    private fun eval(
        now: LocalDateTime,
        today: WeatherSnapshot? = null,
        yesterday: WeatherSnapshot? = null,
    ): List<EnvironmentHook> = engine.evaluate(now, today, yesterday, cfg)

    // ---------- 夜间边界 ----------

    @Test
    fun late_night_23_00_hits() {
        val hooks = eval(at(23, 0))
        assertEquals(listOf(EnvironmentHook.LateNight(23)), hooks)
    }

    @Test
    fun late_night_23_30_hits() {
        assertTrue(eval(at(23, 30)).any { it is EnvironmentHook.LateNight })
    }

    @Test
    fun late_night_00_30_hits() {
        assertEquals(listOf(EnvironmentHook.LateNight(0)), eval(at(0, 30)))
    }

    @Test
    fun late_night_04_59_hits() {
        assertEquals(listOf(EnvironmentHook.LateNight(4)), eval(at(4, 59)))
    }

    @Test
    fun late_night_22_59_misses() {
        assertTrue(eval(at(22, 59)).none { it is EnvironmentHook.LateNight })
    }

    @Test
    fun late_night_05_00_misses() {
        assertTrue(eval(at(5, 0)).none { it is EnvironmentHook.LateNight })
    }

    // ---------- 饭点 ----------

    @Test
    fun mealtime_11_30_is_lunch() {
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.Mealtime("lunch")), eval(at(11, 30)))
    }

    @Test
    fun mealtime_12_00_is_lunch() {
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.Mealtime("lunch")), eval(at(12, 0)))
    }

    @Test
    fun mealtime_12_29_is_lunch() {
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.Mealtime("lunch")), eval(at(12, 29)))
    }

    /** 右开区间：12:30 已出窗，否则午饭尾巴和下午连成一片。 */
    @Test
    fun mealtime_12_30_not_lunch() {
        assertTrue(eval(at(12, 30)).none { it is EnvironmentHook.Mealtime })
    }

    @Test
    fun mealtime_11_29_not_lunch() {
        assertTrue(eval(at(11, 29)).none { it is EnvironmentHook.Mealtime })
    }

    @Test
    fun mealtime_18_00_is_dinner() {
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.Mealtime("dinner")), eval(at(18, 0)))
    }

    @Test
    fun mealtime_18_30_not_dinner() {
        assertTrue(eval(at(18, 30)).none { it is EnvironmentHook.Mealtime })
    }

    // ---------- 天气三阈值（严格大于才触发） ----------

    /** 阈值 60：59 不触发、60 不触发（等于不算）、61 触发。基准时刻 10:00 避开饭点/熬夜。 */
    @Test
    fun rain_threshold_60_is_strict() {
        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, precipProbabilityMax = 59))
            .none { it is EnvironmentHook.Rain })
        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, precipProbabilityMax = 60))
            .none { it is EnvironmentHook.Rain })
        assertEquals(
            listOf<EnvironmentHook>(EnvironmentHook.Rain(61)),
            eval(at(10, 0), today = WeatherSnapshot(day, precipProbabilityMax = 61)),
        )
    }

    /** 阈值 5：降 4 度不触发、降 5 度不触发、降 6 度触发。 */
    @Test
    fun temp_drop_threshold_5_is_strict() {
        val yesterday = WeatherSnapshot(day.minusDays(1), tempMin = 20)

        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, tempMin = 16), yesterday = yesterday)
            .none { it is EnvironmentHook.TempDrop })
        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, tempMin = 15), yesterday = yesterday)
            .none { it is EnvironmentHook.TempDrop })
        assertEquals(
            listOf<EnvironmentHook>(EnvironmentHook.TempDrop(6)),
            eval(at(10, 0), today = WeatherSnapshot(day, tempMin = 14), yesterday = yesterday),
        )
    }

    /** 阈值 35：34 不触发、35 不触发、36 触发。 */
    @Test
    fun hot_threshold_35_is_strict() {
        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, tempMax = 34))
            .none { it is EnvironmentHook.HighTemp })
        assertTrue(eval(at(10, 0), today = WeatherSnapshot(day, tempMax = 35))
            .none { it is EnvironmentHook.HighTemp })
        assertEquals(
            listOf<EnvironmentHook>(EnvironmentHook.HighTemp(36)),
            eval(at(10, 0), today = WeatherSnapshot(day, tempMax = 36)),
        )
    }

    /** 升温（昨天更冷）不算降温，别把 delta 算反。 */
    @Test
    fun temp_rise_does_not_trigger_temp_drop() {
        val hooks = eval(
            at(10, 0),
            today = WeatherSnapshot(day, tempMin = 25),
            yesterday = WeatherSnapshot(day.minusDays(1), tempMin = 10),
        )
        assertTrue(hooks.none { it is EnvironmentHook.TempDrop })
    }

    // ---------- 排序 ----------

    /** 同时满足熬夜 + 下雨 + 高温 → 严格按 [LateNight, Rain, HighTemp]（调用方取第一条）。 */
    @Test
    fun order_late_night_rain_hot() {
        val hooks = eval(
            at(23, 0),
            today = WeatherSnapshot(day, tempMax = 38, precipProbabilityMax = 80),
        )
        assertEquals(
            listOf(
                EnvironmentHook.LateNight(23),
                EnvironmentHook.Rain(80),
                EnvironmentHook.HighTemp(38),
            ),
            hooks,
        )
    }

    /** 全条件齐开时完整顺序：熬夜 > 带伞 > 添衣 > 饭点 > 高温。基准 12:00 取饭点又避开熬夜。 */
    @Test
    fun order_full_chain() {
        val hooks = eval(
            at(12, 0),
            today = WeatherSnapshot(day, tempMax = 38, tempMin = 10, precipProbabilityMax = 80),
            yesterday = WeatherSnapshot(day.minusDays(1), tempMin = 20),
        )
        assertEquals(
            listOf(
                EnvironmentHook.Rain(80),
                EnvironmentHook.TempDrop(10),
                EnvironmentHook.Mealtime("lunch"),
                EnvironmentHook.HighTemp(38),
            ),
            hooks,
        )
    }

    // ---------- 缺数据 ----------

    /** today=null → 只返回时间类钩子。 */
    @Test
    fun today_null_returns_only_time_hooks() {
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.Mealtime("lunch")), eval(at(12, 0), today = null))
        assertEquals(listOf<EnvironmentHook>(EnvironmentHook.LateNight(23)), eval(at(23, 0), today = null))
    }

    /** yesterday=null → 算不出 delta，不返回 TempDrop（不许拿 0 当基准）。 */
    @Test
    fun yesterday_null_no_temp_drop() {
        val hooks = eval(at(10, 0), today = WeatherSnapshot(day, tempMin = 5), yesterday = null)
        assertTrue(hooks.none { it is EnvironmentHook.TempDrop })
    }

    /** 字段缺失 → 对应规则跳过，其余规则照常。 */
    @Test
    fun missing_fields_skip_only_their_rule() {
        val hooks = eval(at(10, 0), today = emptyDay())
        assertTrue(hooks.isEmpty())
    }

    // ---------- stableId ----------

    @Test
    fun stable_ids() {
        assertEquals("late_night", EnvironmentHook.LateNight(23).stableId)
        assertEquals("rain", EnvironmentHook.Rain(80).stableId)
        assertEquals("temp_drop", EnvironmentHook.TempDrop(8).stableId)
        assertEquals("high_temp", EnvironmentHook.HighTemp(38).stableId)
        assertEquals("mealtime_lunch", EnvironmentHook.Mealtime("lunch").stableId)
        assertEquals("mealtime_dinner", EnvironmentHook.Mealtime("dinner").stableId)
        // 午/晚 stableId 必须不同，否则晚饭会因午饭已记账而被去重掉
        assertNotEquals(
            EnvironmentHook.Mealtime("lunch").stableId,
            EnvironmentHook.Mealtime("dinner").stableId,
        )
    }

    // ---------- dateKey ----------

    /** 熬夜按夜计：23:30 与次日 00:30 的 dateKey 必须相同，否则一晚发两条。 */
    @Test
    fun late_night_date_key_same_night() {
        val night = EnvironmentHook.LateNight(23).dateKey(ms(23, 30), zone)
        val afterMidnight = EnvironmentHook.LateNight(0).dateKey(msNextDay(0, 30), zone)

        assertEquals(night, afterMidnight)
        assertEquals("2026-11-20", night)
    }

    /** 夜的另一端：凌晨 05:00 起算新的一天（不是 04:00）。 */
    @Test
    fun late_night_date_key_rolls_at_05_00() {
        val beforeRoll = EnvironmentHook.LateNight(4).dateKey(msNextDay(4, 59), zone)
        val atRoll = EnvironmentHook.LateNight(5).dateKey(msNextDay(5, 0), zone)

        assertEquals("2026-11-20", beforeRoll)
        assertEquals("2026-11-21", atRoll)
    }

    /**
     * 🔴 关键回归：04:30 与 23:30 必须**同一夜**。
     *
     * 熬夜窗口是 `hour >= 23 || hour < 5`（23:00–04:59）。若去重键按 `now - 4h` 算，
     * `04:30 - 4h = 00:30` 落在当天，同一夜被劈成 `11-20` 和 `11-21` 两个键——
     * 整点闹钟 04:00 一响，`recall()` 查新键查不到，就会再发一条「该睡了」。
     */
    @Test
    fun late_night_0430_same_night_as_2330() {
        val first = EnvironmentHook.LateNight(23).dateKey(ms(23, 30), zone)
        val late = EnvironmentHook.LateNight(4).dateKey(msNextDay(4, 30), zone)
        val justBeforeEnd = EnvironmentHook.LateNight(4).dateKey(msNextDay(4, 59), zone)

        assertEquals("2026-11-20", first)
        assertEquals(first, late)          // 04:30 同夜
        assertEquals(first, justBeforeEnd) // 04:59 同夜（窗口最后时刻）
    }

    /**
     * 其余钩子按当天。
     *
     * 过午夜后两者才分叉：次日 00:30 时 `Rain` 已是新的一天（21 日），而熬夜仍停在「今晚」（20 日）。
     * 若在 23:30 比较，两者本来就相等——差异只在跨日时刻体现。
     */
    @Test
    fun rain_date_key_is_today() {
        assertEquals("2026-11-20", EnvironmentHook.Rain(80).dateKey(ms(23, 30), zone))
        assertEquals("2026-11-21", EnvironmentHook.Rain(80).dateKey(msNextDay(0, 30), zone))
        assertNotEquals(
            EnvironmentHook.Rain(80).dateKey(msNextDay(0, 30), zone),
            EnvironmentHook.LateNight(0).dateKey(msNextDay(0, 30), zone),
        )
    }

    /** [EnvironmentHooks.dateKeyOf] 必须与实例方法逐字一致，否则查键/记账分叉 → 去重失效。 */
    @Test
    fun date_key_of_matches_instance_method() {
        assertEquals(
            EnvironmentHook.LateNight(23).dateKey(ms(23, 30), zone),
            EnvironmentHooks.dateKeyOf(EnvironmentHooks.LATE_NIGHT, ms(23, 30), zone),
        )
        assertEquals(
            EnvironmentHook.Rain(80).dateKey(ms(23, 30), zone),
            EnvironmentHooks.dateKeyOf("rain", ms(23, 30), zone),
        )
        // 20:00 也属于同一夜（now-4h = 16:00 当天），覆盖「夜」的左边界
        assertEquals(
            EnvironmentHooks.dateKeyOf(EnvironmentHooks.LATE_NIGHT, ms(23, 30), zone),
            EnvironmentHooks.dateKeyOf(EnvironmentHooks.LATE_NIGHT, ms(20, 0), zone),
        )
    }

    /**
     * 其余 5 个钩子（含两个饭点）的 dateKey 都走基类默认实现 = **当天**。
     *
     * 守的是「想覆写但写错」这类事故：Kotlin 只保证 `override` 关键字不能漏，
     * 保证不了覆写内容正确（比如照抄熬夜的 −4h）。
     *
     * **必须在午夜之后断言，否则这条测试是假的**：`23:30 − 4h = 19:30`、`10:00 − 4h = 06:00`，
     * 都还在同一天，错抄了偏移也照样通过。只有 `次日 00:30 − 4h = 当日 20:30` 才会跨日，
     * 那时正确的实现应当返回**新的当天**（11-21），错的实现在这里会返回 11-20。
     */
    @Test
    fun date_key_defaults_to_today_for_non_night_hooks() {
        val lateEvening = ms(23, 30)
        assertEquals("2026-11-20", EnvironmentHook.Rain(80).dateKey(lateEvening, zone))
        assertEquals("2026-11-20", EnvironmentHook.TempDrop(8).dateKey(lateEvening, zone))
        assertEquals("2026-11-20", EnvironmentHook.HighTemp(38).dateKey(lateEvening, zone))
        assertEquals("2026-11-20", EnvironmentHook.Mealtime("lunch").dateKey(lateEvening, zone))
        assertEquals("2026-11-20", EnvironmentHook.Mealtime("dinner").dateKey(lateEvening, zone))

        // 午夜之后：跨日时刻，能真正验出「误抄 −4h」
        val afterMidnight = msNextDay(0, 30)
        assertEquals("2026-11-21", EnvironmentHook.Rain(80).dateKey(afterMidnight, zone))
        assertEquals("2026-11-21", EnvironmentHook.TempDrop(8).dateKey(afterMidnight, zone))
        assertEquals("2026-11-21", EnvironmentHook.HighTemp(38).dateKey(afterMidnight, zone))
        assertEquals("2026-11-21", EnvironmentHook.Mealtime("lunch").dateKey(afterMidnight, zone))
        assertEquals("2026-11-21", EnvironmentHook.Mealtime("dinner").dateKey(afterMidnight, zone))

        // 与熬夜形成对照：同一时刻熬夜仍停在「今晚」（11-20）
        assertEquals("2026-11-20", EnvironmentHook.LateNight(0).dateKey(afterMidnight, zone))
    }

    /**
     * 引擎只会产出 `lunch` / `dinner` 两种饭点 type。
     *
     * `Mealtime.type` 刻意保持自由字符串（§3 契约），因为**没有外部反序列化路径**：
     * 队列里存的是 `stableId`，编码成 `env:mealtime_lunch` 纯字符串，`Mealtime` 对象从不落库。
     * 所以不必收紧成枚举；这条测试守住「引擎不会产出第三种值」这个唯一实际不变量。
     */
    @Test
    fun mealtime_types_are_only_lunch_and_dinner() {
        val types = (0..23).flatMap { hour ->
            listOf(0, 29, 30, 59).flatMap { minute ->
                eval(at(hour, minute))
                    .filterIsInstance<EnvironmentHook.Mealtime>()
                    .map { it.type }
            }
        }.toSet()
        assertEquals(setOf("lunch", "dinner"), types)
    }
}