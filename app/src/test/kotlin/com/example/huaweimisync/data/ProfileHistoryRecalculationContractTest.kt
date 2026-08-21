package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.ExternalSyncPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHistoryRecalculationContractTest {
    private val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))

    @Test
    fun recalculationReplacesEveryDerivedValueAndNothingElse() {
        val raw = RawScaleMeasurement(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = Instant.parse("2026-08-15T10:00:00Z"),
            weightKg = 72.35,
            impedanceOhm = 517,
            isStable = true,
            hasImpedance = true,
            rawPayload = byteArrayOf(1, 2, 3, 4),
            rawWeight = 14_471,
        )
        val oldProfile = UserProfile(175.0, LocalDate.of(1990, 1, 1), Sex.MALE)
        val newProfile = UserProfile(183.0, LocalDate.of(1980, 6, 15), Sex.FEMALE)
        val oldValues = calculator.calculate(raw, oldProfile)
        val original = oldValues.toEntity(
            rawPayload = raw.rawPayload,
            accountId = AccountId("account"),
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL,
            sourcePendingId = "pending",
            deduplicationHash = "dedup",
        ).copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.FAILED.name,
            huaweiError = "huawei detail",
            healthConnectError = "retry later",
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
            huaweiSyncedCalculatedValues = "huawei-snapshot",
            healthConnectSyncedCalculatedValues = "health-connect-snapshot",
            createdAtEpochMillis = 42L,
        )

        val recalculated = original.recalculate(calculator, newProfile)
        val expected = calculator.calculate(raw, newProfile).toEntity(raw.rawPayload)

        assertNotEquals(original.fullValues, recalculated.fullValues)
        assertEquals(expected.fullValues, recalculated.fullValues)
        assertEquals(expected.algorithmVersion, recalculated.algorithmVersion)
        assertEquals(original.withoutCalculatedValues(), recalculated.withoutCalculatedValues())
    }

    @Test
    fun weightOnlyRowsCannotEnterTheRecalculationOperation() {
        val weightOnly = RawScaleMeasurement(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAt = Instant.parse("2026-08-15T10:00:00Z"),
            weightKg = 72.35,
            impedanceOhm = 0,
            isStable = true,
            hasImpedance = false,
            rawPayload = byteArrayOf(1, 2, 3, 4),
        ).toWeightOnlyEntity(accountId = AccountId("account"))

        val failure = runCatching {
            weightOnly.recalculate(
                calculator,
                UserProfile(175.0, LocalDate.of(1990, 1, 1), Sex.MALE),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun MeasurementEntity.withoutCalculatedValues(): MeasurementEntity = copy(
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
        algorithmVersion = null,
    )
}
