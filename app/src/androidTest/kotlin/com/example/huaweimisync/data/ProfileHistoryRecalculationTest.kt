package com.example.huaweimisync.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.core.measurementFingerprint
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.NewAccount
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
            repository.updateAccount(AccountUpdate(account.id, "Alice", profile))
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
        )

        assertEquals(previous, database.measurementDao().get(original.id))
    }

    @Test
    fun recalculationPreservesSourceQueueAndSnapshotsAndSkipsManualAndWeightOnly() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val other = repository.createAccount(NewAccount("Bob", ORIGINAL_PROFILE))
        val autoPacket = raw("2026-08-15T10:00:00Z", 72.35, 517)
        val originalAutoValues = expectedValues(autoPacket, ORIGINAL_PROFILE)
        val huaweiSnapshot = requireNotNull(originalAutoValues).toCalculatedValuesSnapshot(
            ExternalSyncDestination.HUAWEI,
        ).encode()
        val auto = entity(autoPacket, account.id, ORIGINAL_PROFILE).copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.FAILED.name,
            huaweiError = "terminal detail",
            healthConnectError = "temporary outage",
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
            sourcePendingId = "pending-auto",
            deduplicationHash = "dedup-auto",
            huaweiSyncedCalculatedValues = huaweiSnapshot,
            healthConnectSyncedCalculatedValues = null,
            createdAtEpochMillis = 123_456L,
        )
        val accountLocalPacket = raw("2026-08-15T10:01:00Z", 73.1, 530)
        val accountLocal = entity(accountLocalPacket, account.id, ORIGINAL_PROFILE).copy(
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL.name,
            huaweiStatus = SyncStatus.LOCAL_ONLY.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        val manualPacket = raw("2026-08-15T10:02:00Z", 74.0, 540)
        val manual = entity(manualPacket, account.id, ORIGINAL_PROFILE).copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            huaweiStatus = SyncStatus.LOCAL_ONLY.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        )
        val weightOnly = raw("2026-08-15T10:03:00Z", 75.0, 0).copy(
            hasImpedance = false,
        ).toWeightOnlyEntity(accountId = account.id)
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
        repository.updateAccount(AccountUpdate(account.id, "Alice", updatedProfile))

        val recalculatedAuto = requireNotNull(database.measurementDao().get(auto.id))
        val recalculatedAccountLocal = requireNotNull(database.measurementDao().get(accountLocal.id))
        assertEquals(expectedValues(autoPacket, updatedProfile), recalculatedAuto.fullValues)
        assertEquals(
            expectedValues(accountLocalPacket, updatedProfile),
            recalculatedAccountLocal.fullValues,
        )
        assertEquals(auto.withoutCalculatedValues(), recalculatedAuto.withoutCalculatedValues())
        assertEquals(
            accountLocal.withoutCalculatedValues(),
            recalculatedAccountLocal.withoutCalculatedValues(),
        )
        assertEquals(manual, database.measurementDao().get(manual.id))
        assertEquals(weightOnly, database.measurementDao().get(weightOnly.id))
        assertEquals(otherAccount, database.measurementDao().get(otherAccount.id))

        assertTrue(recalculatedAuto.hasProfileSyncMismatch)
        assertEquals(huaweiSnapshot, recalculatedAuto.huaweiSyncedCalculatedValues)
        assertEquals(SyncStatus.SYNCED.name, recalculatedAuto.huaweiStatus)
        assertEquals(SyncStatus.FAILED.name, recalculatedAuto.healthConnectStatus)
        assertFalse(database.measurementDao().idsNeedingHuaweiSync().contains(auto.id))
        assertTrue(database.measurementDao().idsNeedingHealthConnectSync().contains(auto.id))
        assertEquals(
            recalculatedAuto.currentCalculatedValuesSnapshot(ExternalSyncDestination.HEALTH_CONNECT),
            recalculatedAuto.fullValues?.toCalculatedValuesSnapshot(
                ExternalSyncDestination.HEALTH_CONNECT,
            ),
        )
    }

    @Test
    fun recalculationBackfillsOnlyMissingSnapshotsForSyncedDestinations() = runBlocking {
        val repository = repository()
        val account = repository.createAccount(NewAccount("Alice", ORIGINAL_PROFILE))
        val packet = raw("2026-08-15T10:00:00Z", 72.35, 517)
        val original = entity(packet, account.id, ORIGINAL_PROFILE).copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.PENDING.name,
            huaweiSyncedCalculatedValues = null,
            healthConnectSyncedCalculatedValues = null,
        )
        assertTrue(database.multiAccountMeasurementDao().insert(original) != -1L)
        val oldHuaweiSnapshot = requireNotNull(original.fullValues)
            .toCalculatedValuesSnapshot(ExternalSyncDestination.HUAWEI)
            .encode()

        repository.updateAccount(
            AccountUpdate(
                account.id,
                "Alice",
                ORIGINAL_PROFILE.copy(heightCm = 181.0),
            ),
        )

        val recalculated = requireNotNull(database.measurementDao().get(original.id))
        assertEquals(oldHuaweiSnapshot, recalculated.huaweiSyncedCalculatedValues)
        assertEquals(null, recalculated.healthConnectSyncedCalculatedValues)
        assertEquals(SyncStatus.SYNCED.name, recalculated.huaweiStatus)
        assertEquals(SyncStatus.PENDING.name, recalculated.healthConnectStatus)
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

    private companion object {
        val ORIGINAL_PROFILE = AccountProfile.Complete(
            heightCm = 175.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.MALE,
        )
    }
}
