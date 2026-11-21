package com.companion.chat.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排程契约单测（审查项「KEEP 卡死周期调整」）。
 *
 * `enqueueUniquePeriodicWork(name, KEEP, request)` 在同名任务已存在时**直接忽略新请求**，
 * 不看 interval 是否变了。所以 unique work 名必须随「周期/版本」变化，否则改周期不生效。
 */
class ProactiveCheckWorkerScheduleTest {

    @Test
    fun work_name_carries_schema_and_period() {
        val name = ProactiveCheckWorker.WORK_NAME
        assertTrue("名字要含版本号：$name", name.contains("v${ProactiveCheckWorker.SCHEDULE_SCHEMA}"))
        assertTrue("名字要含周期：$name", name.contains("${ProactiveCheckWorker.PERIOD_HOURS}h"))
    }

    @Test
    fun current_name_is_not_in_legacy_list() {
        // 若当前名出现在黑名单里，启动时会被自己 cancel 掉
        assertFalse(
            ProactiveCheckWorker.LEGACY_WORK_NAMES.contains(ProactiveCheckWorker.WORK_NAME)
        )
    }

    @Test
    fun legacy_list_covers_old_2h_name() {
        // 1.0.3 及更早用的是不带版本的名字，必须能清掉，否则新旧两个周期任务同时跑
        assertTrue(ProactiveCheckWorker.LEGACY_WORK_NAMES.contains("proactive_check_2h"))
    }

    @Test
    fun fast_work_name_differs_from_production_name() {
        assertTrue(
            ProactiveCheckWorker.FAST_WORK_NAME != ProactiveCheckWorker.WORK_NAME
        )
    }

    @Test
    fun period_is_two_hours() {
        assertEquals(2L, ProactiveCheckWorker.PERIOD_HOURS)
    }
}