package com.companion.chat.domain.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关系阶段（P3）：三档阈值、撒娇开关、想你门槛、关系定位文案。 */
class RelationshipStageTest {

    @Test
    fun new_user_is_stranger() {
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.of(RelationshipStage.Stats(3, 1)))
        assertFalse(RelationshipStage.allowsAffection(RelationshipStage.STRANGER))
    }

    @Test
    fun thirty_messages_makes_familiar() {
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(30, 1)))
        assertFalse("熟悉还不够撒娇", RelationshipStage.allowsAffection(RelationshipStage.FAMILIAR))
    }

    @Test
    fun third_day_alone_makes_familiar() {
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.of(RelationshipStage.Stats(5, 3)))
    }

    @Test
    fun close_needs_both_messages_and_days() {
        assertEquals(RelationshipStage.CLOSE, RelationshipStage.of(RelationshipStage.Stats(200, 3)))
        assertTrue(RelationshipStage.allowsAffection(RelationshipStage.CLOSE))
        // 消息够多但只认了一天 → 还不算亲近
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
}