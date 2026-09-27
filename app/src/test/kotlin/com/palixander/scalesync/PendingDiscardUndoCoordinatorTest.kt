package com.palixander.scalesync

import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountMeasurement
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.PendingDiscardUndoToken
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.RestorePendingResult
import com.palixander.scalesync.ui.text.UiText
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingDiscardUndoCoordinatorTest {
    @Test
    fun `snackbars address the matching process-local token exactly once`() = runBlocking {
        val emitter = MainUiEventEmitter()
        val coordinator = PendingDiscardUndoCoordinator(emitter)
        val firstToken = PendingDiscardUndoToken(pending("first"))
        val secondToken = PendingDiscardUndoToken(pending("second"))

        coordinator.show(firstToken)
        coordinator.show(secondToken)

        val firstEvent = emitter.events.first() as MainUiEvent.ShowPendingDiscardUndo
        val secondEvent = emitter.events.first() as MainUiEvent.ShowPendingDiscardUndo
        assertEquals(firstToken.pendingId, firstEvent.pendingId)
        assertEquals(secondToken.pendingId, secondEvent.pendingId)
        assertEquals(UiText.Resource(R.string.message_pending_discarded), firstEvent.message)
        assertEquals(UiText.Resource(R.string.action_undo), firstEvent.actionLabel)
        assertEquals(2, coordinator.activeSnackbarCount)

        assertSame(secondToken, coordinator.finish(secondEvent.snackbarId, undoRequested = true))
        assertNull(coordinator.finish(secondEvent.snackbarId, undoRequested = true))
        assertNull(coordinator.finish(firstEvent.snackbarId, undoRequested = false))
        assertNull(coordinator.finish(firstEvent.snackbarId, undoRequested = true))
        assertEquals(0, coordinator.activeSnackbarCount)
    }

    @Test
    fun `undo capability is absent from event and cannot cross a coordinator lifetime`() =
        runBlocking {
            val firstEmitter = MainUiEventEmitter()
            val firstProcess = PendingDiscardUndoCoordinator(firstEmitter)
            firstProcess.show(PendingDiscardUndoToken(pending("process-one")))
            val event = firstEmitter.events.first() as MainUiEvent.ShowPendingDiscardUndo

            val restartedProcess = PendingDiscardUndoCoordinator(MainUiEventEmitter())

            assertNull(restartedProcess.finish(event.snackbarId, undoRequested = true))
            assertFalse(
                event.javaClass.declaredFields.any {
                    PendingDiscardUndoToken::class.java.isAssignableFrom(it.type)
                },
            )
            assertEquals(1, firstProcess.activeSnackbarCount)
            firstProcess.finish(event.snackbarId, undoRequested = false)
            assertEquals(0, firstProcess.activeSnackbarCount)
        }

    @Test
    fun `every restore outcome has an explicit user-facing result`() {
        val pending = pending("restored")
        val finalized = AccountMeasurement(
            accountId = AccountId("account"),
            composition = null,
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL,
            createdAt = Instant.parse("2026-08-15T12:00:00Z"),
            measurementId = "measurement",
            weightKg = 70.0,
        )

        assertEquals(
            UiText.Resource(R.string.message_pending_restored),
            RestorePendingResult.Restored(pending).undoResultMessage(),
        )
        assertEquals(
            UiText.Resource(R.string.message_pending_already_restored),
            RestorePendingResult.AlreadyRestored(pending).undoResultMessage(),
        )
        assertEquals(
            UiText.Resource(R.string.message_pending_restore_finalized),
            RestorePendingResult.AlreadyFinalized(finalized).undoResultMessage(),
        )
        assertEquals(
            UiText.Resource(R.string.error_pending_restore_conflict),
            RestorePendingResult.Conflict(pending).undoResultMessage(),
        )
    }

    @Test
    fun `undo action calls restore once while stale action cannot call it again`() = runBlocking {
        val emitter = MainUiEventEmitter()
        val coordinator = PendingDiscardUndoCoordinator(emitter)
        val token = PendingDiscardUndoToken(pending("action"))
        coordinator.show(token)
        val event = emitter.events.first() as MainUiEvent.ShowPendingDiscardUndo
        var restoreCalls = 0

        val consumed = requireNotNull(
            coordinator.finish(event.snackbarId, undoRequested = true),
        )
        val message = restorePendingForUndo(consumed) {
            restoreCalls += 1
            assertSame(token, it)
            RestorePendingResult.Restored(it.pending)
        }
        coordinator.finish(event.snackbarId, undoRequested = true)?.let {
            restorePendingForUndo(it) {
                restoreCalls += 1
                RestorePendingResult.Restored(it.pending)
            }
        }

        assertEquals(1, restoreCalls)
        assertEquals(UiText.Resource(R.string.message_pending_restored), message)
    }

    @Test
    fun `restore failure is converted to a stable snackbar message`() {
        runBlocking {
            val token = PendingDiscardUndoToken(pending("failure"))

            assertEquals(
                UiText.Resource(
                    R.string.error_pending_restore_failure_detail,
                    listOf(UiText.Raw("Хранилище недоступно")),
                ),
                restorePendingForUndo(token) { error("Хранилище недоступно") },
            )
            assertEquals(
                UiText.Resource(
                    R.string.error_pending_restore_failure_detail,
                    listOf(UiText.Raw("IllegalStateException")),
                ),
                restorePendingForUndo(token) { throw IllegalStateException() },
            )
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking {
                    restorePendingForUndo(token) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
        }
    }

    private fun pending(id: String) = PendingMeasurement(
        id = PendingMeasurementId(id),
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-15T11:59:00Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "hash-$id",
        enqueuedAt = Instant.parse("2026-08-15T12:00:00Z"),
    )
}
