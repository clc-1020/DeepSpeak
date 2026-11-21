package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关系阶段：三档阈值（1.9.3.2 收紧）、撒娇开关、想你门槛、关系定位文案。 */
class RelationshipStageTest {

    @Test
    fun new_user_is_stranger() {
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.of(RelationshipStage.Stats(3, 1)))
        assertFalse(RelationshipStage.allowsAffection(RelationshipStage.STRANGER))
    }

    @Test
    fun sixty_messages_alone_makes_familiar() {
        // 消息 ≥60 单独达标，不看认识天数
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(60, 1)))
        assertFalse("熟悉还不够撒娇", RelationshipStage.allowsAffection(RelationshipStage.FAMILIAR))
    }

    @Test
    fun two_days_with_thirty_messages_makes_familiar() {
        // 第 2 天 + 消息 ≥30 的双条件路径
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(30, 2)))
    }

    @Test
    fun below_sixty_messages_on_day_one_is_stranger() {
        // 消息差一条到 60，且天数 <2 → 生疏
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.of(RelationshipStage.Stats(59, 1)))
    }

    @Test
    fun two_days_with_too_few_messages_is_stranger() {
        // 1.9.3.2 收紧点：原来「第 2 天就熟悉」，现在还要消息 ≥30
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.of(RelationshipStage.Stats(29, 2)))
    }

    @Test
    fun close_needs_both_messages_and_days() {
        assertEquals(RelationshipStage.CLOSE, RelationshipStage.of(RelationshipStage.Stats(200, 5)))
        assertTrue(RelationshipStage.allowsAffection(RelationshipStage.CLOSE))
        // 消息够 200 但只认了 4 天 → 还不算亲近（1.9.3.2 从 3 天收紧到 5 天）
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(200, 4)))
        // 消息够多但只认了一天 → 熟悉但不亲近
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(500, 1)))
    }

    @Test
    fun missing_floor_shrinks_as_relationship_grows() {
        assertTrue(
            RelationshipStage.missingFloorHours(RelationshipStage.CLOSE) <
                RelationshipStage.missingFloorHours(RelationshipStage.FAMILIAR)
        )
        assertTrue(
            RelationshipStage.missingFloorHours(RelationshipStage.FAMILIAR) <
                RelationshipStage.missingFloorHours(RelationshipStage.STRANGER)
        )
    }

    @Test
    fun prompt_hint_differs_by_stage() {
        assertTrue(RelationshipStage.promptHint(RelationshipStage.CLOSE).contains("亲近"))
        assertTrue(RelationshipStage.promptHint(RelationshipStage.STRANGER).contains("礼貌"))
    }

    // ---- 1.9.4.1：关系 → 主动消息间隔（小时区间 + 随机但从上次说话时刻起算且稳定）----

    @Test
    fun proactive_interval_range_shrinks_as_closer() {
        assertEquals(3..4, RelationshipStage.proactiveIntervalHoursRange(RelationshipStage.STRANGER))
        assertEquals(2..4, RelationshipStage.proactiveIntervalHoursRange(RelationshipStage.FAMILIAR))
        assertEquals(2..3, RelationshipStage.proactiveIntervalHoursRange(RelationshipStage.CLOSE))
    }

    @Test
    fun proactive_gap_is_within_range_and_stable_for_same_seed() {
        for (stage in listOf(RelationshipStage.STRANGER, RelationshipStage.FAMILIAR, RelationshipStage.CLOSE)) {
            val r = RelationshipStage.proactiveIntervalHoursRange(stage)
            repeat(20) { seed ->
                val gap = RelationshipStage.proactiveGapMillis(stage, seed.toLong() * 7_919L + 1)
                val hours = gap / 3_600_000L
                assertTrue("$stage 间隔 ${hours}h 落在 $r 内", hours.toInt() in r)
                // 同一 seed 结果必须一致（不会每 2h 检查一次就重掷）
                assertEquals(gap, RelationshipStage.proactiveGapMillis(stage, seed.toLong() * 7_919L + 1))
            }
        }
    }

    @Test
    fun closer_stage_never_waits_longer_than_stranger() {
        // 每个种子上，亲近的最大间隔(3h) <= 生疏的最小间隔(3h) 附近，保证整体更勤
        val strangerMax = RelationshipStage.proactiveIntervalHoursRange(RelationshipStage.STRANGER).last
        val closeMax = RelationshipStage.proactiveIntervalHoursRange(RelationshipStage.CLOSE).last
        assertTrue("亲近最长间隔应 <= 生疏最长间隔", closeMax <= strangerMax)
    }
}
