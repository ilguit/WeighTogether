package com.palixander.scalesync.measurements

import com.palixander.scalesync.ui.routing.PendingResolverReturnDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementsNavigationTest {
    @Test
    fun pendingQueueBackReturnsToSummary() {
        val pendingQueue = MeasurementsNavigationState().showPendingQueue()

        assertEquals(MeasurementsDestination.PENDING_QUEUE, pendingQueue.destination)
        assertEquals(MeasurementsDestination.SUMMARY, pendingQueue.back().destination)
    }

    @Test
    fun historyBackReturnsToSummary() {
        val history = MeasurementsNavigationState().showHistory()

        assertEquals(MeasurementsDestination.HISTORY, history.destination)
        assertEquals(MeasurementsDestination.SUMMARY, history.back().destination)
    }

    @Test
    fun editorBackReturnsToSummaryOrigin() {
        val editor = MeasurementsNavigationState()
            .showEditor(MeasurementEditorOrigin.SUMMARY)

        assertEquals(MeasurementsDestination.EDITOR, editor.destination)
        assertEquals(MeasurementEditorOrigin.SUMMARY, editor.editorOrigin)
        assertEquals(MeasurementsDestination.SUMMARY, editor.back().destination)
    }

    @Test
    fun editorBackReturnsToHistoryOrigin() {
        val editor = MeasurementsNavigationState()
            .showHistory()
            .showEditor(MeasurementEditorOrigin.HISTORY)

        assertEquals(MeasurementsDestination.EDITOR, editor.destination)
        assertEquals(MeasurementEditorOrigin.HISTORY, editor.editorOrigin)
        assertEquals(MeasurementsDestination.HISTORY, editor.back().destination)
    }

    @Test
    fun summaryBackIsHandledByApplication() {
        val summary = MeasurementsNavigationState()

        assertEquals(summary, summary.back())
    }

    @Test
    fun queueOriginResolutionReturnsToPendingQueue() {
        val history = MeasurementsNavigationState().showHistory()

        val returned = history.afterPendingResolution(
            PendingResolverReturnDestination.PENDING_QUEUE,
        )

        assertEquals(MeasurementsDestination.PENDING_QUEUE, returned.destination)
    }

    @Test
    fun externalResolutionPreservesCurrentDestination() {
        val history = MeasurementsNavigationState().showHistory()

        val returned = history.afterPendingResolution(
            PendingResolverReturnDestination.PRESERVE_CURRENT,
        )

        assertEquals(history, returned)
    }

    @Test
    fun accountSelectionChangePreservesNonEditorDestinations() {
        val destinations = listOf(
            MeasurementsNavigationState(),
            MeasurementsNavigationState().showHistory(),
            MeasurementsNavigationState().showPendingQueue(),
        )

        destinations.forEach { navigation ->
            assertEquals(navigation, navigation.afterAccountSelectionChanged())
        }
    }

    @Test
    fun accountSelectionChangeClosesEditorToItsOrigin() {
        MeasurementEditorOrigin.entries.forEach { origin ->
            val editor = MeasurementsNavigationState().showEditor(origin)

            assertEquals(
                origin.destination,
                editor.afterAccountSelectionChanged().destination,
            )
        }
    }
}
