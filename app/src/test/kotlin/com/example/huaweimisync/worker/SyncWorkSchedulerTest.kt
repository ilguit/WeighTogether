package com.example.huaweimisync.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkSchedulerTest {
    @Test
    fun initialDelayPreventsExecutionBeforeDeadline() {
        assertEquals(300_000L, initialDelayMillis(400_000L, 100_000L))
    }

    @Test
    fun elapsedOrClearedDeadlineRunsImmediately() {
        assertEquals(0L, initialDelayMillis(100_000L, 400_000L))
        assertEquals(0L, initialDelayMillis(0L, 400_000L))
    }

    @Test
    fun globalPauseCannotBeBypassedByAnEarlierRequestedDeadline() {
        assertEquals(400_000L, effectiveNotBeforeEpochMillis(200_000L, 400_000L))
        assertEquals(500_000L, effectiveNotBeforeEpochMillis(500_000L, 400_000L))
    }
}
