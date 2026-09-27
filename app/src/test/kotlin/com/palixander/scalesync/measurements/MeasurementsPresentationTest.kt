package com.palixander.scalesync.measurements

import com.palixander.scalesync.R
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.ui.text.UiText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementsPresentationTest {
    @Test
    fun summarySelectsLatestAndPreviousByTimestampAndCalculatesWeightDelta() {
        val oldest = sampleItem(id = "oldest", measuredAt = 100L, weightKg = 73.0)
        val latest = sampleItem(id = "latest", measuredAt = 300L, weightKg = 72.4)
        val previous = sampleItem(id = "previous", measuredAt = 200L, weightKg = 72.8)

        val summary = buildMeasurementSummary(listOf(oldest, latest, previous))!!

        assertEquals("latest", summary.latest.id)
        assertEquals("previous", summary.previous?.id)
        assertEquals(-0.4, summary.weightDeltaKg!!, 0.000_001)
        assertEquals(0, summary.latest.measuredAt.nano)
    }

    @Test
    fun summaryWithoutPreviousHasNoDeltaAndEmptyInputHasNoLatest() {
        val only = sampleItem(id = "only", measuredAt = 100L, weightKg = 70.0)

        val summary = buildMeasurementSummary(listOf(only))!!

        assertNull(summary.previous)
        assertNull(summary.weightDeltaKg)
        assertNull(buildMeasurementSummary(emptyList()))
        assertTrue(MeasurementsUiState(isLoading = false).hasNoLatestMeasurement)
        assertTrue(MeasurementsUiState(isLoading = false).isHistoryEmpty)
        assertFalse(MeasurementsUiState().hasNoLatestMeasurement)
    }

    @Test
    fun pendingReadingMapsLosslesslyToQueuePresentation() {
        val measuredAt = Instant.parse("2026-08-15T12:42:00Z")
        val pending = PendingMeasurement(
            id = PendingMeasurementId("pending-1"),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = measuredAt,
            weightKg = 72.4,
            impedanceOhm = 512,
            isStable = true,
            hasImpedance = true,
            rawPayload = byteArrayOf(1, 2, 3),
            deduplicationHash = "hash-1",
            enqueuedAt = measuredAt.plusSeconds(5),
        )

        assertEquals(
            PendingMeasurementUiItem(
                id = pending.id,
                measuredAtEpochSecond = measuredAt.epochSecond,
                weightKg = 72.4,
                impedanceOhm = 512,
            ),
            pending.toPendingMeasurementUiItem(),
        )
        assertNull(
            pending.copy(hasImpedance = false)
                .toPendingMeasurementUiItem()
                .impedanceOhm,
        )
    }

    @Test
    fun pendingQueueShowsAggregateImmediatelyButDisablesDecisionActionsUntilDeadline() {
        val enqueuedAt = Instant.parse("2026-08-15T12:42:00Z")
        val pending = PendingMeasurement(
            id = PendingMeasurementId("pending-processing"),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = enqueuedAt,
            weightKg = 72.4,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
            deduplicationHash = "hash-processing",
            enqueuedAt = enqueuedAt,
        )

        val processing = pending.toPendingMeasurementUiItem(pending.finalizeAfter.minusMillis(1))
        val ready = pending.toPendingMeasurementUiItem(pending.finalizeAfter)

        assertTrue(processing.isProcessing)
        assertFalse(processing.canAssign)
        assertFalse(processing.canPreview)
        assertFalse(processing.canDelete)
        assertFalse(ready.isProcessing)
        assertTrue(ready.canAssign)
        assertTrue(ready.canPreview)
        assertTrue(ready.canDelete)
    }

    @Test
    fun preliminaryProjectionParticipatesInSummaryWithoutFinalActions() {
        val measuredAt = Instant.parse("2026-08-15T12:42:00Z")
        val pending = PendingMeasurement(
            id = PendingMeasurementId("pending-summary"),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = measuredAt,
            weightKg = 72.4,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1),
            deduplicationHash = "hash-summary",
            enqueuedAt = measuredAt,
        )
        val previous = sampleItem(
            id = "previous",
            measuredAt = measuredAt.minusSeconds(60).epochSecond,
            weightKg = 72.8,
        )

        val preliminary = pending.toPreliminaryMeasurementUiItem(pending.finalizeAfter)
        val summary = buildMeasurementSummary(listOf(previous, preliminary))!!

        assertEquals(pending.id.value, preliminary.presentationKey)
        assertEquals(pending.id, preliminary.sourcePendingId)
        assertNull(preliminary.finalMeasurementId)
        assertNull(preliminary.mutationId)
        assertTrue(preliminary.isPreliminary)
        assertTrue(preliminary.isReadyForDecision)
        assertFalse(preliminary.canEdit)
        assertFalse(preliminary.canDelete)
        assertFalse(preliminary.canRetry)
        assertFalse(preliminary.canSync)
        assertEquals(preliminary, summary.latest)
        assertEquals(previous, summary.previous)
        assertEquals(-0.4, summary.weightDeltaKg!!, 0.000_001)
    }

    @Test
    fun summaryProvidesFourKeyAndElevenAdditionalMetricsInTemplateOrder() {
        val summary = buildMeasurementSummary(listOf(sampleItem()))!!

        assertEquals(
            listOf(
                MeasurementField.BODY_FAT_PERCENT,
                MeasurementField.MUSCLE_MASS_KG,
                MeasurementField.WATER_PERCENT,
                MeasurementField.BMI,
            ),
            summary.keyMetrics.map(MeasurementMetricPresentation::field),
        )
        assertEquals(11, summary.additionalMetrics.size)
        assertEquals(MeasurementField.IMPEDANCE_OHM, summary.additionalMetrics.first().field)
        assertEquals(MeasurementField.LEAN_BODY_MASS_KG, summary.additionalMetrics.last().field)
        assertEquals(
            MeasurementField.entries.toSet() - MeasurementField.WEIGHT_KG,
            (summary.keyMetrics + summary.additionalMetrics).map { it.field }.toSet(),
        )
    }

    @Test
    fun weightOnlySummaryKeepsWeightDeltaAndExposesMissingCompositionAsNull() {
        val previous = sampleItem(id = "previous", measuredAt = 100L, weightKg = 72.8)
        val latest = sampleItem(
            id = "latest",
            measuredAt = 200L,
            weightKg = 72.4,
            type = MeasurementUiType.WEIGHT_ONLY,
            values = weightOnlyValues(72.4),
        )

        val summary = buildMeasurementSummary(listOf(previous, latest))!!

        assertTrue(summary.latest.isWeightOnly)
        assertEquals(-0.4, summary.weightDeltaKg!!, 0.000_001)
        assertTrue(summary.keyMetrics.all { it.value == null })
        assertTrue(summary.additionalMetrics.all { it.value == null })
    }

    @Test
    fun syncAggregateUsesAvailableErrorPendingSyncedPriority() {
        val localWithError = sync(health = "FAILED")

        assertEquals(MeasurementSyncPresentationState.ERROR, localWithError.state)
        assertTrue(localWithError.canRetry)
        assertEquals(
            MeasurementSyncPresentationState.PENDING,
            sync(health = "PENDING").state,
        )
        assertEquals(
            MeasurementSyncPresentationState.PENDING,
            sync(health = "PENDING").state,
        )
        assertEquals(
            MeasurementSyncPresentationState.SYNCED,
            sync(health = "SYNCED").state,
        )
    }

    @Test
    fun disabledDirectionsAreExcludedAndRetryEligibilityUsesVisibleDirections() {
        val personalSynced = sync(health = "SYNCED")
        val personalPending = sync(health = "PENDING")
        val local = sync(health = "LOCAL_ONLY")

        assertEquals(listOf(MeasurementSyncDirection.HEALTH_CONNECT), personalSynced.directions.map { it.direction })
        assertEquals(MeasurementSyncPresentationState.SYNCED, personalSynced.state)
        assertFalse(personalSynced.canRetry)
        assertTrue(personalPending.canRetry)
        assertEquals(MeasurementSyncPresentationState.LOCAL_ONLY, local.state)
        assertFalse(local.canRetry)
        assertEquals(UiText.Resource(R.string.sync_state_local_only), local.label)
        assertTrue(local.directions.all { it.message == null })
    }

    @Test
    fun syncPresentationMapsRawStatusesBeforeTheyReachUi() {
        val presentation = measurementSyncPresentation(
            healthConnectStatus = "FAILED",
            healthConnectError = "permission denied",
        )

        assertEquals(UiText.Resource(R.string.sync_state_error), presentation.label)
        assertEquals(UiText.Resource(R.string.health_connect_title), presentation.directions.single().label)
        assertEquals(UiText.Raw("permission denied"), presentation.directions.single().message)
    }

    @Test
    fun historyAnnotationsAreIndependentFromLocalOnlySyncPresentation() {
        val localOnly = sampleItem().copy(
            sync = sync(health = "LOCAL_ONLY"),
        )
        val bothAnnotations = sampleItem().copy(
            isManuallyEdited = true,
            hasProfileSyncMismatch = true,
        )

        assertTrue(localOnly.isLocalOnly)
        assertFalse(localOnly.isManuallyEdited)
        assertFalse(localOnly.hasProfileSyncMismatch)
        assertTrue(bothAnnotations.isManuallyEdited)
        assertTrue(bothAnnotations.hasProfileSyncMismatch)
        assertEquals(
            "This measurement was edited manually. Changes are stored only on this device and " +
                "are not sent to external services.",
            MANUALLY_EDITED_HISTORY_MESSAGE,
        )
        assertEquals(
            "Local metrics were recalculated for the updated profile. Previously synced data " +
                "in external services was not changed.",
            PROFILE_SYNC_MISMATCH_HISTORY_MESSAGE,
        )
    }

    private fun sync(
        health: String,
    ): MeasurementSyncPresentation = measurementSyncPresentation(
        healthConnectStatus = health,
        healthConnectError = null,
    )

    private fun sampleItem(
        id: String = "measurement",
        measuredAt: Long = 100L,
        weightKg: Double = 72.4,
        type: MeasurementUiType = MeasurementUiType.FULL,
        values: MeasurementUiValues = sampleValues(weightKg),
    ) = MeasurementUiItem(
        id = id,
        measuredAtEpochSecond = measuredAt,
        values = values,
        sync = sync(health = "SYNCED"),
        type = type,
    )

    private fun weightOnlyValues(weightKg: Double) = MeasurementUiValues(
        weightKg = weightKg,
        impedanceOhm = null,
        bmi = null,
        bodyFatPercent = null,
        bodyFatMassKg = null,
        waterPercent = null,
        waterMassKg = null,
        muscleMassKg = null,
        skeletalMuscleMassKg = null,
        boneMassKg = null,
        proteinPercent = null,
        proteinMassKg = null,
        visceralFatLevel = null,
        basalMetabolicRateKcal = null,
        metabolicAge = null,
        leanBodyMassKg = null,
    )

    private fun sampleValues(weightKg: Double) = MeasurementUiValues(
        weightKg = weightKg,
        impedanceOhm = 512,
        bmi = 22.9,
        bodyFatPercent = 18.7,
        bodyFatMassKg = 13.5,
        waterPercent = 57.3,
        waterMassKg = 41.5,
        muscleMassKg = 54.1,
        skeletalMuscleMassKg = 29.8,
        boneMassKg = 3.2,
        proteinPercent = 18.2,
        proteinMassKg = 13.2,
        visceralFatLevel = 7.0,
        basalMetabolicRateKcal = 1_568.0,
        metabolicAge = 31,
        leanBodyMassKg = 58.9,
    )
}
