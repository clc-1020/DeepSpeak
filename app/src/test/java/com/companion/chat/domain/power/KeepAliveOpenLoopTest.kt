package com.companion.chat.domain.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保活候选链「逐条 try、失败继续」循环单测。任务 6.2 审查改进 */
class KeepAliveOpenLoopTest {

    private val miui = KeepAliveIntents.autostart(RomFamily.MIUI)
    private val chain = KeepAliveIntents.specsFor(KeepAliveTarget.AUTOSTART, RomFamily.MIUI, "com.companion.chat")

    @Test
    fun returns_first_success_and_stops_trying() {
        val tried = mutableListOf<String>()
        val winner = KeepAliveIntents.openLoop(chain, { spec ->
            tried.add(spec.label)
            true
        })
        assertEquals(chain.first(), winner)
        assertEquals(1, tried.size) // 成功后不再试后面的候选
    }

    @Test
    fun continues_after_failure_and_returns_the_working_candidate() {
        val tried = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val winner = KeepAliveIntents.openLoop(
            chain,
            { spec ->
                tried.add(spec.label)
                tried.size == 2 // 只有第二条可用
            },
            log = { logs.add(it) }
        )
        assertEquals(chain[1], winner)
        assertEquals(2, tried.size)
        assertTrue(logs.any { it.contains("不可用") })
        assertTrue(logs.any { it.contains("跳转成功") })
    }

    @Test
    fun all_candidates_failing_returns_null_and_logs_every_attempt() {
        val logs = mutableListOf<String>()
        val winner = KeepAliveIntents.openLoop(chain, { false }, log = { logs.add(it) })
        assertNull(winner)
        assertTrue(logs.any { it.contains("全部 ${chain.size} 条候选都失败") })
        // 每条候选各一条失败日志 + 末尾一条总结
        assertEquals(chain.size + 1, logs.size)
    }

    @Test
    fun attempt_exception_is_contained_and_loop_continues() {
        val logs = mutableListOf<String>()
        val winner = KeepAliveIntents.openLoop(
            chain,
            { spec ->
                if (spec == chain.first()) throw SecurityException("not exported")
                true
            },
            log = { logs.add(it) }
        )
        assertEquals(chain[1], winner) // 第一条抛异常，第二条接手
        assertTrue(logs.any { it.contains("抛异常") && it.contains("SecurityException") })
    }

    @Test
    fun empty_chain_returns_null_without_crashing() {
        val logs = mutableListOf<String>()
        assertNull(KeepAliveIntents.openLoop(emptyList(), { true }, log = { logs.add(it) }))
        assertEquals(listOf("候选链为空"), logs)
    }

    @Test
    fun every_vendor_chain_can_be_walked_to_its_fallback() {
        // 全 ROM × 目标：前 N-1 条都失败时，必须能走到最后的「应用详情页」兜底
        for (rom in RomFamily.values()) {
            for (target in KeepAliveTarget.values()) {
                val specs = KeepAliveIntents.specsFor(target, rom, "com.companion.chat")
                val winner = KeepAliveIntents.openLoop(
                    specs,
                    { spec -> spec.action == KeepAliveIntents.ACTION_APPLICATION_DETAILS_SETTINGS }
                )
                assertEquals("$rom/$target 没能落到详情页兜底", specs.last(), winner)
            }
        }
    }

    @Test
    fun label_is_readable_for_explicit_and_implicit_specs() {
        assertTrue(chain.first().label.contains("com.miui.securitycenter/"))
        val implicit = IntentSpec(KeepAliveIntents.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        assertEquals(KeepAliveIntents.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, implicit.label)
        assertTrue(implicit.withData("package:x").label.contains("package:x"))
    }

    @Test
    fun miui_autostart_chain_is_reachable_in_order() {
        // 自启动链的第一条必须是小��的自启动管理页（顺序即优先级）
        assertEquals("com.miui.securitycenter", miui.first().packageName)
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            miui.first().className
        )
    }
}