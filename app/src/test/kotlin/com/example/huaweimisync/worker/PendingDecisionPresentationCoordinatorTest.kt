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

    @Test
    fun permissionChangesReplaceNotificationAndFallbackFromActualState() {
        var notificationsAllowed = true
        val posted = mutableListOf<Int>()
        var cancelled = 0
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { notificationsAllowed },
            postNotification = posted::add,
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingCount(2)
        notificationsAllowed = false
        coordinator.updatePendingCount(2)

        assertEquals(listOf(2), posted)
        assertEquals(1, cancelled)
        assertEquals(
            PendingDecisionFallback.ShowOnForeground(2),
            coordinator.notificationDeniedFallback.value,
        )

        notificationsAllowed = true
        coordinator.updatePendingCount(2)

        assertEquals(listOf(2, 2), posted)
        assertEquals(PendingDecisionFallback.Hidden, coordinator.notificationDeniedFallback.value)
    }

    @Test
    fun notificationTransportFailureFallsBackWithoutEscapingDurableIngestion() {
        var cancelled = 0
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = { throw SecurityException("permission revoked concurrently") },
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingCount(2)

        assertEquals(1, cancelled)
        assertEquals(
            PendingDecisionFallback.ShowOnForeground(2),
            coordinator.notificationDeniedFallback.value,
        )
    }

    @Test
    fun notificationCapabilityFailureAlsoFallsBack() {
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = {
                throw IllegalStateException("notification service unavailable")
            },
            postNotification = { error("must not post") },
            cancelNotification = {},
        )

        coordinator.updatePendingCount(1)

        assertEquals(
            PendingDecisionFallback.ShowOnForeground(1),
            coordinator.notificationDeniedFallback.value,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePendingCountIsRejected() {
        PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = {},
            cancelNotification = {},
        ).updatePendingCount(-1)
    }
}
