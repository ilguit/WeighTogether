package com.example.huaweimisync.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReplayPlanTest {
    @Test
    fun exactReplayKeepsOriginalDeadlineAndEnsuresFinalizationWatchdog() {
        val plan = pendingReplayPlan(exactReplay = true, isBeforeDeadline = true)

        assertFalse(plan.shouldSlideDeadline)
        assertTrue(plan.shouldScheduleFinalization)
    }

    @Test
    fun nearbyDistinctPacketRetainsSlidingDeduplicationPolicy() {
        val plan = pendingReplayPlan(exactReplay = false, isBeforeDeadline = true)

        assertTrue(plan.shouldSlideDeadline)
        assertTrue(plan.shouldScheduleFinalization)
    }

    @Test
    fun latePacketCannotReverseDueTransition() {
        val plan = pendingReplayPlan(exactReplay = false, isBeforeDeadline = false)

        assertFalse(plan.shouldSlideDeadline)
        assertFalse(plan.shouldScheduleFinalization)
    }

    @Test
    fun lateExactReplayRepairsWatchdogWithoutSlidingDeadline() {
        val plan = pendingReplayPlan(exactReplay = true, isBeforeDeadline = false)

        assertFalse(plan.shouldSlideDeadline)
        assertTrue(plan.shouldScheduleFinalization)
    }
}
