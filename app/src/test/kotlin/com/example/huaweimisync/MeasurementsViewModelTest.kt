package com.example.huaweimisync

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementMutationResult
import org.junit.Assert.assertEquals
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
}

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
