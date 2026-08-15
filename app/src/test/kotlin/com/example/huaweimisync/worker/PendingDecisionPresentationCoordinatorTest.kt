package com.example.huaweimisync.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDecisionPresentationCoordinatorTest {
    @Test
    fun allowedNotificationsAreUpdatedWithCurrentCount() {
        val posted = mutableListOf<Int>()
        var cancelled = 0
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = posted::add,
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingCount(1)
        coordinator.updatePendingCount(3)

        assertEquals(listOf(1, 3), posted)
        assertEquals(0, cancelled)
        assertEquals(PendingDecisionFallback.Hidden, coordinator.notificationDeniedFallback.value)
    }

    @Test
    fun deniedNotificationsExposeForegroundFallbackUntilQueueIsEmpty() {
        val posted = mutableListOf<Int>()
        var cancelled = 0
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { false },
            postNotification = posted::add,
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingCount(2)

        assertEquals(PendingDecisionFallback.ShowOnForeground(2), coordinator.notificationDeniedFallback.value)
        assertTrue(posted.isEmpty())

        coordinator.updatePendingCount(0)

        assertEquals(PendingDecisionFallback.Hidden, coordinator.notificationDeniedFallback.value)
        assertEquals(2, cancelled)
    }
}
