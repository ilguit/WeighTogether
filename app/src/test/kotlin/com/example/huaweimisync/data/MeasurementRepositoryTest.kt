package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementRepositoryTest {
    private val profile = UserProfile(175.0, LocalDate.of(1990, 1, 1), Sex.MALE)
    private val raw = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-11T12:34:56Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = ByteArray(13),
    )

    @Test
    fun repeatedPacketIsStoredAndScheduledOnlyOnce() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val first = repository.store(raw)
        val second = repository.store(raw.copy(rawPayload = ByteArray(13) { 1 }))

        assertTrue(first is StoreResult.Inserted)
        assertEquals(StoreResult.Duplicate, second)
        assertEquals(1, dao.values.size)
        assertEquals(listOf(dao.values.keys.single()), scheduler.enqueued)
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(SyncStatus.DISABLED.name, dao.values.values.single().huaweiStatus)
    }

    @Test
    fun stableWeightWithoutImpedanceIsStoredWithoutProfileOrCalculatedValues() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
            scaleAddressProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
            huaweiSyncEnabled = false,
        )
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val first = repository.store(partial)
        val repeated = repository.store(partial.copy(rawPayload = ByteArray(13) { 1 }))

        assertTrue(first is StoreResult.Inserted)
        assertEquals(StoreResult.Duplicate, repeated)
        val stored = dao.values.values.single()
        assertEquals(MeasurementType.WEIGHT_ONLY, stored.measurementType)
        assertEquals(70.0, stored.weightKg, 0.0)
        assertNull(stored.impedanceOhm)
        assertNull(stored.fullValues)
        assertEquals(listOf(stored.id), scheduler.enqueued)
    }

    @Test
    fun unstableAndOutOfRangeWeightsAreRejected() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(StoreResult.Rejected, repository.store(raw.copy(isStable = false)))
        assertEquals(StoreResult.Rejected, repository.store(raw.copy(weightKg = 9.0, rawWeight = 1_800)))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun fullPacketUpgradesWeightOnlyRowAndRequeuesSyncedDestinations() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler, huaweiSyncEnabled = true)
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val inserted = repository.store(partial) as StoreResult.Inserted
        dao.values[inserted.value.id] = inserted.value.copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.SYNCED.name,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        val result = repository.store(raw)

        assertTrue(result is StoreResult.Upgraded)
        assertEquals(1, dao.values.size)
        val upgraded = dao.values.values.single()
        assertEquals(inserted.value.id, upgraded.id)
        assertEquals(MeasurementType.FULL, upgraded.measurementType)
        assertEquals(500, upgraded.impedanceOhm)
        assertTrue(upgraded.fullValues != null)
        assertEquals(SyncStatus.PENDING.name, upgraded.huaweiStatus)
        assertEquals(SyncStatus.PENDING.name, upgraded.healthConnectStatus)
        assertTrue(upgraded.huaweiWeightSynced)
        assertTrue(upgraded.healthConnectWeightSynced)
        assertEquals(listOf(upgraded.id, upgraded.id), scheduler.enqueued)
    }

    @Test
    fun fullPacketUpgradePreservesLocalOnlyDirectionsWithoutSchedulingAgain() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler, huaweiSyncEnabled = true)
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val inserted = repository.store(partial) as StoreResult.Inserted
        dao.values[inserted.value.id] = inserted.value.copy(
            huaweiStatus = SyncStatus.LOCAL_ONLY.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        val result = repository.store(raw)

        assertTrue(result is StoreResult.Upgraded)
        val upgraded = dao.values.getValue(inserted.value.id)
        assertEquals(MeasurementType.FULL, upgraded.measurementType)
        assertEquals(SyncStatus.LOCAL_ONLY.name, upgraded.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, upgraded.healthConnectStatus)
        assertTrue(upgraded.huaweiWeightSynced)
        assertTrue(upgraded.healthConnectWeightSynced)
        assertEquals(listOf(inserted.value.id), scheduler.enqueued)
    }

    @Test
    fun laterWeightOnlyPacketCannotDowngradeFullRow() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val inserted = repository.store(raw) as StoreResult.Inserted
        val result = repository.store(raw.copy(impedanceOhm = 0, hasImpedance = false))

        assertEquals(StoreResult.Duplicate, result)
        assertEquals(1, dao.values.size)
        assertEquals(MeasurementType.FULL, dao.values.getValue(inserted.value.id).measurementType)
        assertEquals(listOf(inserted.value.id), scheduler.enqueued)
    }

    @Test
    fun missingProfileDoesNotInsertOrSchedule() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
            scaleAddressProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
            huaweiSyncEnabled = false,
        )

        assertEquals(StoreResult.ProfileMissing, repository.store(raw))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun pendingDestinationsAreRequeuedAfterPermissionGrant() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["health-1"] = measurement(
            id = "health-1",
            measuredAt = 1L,
            huaweiStatus = SyncStatus.DISABLED,
            healthConnectStatus = SyncStatus.PENDING,
        )
        dao.values["health-2"] = measurement(
            id = "health-2",
            measuredAt = 2L,
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.BLOCKED,
        )
        dao.values["huawei-1"] = measurement(
            id = "huawei-1",
            measuredAt = 3L,
            huaweiStatus = SyncStatus.PENDING,
            healthConnectStatus = SyncStatus.SYNCED,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertEquals(listOf("health-1", "health-2", "huawei-1"), scheduler.enqueued)
    }

    @Test
    fun updateChangesAllValuesButPreservesIdentityDateAndProvenance() = runBlocking {
        val dao = FakeMeasurementDao()
        val original = measurement(
            id = "edited",
            measuredAt = 123_456L,
            huaweiStatus = SyncStatus.DISABLED,
            healthConnectStatus = SyncStatus.FAILED,
        ).copy(
            fingerprint = "immutable-fingerprint",
            deviceAddress = "immutable-device",
            rawPayloadHex = "immutable-payload",
            algorithmVersion = "immutable-algorithm",
            huaweiError = "old huawei error",
            healthConnectError = "old health error",
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
            createdAtEpochMillis = 654_321L,
        )
        dao.values[original.id] = original
        val values = editedValues()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val result = repository.update(original.id, values)

        assertEquals(MeasurementMutationResult.Success, result)
        assertEquals(values, dao.values.getValue(original.id).values)
        assertEquals(
            original.copy(
                weightKg = values.weightKg,
                impedanceOhm = values.impedanceOhm,
                bmi = values.bmi,
                bodyFatPercent = values.bodyFatPercent,
                bodyFatMassKg = values.bodyFatMassKg,
                waterPercent = values.waterPercent,
                waterMassKg = values.waterMassKg,
                muscleMassKg = values.muscleMassKg,
                skeletalMuscleMassKg = values.skeletalMuscleMassKg,
                boneMassKg = values.boneMassKg,
                proteinPercent = values.proteinPercent,
                proteinMassKg = values.proteinMassKg,
                visceralFatLevel = values.visceralFatLevel,
                basalMetabolicRateKcal = values.basalMetabolicRateKcal,
                metabolicAge = values.metabolicAge,
                leanBodyMassKg = values.leanBodyMassKg,
                huaweiStatus = SyncStatus.DISABLED.name,
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
                huaweiError = null,
                healthConnectError = null,
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            ),
            dao.values.getValue(original.id),
        )
        assertEquals(listOf(original.id), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun updateKeepsExistingLocalOnlyEditSemanticsForAvailableDirections() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["edited"] = measurement(
            id = "edited",
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.SYNCED,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(
            MeasurementMutationResult.Success,
            repository.update("edited", editedValues()),
        )

        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("edited").huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("edited").healthConnectStatus)
        assertEquals(listOf("edited"), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun weightOnlyUpdateStaysLocalAndPreservesIdentityCompositionAndProviderHistory() = runBlocking {
        val dao = FakeMeasurementDao()
        val original = measurement(
            id = "weight-only",
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.FAILED,
        ).copy(
            fingerprint = "immutable-weight-fingerprint",
            rawWeight = 14_001,
            measurementType = MeasurementType.WEIGHT_ONLY,
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
            algorithmVersion = null,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        dao.values[original.id] = original
        val scheduler = FakeSyncScheduler()

        val result = repository(dao, scheduler, huaweiSyncEnabled = true)
            .updateWeightOnly(original.id, 69.25)

        assertEquals(MeasurementMutationResult.Success, result)
        val updated = dao.values.getValue(original.id)
        assertEquals(69.25, updated.weightKg, 0.0)
        assertEquals(14_001, updated.rawWeight)
        assertEquals(MeasurementType.WEIGHT_ONLY, updated.measurementType)
        assertEquals(original.fingerprint, updated.fingerprint)
        assertNull(updated.fullValues)
        assertTrue(updated.huaweiWeightSynced)
        assertTrue(updated.healthConnectWeightSynced)
        assertEquals(SyncStatus.LOCAL_ONLY.name, updated.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, updated.healthConnectStatus)
        assertEquals(ExternalSyncPolicy.USER_LOCAL.name, updated.externalSyncPolicy)
        assertEquals(listOf(original.id), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun invalidValuesAreRejectedWithoutChangingOrCancelling() = runBlocking {
        val original = measurement(id = "edited")
        val invalidValues = listOf(
            editedValues().copy(weightKg = Double.NaN),
            editedValues().copy(bmi = Double.POSITIVE_INFINITY),
            editedValues().copy(bodyFatMassKg = -0.01),
            editedValues().copy(bodyFatPercent = 100.01),
            editedValues().copy(waterPercent = 100.01),
            editedValues().copy(proteinPercent = 100.01),
            editedValues().copy(impedanceOhm = -1),
            editedValues().copy(metabolicAge = -1),
        )

        invalidValues.forEach { invalid ->
            val dao = FakeMeasurementDao().also { it.values[original.id] = original }
            val scheduler = FakeSyncScheduler()
            val result = repository(dao, scheduler).update(original.id, invalid)

            assertEquals(MeasurementMutationResult.Invalid, result)
            assertEquals(original, dao.values.getValue(original.id))
            assertTrue(scheduler.cancelled.isEmpty())
            assertTrue(scheduler.enqueued.isEmpty())
        }
    }

    @Test
    fun missingUpdateAndDeleteDoNotChangeStateOrCancel() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(
            MeasurementMutationResult.NotFound,
            repository.update("missing", editedValues()),
        )
        assertEquals(MeasurementMutationResult.NotFound, repository.delete("missing"))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun deleteMarksLocalOnlyThenCancelsThenDeletesWithoutEnqueueing() = runBlocking {
        val events = mutableListOf<String>()
        val dao = FakeMeasurementDao(events)
        dao.values["deleted"] = measurement(id = "deleted")
        val scheduler = FakeSyncScheduler(onCancel = { id -> events += "cancel:$id" })
        val repository = repository(dao, scheduler)

        assertEquals(MeasurementMutationResult.Success, repository.delete("deleted"))

        assertEquals(
            listOf("mark-local-only:deleted", "cancel:deleted", "delete:deleted"),
            events,
        )
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun latestMeasurementFromCurrentlyLinkedScaleIsProtectedWithoutMutationOrCancellation() =
        runBlocking {
            val events = mutableListOf<String>()
            val dao = FakeMeasurementDao(events)
            val protected = measurement(
                id = "protected",
                measuredAt = 2_000L,
                huaweiStatus = SyncStatus.FAILED,
                healthConnectStatus = SyncStatus.PENDING,
            ).copy(
                huaweiError = "keep huawei error",
                healthConnectError = "keep health error",
            )
            dao.values[protected.id] = protected
            dao.values["newer-manual"] = measurement(
                id = "newer-manual",
                measuredAt = 3_000L,
            ).copy(deviceAddress = "manual")
            val scheduler = FakeSyncScheduler()
            val repository = repository(
                dao = dao,
                scheduler = scheduler,
                scaleAddress = "aa:bb:cc:dd:ee:ff",
            )

            assertEquals(
                MeasurementMutationResult.ProtectedLatest,
                repository.delete(protected.id),
            )

            assertEquals(protected, dao.values.getValue(protected.id))
            assertTrue(events.isEmpty())
            assertTrue(scheduler.cancelled.isEmpty())
            assertTrue(scheduler.enqueued.isEmpty())
        }

    @Test
    fun previousMeasurementFromLinkedScaleDeletesNormally() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["previous"] = measurement(id = "previous", measuredAt = 1_000L)
        dao.values["latest"] = measurement(id = "latest", measuredAt = 2_000L)
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler, scaleAddress = "AA:BB:CC:DD:EE:FF")

        assertEquals(MeasurementMutationResult.Success, repository.delete("previous"))

        assertTrue("previous" !in dao.values)
        assertEquals(listOf("previous"), scheduler.cancelled)
    }

    @Test
    fun manualAndDifferentScaleMeasurementsDeleteNormally() = runBlocking {
        val deletable = listOf(
            measurement(id = "manual", measuredAt = 3_000L).copy(deviceAddress = "manual"),
            measurement(id = "old-scale", measuredAt = 4_000L).copy(
                deviceAddress = "11:22:33:44:55:66",
            ),
        )

        deletable.forEach { measurement ->
            val dao = FakeMeasurementDao().also { it.values[measurement.id] = measurement }
            val scheduler = FakeSyncScheduler()
            val repository = repository(
                dao = dao,
                scheduler = scheduler,
                scaleAddress = "AA:BB:CC:DD:EE:FF",
            )

            assertEquals(
                MeasurementMutationResult.Success,
                repository.delete(measurement.id),
            )
            assertTrue(dao.values.isEmpty())
            assertEquals(listOf(measurement.id), scheduler.cancelled)
        }
    }

    @Test
    fun protectedMeasurementBecomesDeletableAfterNewerScaleMeasurementAppears() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["previous"] = measurement(id = "previous", measuredAt = 1_000L)
        val scheduler = FakeSyncScheduler()
        val repository = repository(
            dao = dao,
            scheduler = scheduler,
            scaleAddress = "AA:BB:CC:DD:EE:FF",
        )

        assertEquals(
            MeasurementMutationResult.ProtectedLatest,
            repository.delete("previous"),
        )
        dao.values["newer"] = measurement(id = "newer", measuredAt = 2_000L)

        assertEquals(MeasurementMutationResult.Success, repository.delete("previous"))
        assertEquals(listOf("previous"), scheduler.cancelled)
        assertEquals("newer", repository.protectedLatestId(dao.values.values.toList()))
    }

    @Test
    fun localOnlyMeasurementCannotBeRetriedOrReturnedToQueue() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["local"] = measurement(
            id = "local",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retry("local")
        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(dao.idsNeedingSync().isEmpty())
    }

    @Test
    fun accountAndUserLocalPoliciesCannotBeRetriedEvenWithInconsistentPendingStatuses() =
        runBlocking {
            listOf(ExternalSyncPolicy.ACCOUNT_LOCAL, ExternalSyncPolicy.USER_LOCAL).forEach { policy ->
                val dao = FakeMeasurementDao()
                dao.values[policy.name] = measurement(id = policy.name).copy(
                    externalSyncPolicy = policy.name,
                )
                val scheduler = FakeSyncScheduler()
                val repository = repository(dao, scheduler)

                repository.retry(policy.name)
                repository.retryPendingHealthConnect()
                repository.retryPendingHuawei()
                repository.sweepPendingSync()

                assertTrue("Scheduled $policy", scheduler.enqueued.isEmpty())
            }
        }

    @Test
    fun localOnlyDirectionDoesNotBlockRetryForOtherAvailableDirection() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["mixed"] = measurement(
            id = "mixed",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.FAILED,
        )
        dao.values["opposite"] = measurement(
            id = "opposite",
            measuredAt = 2_000L,
            huaweiStatus = SyncStatus.FAILED,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retry("mixed")
        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertEquals(listOf("mixed", "mixed", "opposite"), scheduler.enqueued)
        assertEquals(listOf("mixed", "opposite"), dao.idsNeedingSync())
    }

    @Test
    fun observeAllIsDescendingAndRangeIsHalfOpenAscending() = runBlocking {
        val dao = FakeMeasurementDao()
        listOf(9_000L, 10_000L, 15_000L, 20_000L, 21_000L).forEach { timestamp ->
            val value = measurement(id = timestamp.toString(), measuredAt = timestamp)
            dao.values[value.id] = value
        }
        val repository = repository(dao, FakeSyncScheduler())

        assertEquals(
            listOf(21_000L, 20_000L, 15_000L, 10_000L, 9_000L),
            repository.observeAll().first().map(MeasurementEntity::measuredAtEpochMillis),
        )
        assertEquals(
            listOf(10_000L, 15_000L),
            repository.observeRange(Instant.ofEpochSecond(10L), Instant.ofEpochSecond(20L))
                .first()
                .map(MeasurementEntity::measuredAtEpochMillis),
        )
    }

    @Test
    fun localOnlyStatusCannotBeOverwrittenByWorkerUpdate() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["local"] = measurement(
            id = "local",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )

        assertEquals(
            0,
            dao.applyHuaweiSyncResult(
                "local",
                MeasurementType.FULL.name,
                SyncStatus.SYNCED.name,
                null,
                true,
            ),
        )
        assertEquals(
            0,
            dao.applyHealthConnectSyncResult(
                "local",
                MeasurementType.FULL.name,
                SyncStatus.SYNCED.name,
                null,
                true,
            ),
        )
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("local").huaweiStatus)
        assertEquals(
            SyncStatus.LOCAL_ONLY.name,
            dao.values.getValue("local").healthConnectStatus,
        )
    }

    private fun repository(
        dao: MeasurementDao,
        scheduler: MeasurementSyncScheduler,
        huaweiSyncEnabled: Boolean = false,
        scaleAddress: String? = null,
    ) = MeasurementRepository(
        dao = dao,
        profileProvider = { profile },
        scaleAddressProvider = { scaleAddress },
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = scheduler,
        huaweiSyncEnabled = huaweiSyncEnabled,
    )
}

private class FakeSyncScheduler(
    private val onCancel: (String) -> Unit = {},
) : MeasurementSyncScheduler {
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun enqueue(measurementId: String) {
        enqueued += measurementId
    }

    override fun cancel(measurementId: String) {
        cancelled += measurementId
        onCancel(measurementId)
    }
}

private class FakeMeasurementDao(
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementDao {
    val values = linkedMapOf<String, MeasurementEntity>()

    override suspend fun insert(measurement: MeasurementEntity): Long {
        if (values.containsKey(measurement.id)) return -1L
        values[measurement.id] = measurement
        return values.size.toLong()
    }

    override suspend fun get(id: String): MeasurementEntity? = values[id]

    override suspend fun getByFingerprint(fingerprint: String): MeasurementEntity? =
        values.values.firstOrNull { it.fingerprint == fingerprint }

    override suspend fun getLatestForDevice(deviceAddress: String): MeasurementEntity? = values.values
        .filter { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }
        .maxWithOrNull(
            compareBy<MeasurementEntity>(MeasurementEntity::measuredAtEpochSecond)
                .thenBy(MeasurementEntity::createdAtEpochMillis)
                .thenBy(MeasurementEntity::id),
        )

    override fun observeLatest(limit: Int): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochSecond).take(limit),
    )

    override fun observeAll(): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochSecond),
    )

    override fun observeRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<MeasurementEntity>> = flowOf(
        values.values
            .filter { it.measuredAtEpochSecond >= startInclusive }
            .filter { it.measuredAtEpochSecond < endExclusive }
            .sortedBy(MeasurementEntity::measuredAtEpochSecond),
    )

    override suspend fun update(measurement: MeasurementEntity): Int {
        if (!values.containsKey(measurement.id)) return 0
        values[measurement.id] = measurement
        return 1
    }

    override suspend fun markLocalOnly(id: String): Int {
        val value = values[id] ?: return 0
        events += "mark-local-only:$id"
        values[id] = value.copy(
            huaweiStatus = if (value.huaweiStatus == SyncStatus.DISABLED.name) {
                SyncStatus.DISABLED.name
            } else {
                SyncStatus.LOCAL_ONLY.name
            },
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiError = null,
            healthConnectError = null,
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        )
        return 1
    }

    override suspend fun delete(id: String): Int {
        if (!values.containsKey(id)) return 0
        events += "delete:$id"
        values.remove(id)
        return 1
    }

    override suspend fun applyHuaweiSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
    ): Int {
        val value = values[id] ?: return 0
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name ||
            value.huaweiStatus == SyncStatus.LOCAL_ONLY.name
        ) return 0
        values[id] = value.copy(
            huaweiStatus = if (value.measurementType.name == expectedMeasurementType) {
                status
            } else {
                value.huaweiStatus
            },
            huaweiError = if (value.measurementType.name == expectedMeasurementType) {
                error
            } else {
                value.huaweiError
            },
            huaweiWeightSynced = value.huaweiWeightSynced || markWeightSynced,
        )
        return 1
    }

    override suspend fun applyHealthConnectSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
    ): Int {
        val value = values[id] ?: return 0
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name ||
            value.healthConnectStatus == SyncStatus.LOCAL_ONLY.name
        ) return 0
        values[id] = value.copy(
            healthConnectStatus = if (value.measurementType.name == expectedMeasurementType) {
                status
            } else {
                value.healthConnectStatus
            },
            healthConnectError = if (value.measurementType.name == expectedMeasurementType) {
                error
            } else {
                value.healthConnectError
            },
            healthConnectWeightSynced = value.healthConnectWeightSynced || markWeightSynced,
        )
        return 1
    }

    override suspend fun idsNeedingSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter {
            it.huaweiStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.DISABLED.name,
                SyncStatus.LOCAL_ONLY.name,
            ) || it.healthConnectStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.LOCAL_ONLY.name,
            )
        }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHealthConnectSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter { it.healthConnectStatus !in setOf(SyncStatus.SYNCED.name, SyncStatus.LOCAL_ONLY.name) }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHuaweiSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter {
            it.huaweiStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.DISABLED.name,
                SyncStatus.LOCAL_ONLY.name,
            )
        }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)
}

private fun editedValues() = MeasurementValues(
    weightKg = 81.25,
    impedanceOhm = 612,
    bmi = 26.5,
    bodyFatPercent = 25.5,
    bodyFatMassKg = 20.7,
    waterPercent = 52.1,
    waterMassKg = 42.3,
    muscleMassKg = 35.4,
    skeletalMuscleMassKg = 18.6,
    boneMassKg = 3.2,
    proteinPercent = 17.4,
    proteinMassKg = 13.8,
    visceralFatLevel = 9.0,
    basalMetabolicRateKcal = 1_602.0,
    metabolicAge = 41,
    leanBodyMassKg = 60.55,
)

private fun measurement(
    id: String,
    measuredAt: Long = 1_000L,
    huaweiStatus: SyncStatus = SyncStatus.PENDING,
    healthConnectStatus: SyncStatus = SyncStatus.PENDING,
) = MeasurementEntity(
    id = id,
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = Math.floorDiv(measuredAt, 1_000L),
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
    huaweiStatus = huaweiStatus.name,
    healthConnectStatus = healthConnectStatus.name,
    createdAtEpochMillis = 2_000L,
)
