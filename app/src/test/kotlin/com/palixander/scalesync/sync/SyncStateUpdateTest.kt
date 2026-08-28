package com.palixander.scalesync.sync

import com.palixander.scalesync.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateUpdateTest {
    @Test
    fun successIsTerminalAndClearsError() {
        val update = SyncResult.Success.toStateUpdate()

        assertEquals(SyncStatus.SYNCED, update.status)
        assertNull(update.error)
        assertFalse(update.shouldRetry)
    }

    @Test
    fun retryableRemainsPending() {
        val update = SyncResult.Retryable("temporary").toStateUpdate()

        assertEquals(SyncStatus.PENDING, update.status)
        assertEquals("temporary", update.error)
        assertTrue(update.shouldRetry)
    }

    @Test
    fun blockedAndDisabledAreTerminal() {
        val blocked = SyncResult.Blocked("permission").toStateUpdate()
        val disabled = SyncResult.Disabled("not in this build").toStateUpdate()

        assertEquals(SyncStatus.BLOCKED, blocked.status)
        assertFalse(blocked.shouldRetry)
        assertEquals(SyncStatus.DISABLED, disabled.status)
        assertFalse(disabled.shouldRetry)
    }
}
