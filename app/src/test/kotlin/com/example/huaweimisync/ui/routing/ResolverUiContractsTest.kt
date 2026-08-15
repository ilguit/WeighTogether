package com.example.huaweimisync.ui.routing

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.RoutingCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverUiContractsTest {
    @Test
    fun `candidate accounts lead by difference and every other account remains selectable`() {
        val first = account("first", "Первый")
        val second = account("second", "Второй")
        val any = account("any", "Любой")
        val options = buildResolverAccountOptions(
            accounts = listOf(first, second, any),
            primaryAccountId = first.id,
            candidates = listOf(
                RoutingCandidate(second.id, differenceKg = 2.0, medianWeightKg = 72.0, stableOrder = 1),
                RoutingCandidate(first.id, differenceKg = 0.5, medianWeightKg = 69.5, stableOrder = 0),
            ),
        )

        assertEquals(listOf(first.id, second.id, any.id), options.map { it.accountId })
        assertTrue(options[0].isCandidate)
        assertTrue(options[1].isCandidate)
        assertFalse(options[2].isCandidate)
    }

    @Test
    fun `pending queue is fifo and later keeps its head durable`() {
        val lateId = pending("late", "2026-08-15T10:01:00Z")
        val tieSecond = pending("b", "2026-08-15T10:00:00Z")
        val tieFirst = pending("a", "2026-08-15T10:00:00Z")
        val initial = ResolverQueueState.from(
            listOf(lateId, tieSecond, tieFirst),
            isResolverVisible = true,
        )
        assertEquals(tieFirst.id, initial.current?.id)

        val later = reduceResolverQueue(initial, ResolverQueueAction.LaterRequested)
        assertFalse(later.isResolverVisible)
        assertEquals(initial.pending, later.pending)
        assertEquals(tieFirst.id, later.current?.id)
    }

    @Test
    fun `only fifo head may be removed and resolver advances to next`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val second = pending("b", "2026-08-15T10:01:00Z")
        val initial = ResolverQueueState.from(listOf(first, second), isResolverVisible = true)

        assertSame(
            initial,
            reduceResolverQueue(initial, ResolverQueueAction.HeadFinalized(second.id)),
        )
        val advanced = reduceResolverQueue(
            initial,
            ResolverQueueAction.HeadFinalized(first.id),
        )
        assertEquals(second.id, advanced.current?.id)
        assertTrue(advanced.isResolverVisible)
    }

    @Test
    fun `notification denial exposes foreground fallback without opening resolver`() {
        val state = ResolverQueueState.from(
            listOf(pending("a", "2026-08-15T10:00:00Z")),
            notificationPermissionGranted = false,
        )

        assertTrue(state.showForegroundFallback)
        assertFalse(state.isResolverVisible)
    }

    @Test
    fun `unsaved preview calculates in memory and carries explicit no-persist contract`() {
        val pending = pending("preview", "2026-08-15T10:00:00Z")
        val draft = UnsavedPreviewProfileDraft(
            heightCm = "170,0",
            birthDate = "01.01.1990",
            sex = Sex.FEMALE,
        )

        val result = calculateUnsavedPreview(
            pending = pending,
            draft = draft,
            zoneId = ZoneOffset.UTC,
        )

        assertNotNull(result)
        assertEquals(pending.weightKg, result!!.composition.weightKg, 0.0)
        assertFalse(result.isPersisted)
        assertFalse(result.canSyncExternally)
    }

    @Test
    fun `unsaved preview rejects a profile dated after the measurement`() {
        val validation = validateUnsavedPreviewProfile(
            draft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = "16.08.2026",
                sex = Sex.MALE,
            ),
            measurementDate = LocalDate.of(2026, 8, 15),
        )

        assertFalse(validation.isValid)
        assertNotNull(validation.birthDateError)
        assertNull(validation.profile)
    }

    private fun account(id: String, name: String): Account = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(
            heightCm = 170.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun pending(id: String, enqueuedAt: String): PendingMeasurement = PendingMeasurement(
        id = PendingMeasurementId(id),
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-15T09:59:00Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "hash-$id",
        enqueuedAt = Instant.parse(enqueuedAt),
    )
}
