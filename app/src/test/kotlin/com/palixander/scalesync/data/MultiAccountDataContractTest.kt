package com.palixander.scalesync.data

import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAccountDataContractTest {
    @Test
    fun legacyProfileIsCompleteOnlyWhenAllValuesFormAValidUserProfile() {
        assertEquals(
            175.0,
            LegacyProfileSnapshot(175.0, LocalDate.of(1990, 1, 1), Sex.MALE)
                .completeProfile
                ?.heightCm ?: Double.NaN,
            0.0,
        )
        assertNull(
            LegacyProfileSnapshot(99.0, LocalDate.of(1990, 1, 1), Sex.MALE)
                .completeProfile,
        )
        assertNull(LegacyProfileSnapshot(175.0, null, Sex.MALE).completeProfile)
    }

    @Test
    fun accountEntityRetainsExplicitIncompleteRecoveryState() {
        val entity = AccountEntity(
            id = "88d169b9-d520-42f3-a081-f41819550e67",
            displayName = "Recovery",
            normalizedName = "recovery",
            heightCm = 99.0,
            birthDateEpochDay = null,
            sex = Sex.FEMALE.name,
            isProfileComplete = false,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
        )

        val profile = entity.toDomain().profile as AccountProfile.IncompleteRecovery

        assertEquals(99.0, profile.heightCm!!, 0.0)
        assertNull(profile.birthDate)
        assertEquals(Sex.FEMALE, profile.sex)
    }

    @Test
    fun malformedCompleteAccountFallsBackToReadableRecoveryFields() {
        val entity = AccountEntity(
            id = "d5447498-b10e-4379-9d8a-6e5090f2fcdc",
            displayName = "Recovery",
            normalizedName = "recovery",
            heightCm = 175.0,
            birthDateEpochDay = null,
            sex = "UNKNOWN",
            isProfileComplete = true,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
        )

        val profile = entity.toDomain().profile as AccountProfile.IncompleteRecovery

        assertEquals(175.0, profile.heightCm!!, 0.0)
        assertNull(profile.birthDate)
        assertNull(profile.sex)
    }

    @Test
    fun invalidStoredCompleteHeightFallsBackToRecovery() {
        val entity = AccountEntity(
            id = "38f60ca2-faf5-4a2d-8161-6dc2a6a5a441",
            displayName = "Recovery",
            normalizedName = "recovery",
            heightCm = 99.0,
            birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
            sex = Sex.MALE.name,
            isProfileComplete = true,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L,
        )

        val profile = entity.toDomain().profile as AccountProfile.IncompleteRecovery

        assertEquals(99.0, profile.heightCm!!, 0.0)
        assertEquals(LocalDate.of(1990, 1, 1), profile.birthDate)
        assertEquals(Sex.MALE, profile.sex)
    }

    @Test
    fun deduplicationHashIgnoresPayloadNoiseButIncludesPhysicalReading() {
        val original = raw(weightKg = 70.0, payload = byteArrayOf(1, 2, 3))

        assertEquals(
            original.deduplicationHash(),
            original.copy(rawPayload = byteArrayOf(9, 8, 7)).deduplicationHash(),
        )
        assertTrue(
            original.deduplicationHash() !=
                raw(weightKg = 70.1, payload = byteArrayOf(1, 2, 3)).deduplicationHash(),
        )
    }

    @Test
    fun finalizedEntityTruncatesTimestampToWholeSeconds() {
        val instant = Instant.parse("2026-08-15T12:00:00.123456789Z")
        val raw = raw(weightKg = 70.0, payload = byteArrayOf(1, 2, 3)).copy(measuredAt = instant)
        val entity = raw.toWeightOnlyEntity(accountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID))

        assertEquals(instant.epochSecond * 1_000L, entity.measuredAtEpochMillis)
        assertEquals(instant.epochSecond, entity.measuredAtEpochSecond)
        assertEquals(0, entity.measuredAtNano)
        assertEquals(Instant.ofEpochSecond(instant.epochSecond), entity.measuredAt)
    }

    @Test
    fun finalizedEntityKeepsOriginalRawWeightWhenUserFacingWeightChanges() {
        val entity = raw(weightKg = 70.005, payload = byteArrayOf(1, 2, 3))
            .toWeightOnlyEntity(accountId = AccountId(LEGACY_UNASSIGNED_ACCOUNT_ID))

        val edited = entity.copy(weightKg = 75.0)

        assertEquals(14_001, entity.rawWeight)
        assertEquals(14_001, edited.rawWeight)
    }

    @Test
    fun pendingEntityDefaultsFinalizationToTenSecondsAfterEnqueue() {
        val entity = PendingMeasurementEntity(
            id = "pending",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            measuredAtEpochSecond = 100L,
            weightKg = 70.0,
            impedanceOhm = 500,
            isStable = true,
            hasImpedance = true,
            rawPayload = byteArrayOf(1, 2, 3),
            deduplicationHash = "hash",
            enqueuedAtEpochMillis = 50_000L,
        )

        assertEquals(60_000L, entity.finalizeAfterEpochMillis)
    }

    private fun raw(weightKg: Double, payload: ByteArray) = RawScaleMeasurement(
        deviceAddress = "aa:bb:cc:dd:ee:ff",
        measuredAt = Instant.parse("2026-08-15T12:00:00Z"),
        weightKg = weightKg,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = payload,
    )
}
