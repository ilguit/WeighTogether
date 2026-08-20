package com.example.huaweimisync.measurements

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
}
