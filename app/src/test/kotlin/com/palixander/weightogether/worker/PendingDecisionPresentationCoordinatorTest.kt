package com.palixander.weightogether.worker

import com.palixander.weightogether.domain.PendingMeasurementId
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
            postNotification = { posted += it.size },
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingMeasurements(ids("a"))
        coordinator.updatePendingMeasurements(ids("a", "b", "c"))

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
            postNotification = { posted += it.size },
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingMeasurements(ids("a", "b"))

        assertEquals(PendingDecisionFallback.ShowOnForeground(2), coordinator.notificationDeniedFallback.value)
        assertTrue(posted.isEmpty())

        coordinator.updatePendingMeasurements(emptySet())

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
            postNotification = { posted += it.size },
            cancelNotification = { cancelled += 1 },
        )

        coordinator.updatePendingMeasurements(ids("a", "b"))
        notificationsAllowed = false
        coordinator.updatePendingMeasurements(ids("a", "b"))

        assertEquals(listOf(2), posted)
        assertEquals(1, cancelled)
        assertEquals(
            PendingDecisionFallback.ShowOnForeground(2),
            coordinator.notificationDeniedFallback.value,
        )

        notificationsAllowed = true
        coordinator.updatePendingMeasurements(ids("a", "b"))

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

        coordinator.updatePendingMeasurements(ids("a", "b"))

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

        coordinator.updatePendingMeasurements(ids("a"))

        assertEquals(
            PendingDecisionFallback.ShowOnForeground(1),
            coordinator.notificationDeniedFallback.value,
        )
    }

    @Test
    fun dismissedSnapshotIsSuppressedUntilANewIdArrives() {
        val store = MemoryDismissalStore()
        val posted = mutableListOf<Int>()
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = { posted += it.size },
            cancelNotification = {},
            dismissalStore = store,
        )
        coordinator.updatePendingMeasurements(ids("a", "b"))
        coordinator.recordNotificationDismissed(ids("a", "b"))
        coordinator.updatePendingMeasurements(ids("a", "b"))
        coordinator.updatePendingMeasurements(ids("a", "b", "c"))

        assertEquals(listOf(2, 3), posted)
        assertEquals(ids("a", "b"), store.ids)
    }

    @Test
    fun shrinkingAndEmptySnapshotsCleanPersistedDismissal() {
        val store = MemoryDismissalStore(ids("a", "b"))
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = {},
            cancelNotification = {},
            dismissalStore = store,
        )
        coordinator.updatePendingMeasurements(ids("b"))
        assertEquals(ids("b"), store.ids)
        coordinator.updatePendingMeasurements(emptySet())
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun coldStartReceiverOnlyPersistsItsSnapshot() {
        val store = MemoryDismissalStore()
        val posted = mutableListOf<Set<PendingMeasurementId>>()
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = posted::add,
            cancelNotification = {},
            dismissalStore = store,
        )
        coordinator.recordNotificationDismissed(ids("old"))

        assertEquals(ids("old"), store.ids)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun staleDismissIntentDoesNotSuppressNewPendingNotification() {
        val store = MemoryDismissalStore()
        val posted = mutableListOf<Set<PendingMeasurementId>>()
        val coordinator = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = posted::add,
            cancelNotification = {},
            dismissalStore = store,
        )

        coordinator.updatePendingMeasurements(ids("new"))
        coordinator.recordNotificationDismissed(ids("old"))

        assertEquals(listOf(ids("new")), posted)
        assertEquals(ids("old"), store.ids)

        coordinator.updatePendingMeasurements(ids("new"))

        assertEquals(listOf(ids("new"), ids("new")), posted)
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun dismissalStoreAtomicallyUnionsSnapshotsBeforeAuthoritativeRetain() {
        val store = MemoryDismissalStore(ids("existing"))

        store.addDismissedIds(ids("first"))
        store.addDismissedIds(ids("second"))

        assertEquals(ids("existing", "first", "second"), store.ids)
        assertEquals(
            ids("first", "second"),
            store.retainDismissedIds(ids("first", "second", "pending")),
        )
        assertEquals(ids("first", "second"), store.ids)
    }

    @Test
    fun persistedDismissalSurvivesCoordinatorRecreation() {
        val store = MemoryDismissalStore(ids("a"))
        val posted = mutableListOf<Int>()
        PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = { posted += it.size },
            cancelNotification = {},
            dismissalStore = store,
        ).updatePendingMeasurements(ids("a"))

        assertTrue(posted.isEmpty())
    }
}

private class MemoryDismissalStore(
    var ids: Set<PendingMeasurementId> = emptySet(),
) : PendingNotificationDismissalStore {
    override fun addDismissedIds(ids: Set<PendingMeasurementId>): Set<PendingMeasurementId> =
        synchronized(this) {
            (this.ids + ids).also { this.ids = it }
        }

    override fun retainDismissedIds(
        pendingIds: Set<PendingMeasurementId>,
    ): Set<PendingMeasurementId> = synchronized(this) {
        ids.intersect(pendingIds).also { ids = it }
    }
}

private fun ids(vararg values: String): Set<PendingMeasurementId> =
    values.mapTo(linkedSetOf(), ::PendingMeasurementId)
