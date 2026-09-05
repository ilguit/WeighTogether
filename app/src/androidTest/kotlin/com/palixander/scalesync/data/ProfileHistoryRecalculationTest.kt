package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.core.measurementFingerprint
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.ProfileUpdateAttemptResult
import com.palixander.scalesync.worker.ExternalSyncOperationSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileHistoryRecalculationTest {
    private lateinit var database: AppDatabase
    private val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
    private var nextAccount = 0

    @Before
    fun createDatabase() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun atomicProfileUpdateAttemptSeesNewCandidateAndSkipsWriteAndSweep() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val candidate = entity(
            raw("2026-08-15T10:00:00Z", 72.35, 517),
            account.id,
            ORIGINAL_PROFILE,
        )
        var sweepCalls = 0
        val updater = SerializedAccountUpdater(
            updateDelegate = repository::updateAccount,
            attemptDelegate = { update ->
                assertTrue(database.multiAccountMeasurementDao().insert(candidate) != -1L)
                repository.attemptProfileUpdate(update)
            },
            sweepPendingRouting = { sweepCalls += 1 },
            operations = ExternalSyncOperationSerializer(),
        )

        val result = updater.attempt(
            AccountUpdate(account.id, "Alicia", ORIGINAL_PROFILE.copy(heightCm = 181.0)),
        )

        assertEquals(ProfileUpdateAttemptResult.ConfirmationRequired, result)
        assertEquals(account, repository.getAccount(account.id))
        assertEquals(candidate, database.measurementDao().get(candidate.id))
        assertEquals(0, sweepCalls)
    }

    @Test
    fun atomicProfileUpdateAttemptSavesNameOnlyAndProfileWithoutHistory() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))

        val renamed = repository.attemptProfileUpdate(
            AccountUpdate(account.id, "Alicia", ORIGINAL_PROFILE),
        )
        val changedWithoutHistory = repository.attemptProfileUpdate(
            AccountUpdate(account.id, "Alicia", ORIGINAL_PROFILE.copy(heightCm = 181.0)),
        )

        assertTrue(renamed is ProfileUpdateAttemptResult.Saved)
        assertTrue(changedWithoutHistory is ProfileUpdateAttemptResult.Saved)
        assertEquals(
            181.0,
            ((changedWithoutHistory as ProfileUpdateAttemptResult.Saved).account.profile
                as AccountProfile.Complete).heightCm,
            0.0,
        )
    }

    @Test
    fun eachCalculationProfileFieldTriggersRecalculationButNameDoesNot() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val packet = raw("2026-08-15T10:00:00Z", 72.35, 517)
        val original = entity(packet, account.id, ORIGINAL_PROFILE)
        assertTrue(database.multiAccountMeasurementDao().insert(original) != -1L)

        var previous = original
        listOf(
            AccountProfile.Complete(
                heightCm = ORIGINAL_PROFILE.heightCm,
                birthDate = ORIGINAL_PROFILE.birthDate,
                sex = Sex.FEMALE,
            ),
            AccountProfile.Complete(
                heightCm = 184.0,
                birthDate = ORIGINAL_PROFILE.birthDate,
                sex = Sex.FEMALE,
            ),
            AccountProfile.Complete(
                heightCm = 184.0,
                birthDate = LocalDate.of(1980, 6, 15),
                sex = Sex.FEMALE,
            ),
        ).forEach { profile ->
            repository.updateAccount(
                AccountUpdate(account.id, "Alice", profile),
                ProfileHistoryUpdateMode.RECALCULATE,
            )
            val recalculated = requireNotNull(database.measurementDao().get(original.id))

            assertEquals(expectedValues(packet, profile), recalculated.fullValues)
            assertNotEquals(previous.fullValues, recalculated.fullValues)
            previous = recalculated
        }

        repository.updateAccount(
            AccountUpdate(
                id = account.id,
                displayName = "Alicia",
                profile = requireNotNull(repository.getAccount(account.id)).profile
                    as AccountProfile.Complete,
            ),
            ProfileHistoryUpdateMode.RECALCULATE,
        )

        assertEquals(previous, database.measurementDao().get(original.id))
    }

    @Test
    fun candidateReadAndKeepExistingUseTheSameEligibleHistoryBoundary() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val eligible = entity(
            raw("2026-08-15T10:00:00Z", 72.35, 517),
            account.id,
            ORIGINAL_PROFILE,
        )
        val manual = entity(
            raw("2026-08-15T10:01:00Z", 73.0, 520),
            account.id,
            ORIGINAL_PROFILE,
        ).copy(externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name)
        assertTrue(database.multiAccountMeasurementDao().insert(eligible) != -1L)
        assertTrue(database.multiAccountMeasurementDao().insert(manual) != -1L)
        assertTrue(repository.hasProfileRecalculationCandidates(account.id))

        val updated = repository.updateAccount(
            AccountUpdate(
                account.id,
                "Alice",
                ORIGINAL_PROFILE.copy(heightCm = 181.0),
            ),
            ProfileHistoryUpdateMode.KEEP_EXISTING,
        )

        assertEquals(181.0, (updated.profile as AccountProfile.Complete).heightCm, 0.0)
        assertEquals(eligible, database.measurementDao().get(eligible.id))
        assertEquals(manual, database.measurementDao().get(manual.id))
    }

    @Test
    fun candidateReadIncludesAutomaticWeightOnlyButIgnoresManualAndOtherAccountHistory() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val other = repository.createAccount(NewAccount("Bob", ORIGINAL_PROFILE))
        val manual = entity(
            raw("2026-08-15T10:00:00Z", 72.35, 517),
            account.id,
            ORIGINAL_PROFILE,
        ).copy(externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name)
        val weightOnly = raw("2026-08-15T10:01:00Z", 73.0, 0)
            .copy(hasImpedance = false)
            .toWeightOnlyEntity(accountId = account.id)
        val otherEligible = entity(
            raw("2026-08-15T10:02:00Z", 74.0, 520),
            other.id,
            ORIGINAL_PROFILE,
        )
        listOf(manual, weightOnly, otherEligible).forEach {
            assertTrue(database.multiAccountMeasurementDao().insert(it) != -1L)
        }

        assertTrue(repository.hasProfileRecalculationCandidates(account.id))
        assertTrue(repository.hasProfileRecalculationCandidates(other.id))
    }

    @Test
    fun recalculationUpdatesFullAndWeightOnlyContextButPreservesManualAndOtherAccount() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val other = repository.createAccount(NewAccount("Bob", ORIGINAL_PROFILE))
        val autoPacket = raw("2026-08-15T10:00:00Z", 72.35, 517)
        val originalAutoValues = expectedValues(autoPacket, ORIGINAL_PROFILE)
        val healthConnectSnapshot = requireNotNull(originalAutoValues).toCalculatedValuesSnapshot(
            ExternalSyncDestination.HEALTH_CONNECT,
        ).encode()
        val auto = entity(autoPacket, account.id, ORIGINAL_PROFILE).copy(
            healthConnectStatus = SyncStatus.SYNCED.name,
            healthConnectError = "terminal detail",
            healthConnectWeightSynced = true,
            sourcePendingId = "pending-auto",
            deduplicationHash = "dedup-auto",
            healthConnectSyncedCalculatedValues = healthConnectSnapshot,
            createdAtEpochMillis = 123_456L,
        )
        val accountLocalPacket = raw("2026-08-15T10:01:00Z", 73.1, 530)
        val accountLocal = entity(accountLocalPacket, account.id, ORIGINAL_PROFILE).copy(
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        val manualPacket = raw("2026-08-15T10:02:00Z", 74.0, 540)
        val manual = entity(manualPacket, account.id, ORIGINAL_PROFILE).copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        val weightOnly = raw("2026-08-15T10:03:00Z", 75.0, 0).copy(
            hasImpedance = false,
        ).toWeightOnlyEntity(
            accountId = account.id,
            ratingHeightCm = ORIGINAL_PROFILE.heightCm,
        )
        val otherPacket = raw("2026-08-15T10:04:00Z", 76.0, 550)
        val otherAccount = entity(otherPacket, other.id, ORIGINAL_PROFILE)
        listOf(auto, accountLocal, manual, weightOnly, otherAccount).forEach {
            assertTrue(database.multiAccountMeasurementDao().insert(it) != -1L)
        }

        val updatedProfile = AccountProfile.Complete(
            heightCm = 181.0,
            birthDate = LocalDate.of(1982, 2, 2),
            sex = Sex.FEMALE,
        )
        repository.updateAccount(
            AccountUpdate(account.id, "Alice", updatedProfile),
            ProfileHistoryUpdateMode.RECALCULATE,
        )

        val recalculatedAuto = requireNotNull(database.measurementDao().get(auto.id))
        val recalculatedAccountLocal = requireNotNull(database.measurementDao().get(accountLocal.id))
        assertEquals(expectedValues(autoPacket, updatedProfile), recalculatedAuto.fullValues)
        assertEquals(
            expectedValues(accountLocalPacket, updatedProfile),
            recalculatedAccountLocal.fullValues,
        )
        assertEquals(
            auto.withoutProfileDependentValues(),
            recalculatedAuto.withoutProfileDependentValues(),
        )
        assertEquals(
            accountLocal.withoutProfileDependentValues(),
            recalculatedAccountLocal.withoutProfileDependentValues(),
        )
        assertEquals(manual, database.measurementDao().get(manual.id))
        val recalculatedWeightOnly = requireNotNull(database.measurementDao().get(weightOnly.id))
        assertEquals(
            updatedProfile.heightCm,
            requireNotNull(recalculatedWeightOnly.ratingHeightCm),
            0.0,
        )
        assertEquals(RatingHeightOrigin.CAPTURED, recalculatedWeightOnly.ratingHeightOrigin)
        assertEquals(
            weightOnly.copy(
                ratingHeightCm = updatedProfile.heightCm,
                ratingHeightOrigin = RatingHeightOrigin.CAPTURED,
            ),
            recalculatedWeightOnly,
        )
        assertEquals(otherAccount, database.measurementDao().get(otherAccount.id))

        assertTrue(recalculatedAuto.hasProfileSyncMismatch)
        assertEquals(healthConnectSnapshot, recalculatedAuto.healthConnectSyncedCalculatedValues)
        assertEquals(SyncStatus.SYNCED.name, recalculatedAuto.healthConnectStatus)
        assertFalse(database.measurementDao().idsNeedingHealthConnectSync().contains(auto.id))
        assertEquals(
            recalculatedAuto.currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT),
            recalculatedAuto.fullValues?.toCalculatedValuesSnapshot(
                ExternalSyncDestination.HEALTH_CONNECT,
            ),
        )
    }

    @Test
    fun recalculationBackfillsMissingSnapshotForSyncedHealthConnect() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val packet = raw("2026-08-15T10:00:00Z", 72.35, 517)
        val original = entity(packet, account.id, ORIGINAL_PROFILE).copy(
            healthConnectStatus = SyncStatus.SYNCED.name,
            healthConnectSyncedCalculatedValues = null,
        )
        assertTrue(database.multiAccountMeasurementDao().insert(original) != -1L)
        val oldHealthConnectSnapshot = requireNotNull(original.fullValues)
            .toCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT)
            .encode()

        repository.updateAccount(
            AccountUpdate(
                account.id,
                "Alice",
                ORIGINAL_PROFILE.copy(heightCm = 181.0),
            ),
            ProfileHistoryUpdateMode.RECALCULATE,
        )

        val recalculated = requireNotNull(database.measurementDao().get(original.id))
        assertEquals(oldHealthConnectSnapshot, recalculated.healthConnectSyncedCalculatedValues)
        assertEquals(SyncStatus.SYNCED.name, recalculated.healthConnectStatus)
        assertNotEquals(original.fullValues, recalculated.fullValues)
        assertTrue(recalculated.hasProfileSyncMismatch)
    }

    @Test
    fun malformedFullHistoryRollsBackProfileAndEarlierRecalculations() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val valid = entity(
            raw("2026-08-15T10:00:00Z", 72.35, 517),
            account.id,
            ORIGINAL_PROFILE,
        )
        val malformed = entity(
            raw("2026-08-15T10:01:00Z", 73.0, 520),
            account.id,
            ORIGINAL_PROFILE,
        ).copy(impedanceOhm = null)
        assertTrue(database.multiAccountMeasurementDao().insert(valid) != -1L)
        assertTrue(database.multiAccountMeasurementDao().insert(malformed) != -1L)

        val failure = runCatching {
            repository.updateAccount(
                AccountUpdate(
                    account.id,
                    "Alice",
                    ORIGINAL_PROFILE.copy(heightCm = 180.0),
                ),
                ProfileHistoryUpdateMode.RECALCULATE,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(ORIGINAL_PROFILE, repository.getAccount(account.id)?.profile)
        assertEquals(valid, database.measurementDao().get(valid.id))
        assertEquals(malformed, database.measurementDao().get(malformed.id))
    }

    private fun repository() = RoomAccountRepository(
        database = database,
        calculator = calculator,
        now = { Instant.parse("2026-08-21T12:00:00Z") },
        newId = { "account-${++nextAccount}" },
    )

    private fun entity(
        raw: RawScaleMeasurement,
        accountId: AccountId,
        profile: AccountProfile.Complete,
    ): MeasurementEntity = calculator.calculate(raw, profile.toUserProfile()).toEntity(
        rawPayload = raw.rawPayload,
        fingerprint = measurementFingerprint(raw),
        accountId = accountId,
        ratingHeightCm = profile.heightCm,
    )

    private fun expectedValues(
        raw: RawScaleMeasurement,
        profile: AccountProfile.Complete,
    ): MeasurementValues? = calculator.calculate(raw, profile.toUserProfile()).toEntity(
        rawPayload = raw.rawPayload,
    ).fullValues

    private fun raw(timestamp: String, weightKg: Double, impedanceOhm: Int) = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse(timestamp),
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(0x12, 0x34, 0x56),
    )

    private fun AccountProfile.Complete.toUserProfile() = UserProfile(
        heightCm = heightCm,
        birthDate = birthDate,
        sex = sex,
    )

    private fun MeasurementEntity.withoutProfileDependentValues(): MeasurementEntity = copy(
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
        ratingHeightCm = null,
        ratingHeightOrigin = RatingHeightOrigin.CAPTURED,
    )

    private companion object {
        val ORIGINAL_PROFILE = AccountProfile.Complete(
            heightCm = 175.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.MALE,
        )
    }
}
