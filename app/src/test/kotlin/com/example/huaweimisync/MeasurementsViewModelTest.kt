package com.example.huaweimisync

import com.example.huaweimisync.data.ExternalSyncDestination
import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementsViewModelTest {
    @Test
    fun protectedLatestDeleteRequestSkipsConfirmation() {
        assertEquals(
            MeasurementDeleteRequest.ProtectedLatest,
            measurementDeleteRequest(
                id = "latest",
                measurements = listOf(measurement(id = "latest")),
                protectedLatestId = "latest",
            ),
        )
    }

    @Test
    fun ordinaryDeleteRequestPreservesConfirmationData() {
        val value = measurement(id = "previous")

        assertEquals(
            MeasurementDeleteRequest.Confirm(
                confirmation = com.example.huaweimisync.measurements.MeasurementDeleteConfirmation(
                    measurementId = value.id,
                    measuredAtEpochSecond = value.measuredAtEpochSecond,
                    weightKg = value.weightKg,
                ),
            ),
            measurementDeleteRequest(
                id = value.id,
                measurements = listOf(value),
                protectedLatestId = "newer",
            ),
        )
    }

    @Test
    fun missingDeleteRequestDoesNotCreateConfirmation() {
        assertEquals(
            MeasurementDeleteRequest.NotFound,
            measurementDeleteRequest(
                id = "missing",
                measurements = listOf(measurement(id = "existing")),
                protectedLatestId = "existing",
            ),
        )
    }

    @Test
    fun protectedRepositoryResultUsesRequiredSnackbarMessage() {
        assertEquals(
            MeasurementsViewModel.PROTECTED_LATEST_MESSAGE,
            measurementDeleteResultMessage(MeasurementMutationResult.ProtectedLatest),
        )
        assertEquals(
            "Локальное измерение удалено",
            measurementDeleteResultMessage(MeasurementMutationResult.Success),
        )
    }

    @Test
    fun historyFlagsDoNotInferManualEditFromLocalOnlySyncStatus() {
        val accountLocal = measurement(id = "account-local").copy(
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL.name,
            huaweiStatus = "LOCAL_ONLY",
            healthConnectStatus = "LOCAL_ONLY",
        ).toMeasurementUiItem(false, false)

        val userLocal = measurement(id = "user-local").copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            huaweiStatus = "SYNCED",
            healthConnectStatus = "SYNCED",
        ).toMeasurementUiItem(false, false)

        assertFalse(accountLocal.isManuallyEdited)
        assertTrue(accountLocal.isLocalOnly)
        assertTrue(userLocal.isManuallyEdited)
        assertFalse(userLocal.isLocalOnly)
    }

    @Test
    fun historyMismatchMapsSnapshotDivergenceIndependentlyFromManualEdit() {
        val original = measurement(id = "mismatch")
        val syncedSnapshot = original.currentCalculatedValuesSnapshot(
            ExternalSyncDestination.HUAWEI,
        )!!.encode()

        val item = original.copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            huaweiSyncedCalculatedValues = syncedSnapshot,
            bmi = original.bmi!! + 1.0,
        ).toMeasurementUiItem(false, false)

        assertTrue(item.isManuallyEdited)
        assertTrue(item.hasProfileSyncMismatch)
        assertEquals(
            com.example.huaweimisync.measurements.MeasurementSyncPresentationState.PENDING,
            item.sync.state,
        )
    }

    @Test
    fun finalizedProjectionKeepsPendingPresentationKeyAndUsesRowIdForMutations() {
        val item = measurement(id = "measurement-row").copy(
            sourcePendingId = "pending-stable",
        ).toMeasurementUiItem(false, false)

        assertEquals("pending-stable", item.presentationKey)
        assertEquals(PendingMeasurementId("pending-stable"), item.sourcePendingId)
        assertEquals("measurement-row", item.finalMeasurementId)
        assertEquals("measurement-row", item.mutationId)
        assertFalse(item.isPreliminary)
        assertTrue(item.canEdit)
        assertTrue(item.canDelete)
        assertTrue(item.canSync)
    }

    @Test
    fun immediatePreliminaryUsesStableKeyAndInMemoryFullComposition() {
        val pending = pending(id = "pending-live", hasImpedance = true)
        val account = account("account-a")

        val item = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(
                preliminary = listOf(pending),
                account = account,
            ),
            protectedLatestId = null,
            now = pending.enqueuedAt,
            preliminaryComposition = { value, _ -> composition(value) },
        ).single()

        assertEquals("pending-live", item.presentationKey)
        assertEquals(PendingMeasurementId("pending-live"), item.sourcePendingId)
        assertTrue(item.isPreliminary)
        assertFalse(item.isReadyForDecision)
        assertFalse(item.canEdit)
        assertFalse(item.canDelete)
        assertFalse(item.canRetry)
        assertFalse(item.canSync)
        assertEquals(22.9, item.values.bmi!!, 0.0)
        assertEquals(com.example.huaweimisync.measurements.MeasurementUiType.FULL, item.type)
    }

    @Test
    fun weightOnlyPreliminaryUpgradesInPlaceWhenFullAggregateArrives() {
        val account = account("account-a")
        val weightOnly = pending(id = "pending-upgrade", hasImpedance = false)
        val full = weightOnly.copy(hasImpedance = true, impedanceOhm = 500)

        fun project(value: PendingMeasurement) = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(listOf(), listOf(value), account),
            protectedLatestId = null,
            now = value.enqueuedAt,
            preliminaryComposition = { pending, _ ->
                pending.takeIf { it.hasImpedance }?.let(::composition)
            },
        ).single()

        val before = project(weightOnly)
        val after = project(full)
        assertEquals(before.presentationKey, after.presentationKey)
        assertTrue(before.isWeightOnly)
        assertFalse(after.isWeightOnly)
        assertEquals(22.9, after.values.bmi!!, 0.0)
    }

    @Test
    fun finalizedReplacementSuppressesTemporaryPendingDuplicateAndKeepsOrdering() {
        val pending = pending(id = "pending-replaced", hasImpedance = true)
        val final = measurement(id = "final-row").copy(
            measuredAtEpochSecond = pending.measuredAt.epochSecond,
            sourcePendingId = pending.id.value,
        )
        val older = measurement(id = "older").copy(measuredAtEpochSecond = 1L)

        val items = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(
                finalized = listOf(older, final),
                preliminary = listOf(pending),
                account = account("account-a"),
            ),
            protectedLatestId = null,
            now = pending.finalizeAfter,
            preliminaryComposition = { value, _ -> composition(value) },
        )

        assertEquals(listOf("pending-replaced", "older"), items.map { it.presentationKey })
        assertEquals("final-row", items.first().mutationId)
        assertFalse(items.first().isPreliminary)
    }

    @Test
    fun accountScopedSourcesMovePreliminaryWithoutLeakingAcrossSelection() {
        val pending = pending(id = "pending-move", hasImpedance = true)
        val first = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(
                preliminary = listOf(pending),
                account = account("account-a"),
            ),
            null,
            pending.enqueuedAt,
        ) { value, _ -> composition(value) }
        val second = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(account = account("account-b")),
            null,
            pending.enqueuedAt,
        ) { value, _ -> composition(value) }

        assertEquals(listOf("pending-move"), first.map { it.presentationKey })
        assertTrue(second.isEmpty())
    }

    @Test
    fun unassignedQueueImmediatelyIncludesNullProvisionalAndExcludesAssignedPreliminary() {
        val unassigned = pending(id = "pending-unassigned", hasImpedance = false)
        val assigned = pending(id = "pending-assigned", hasImpedance = true).copy(
            provisionalAccountId = AccountId("account-a"),
        )

        assertEquals(
            listOf(unassigned),
            unassignedPendingMeasurements(listOf(assigned, unassigned)),
        )
    }
}

private fun account(id: String) = Account(
    id = AccountId(id),
    displayName = id,
    profile = AccountProfile.Complete(175.0, LocalDate.of(1990, 1, 1), Sex.MALE),
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

private fun pending(id: String, hasImpedance: Boolean) = PendingMeasurement(
    id = PendingMeasurementId(id),
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = Instant.ofEpochSecond(100),
    weightKg = 70.0,
    impedanceOhm = if (hasImpedance) 500 else 0,
    isStable = true,
    hasImpedance = hasImpedance,
    rawPayload = byteArrayOf(1),
    deduplicationHash = "hash-$id",
    enqueuedAt = Instant.ofEpochSecond(101),
)

private fun composition(pending: PendingMeasurement) = BodyComposition(
    measurementId = "preview-only",
    deviceAddress = pending.deviceAddress,
    measuredAt = pending.measuredAt,
    weightKg = pending.weightKg,
    impedanceOhm = pending.impedanceOhm,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "preview",
)

private fun measurement(id: String) = MeasurementEntity(
    id = id,
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = 1L,
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test-v1",
)
