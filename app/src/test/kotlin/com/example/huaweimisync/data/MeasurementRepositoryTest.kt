package com.example.huaweimisync.data

import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.worker.MeasurementSyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun missingProfileDoesNotInsertOrSchedule() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
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
            deviceAddress = "immutable-device",
            rawPayloadHex = "immutable-payload",
            algorithmVersion = "immutable-algorithm",
            huaweiError = "old huawei error",
            healthConnectError = "old health error",
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
            ),
            dao.values.getValue(original.id),
        )
        assertEquals(listOf(original.id), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun updateMakesEnabledHuaweiTerminalLocalOnly() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["edited"] = measurement(
            id = "edited",
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.SYNCED,
        )
        val repository = repository(dao, FakeSyncScheduler())

        assertEquals(
            MeasurementMutationResult.Success,
            repository.update("edited", editedValues()),
        )

        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("edited").huaweiStatus)
        assertEquals(
            SyncStatus.LOCAL_ONLY.name,
            dao.values.getValue("edited").healthConnectStatus,
        )
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
    fun observeAllIsDescendingAndRangeIsHalfOpenAscending() = runBlocking {
        val dao = FakeMeasurementDao()
        listOf(9L, 10L, 15L, 20L, 21L).forEach { timestamp ->
            val value = measurement(id = timestamp.toString(), measuredAt = timestamp)
            dao.values[value.id] = value
        }
        val repository = repository(dao, FakeSyncScheduler())

        assertEquals(
            listOf(21L, 20L, 15L, 10L, 9L),
            repository.observeAll().first().map(MeasurementEntity::measuredAtEpochMillis),
        )
        assertEquals(
            listOf(10L, 15L),
            repository.observeRange(Instant.ofEpochMilli(10L), Instant.ofEpochMilli(20L))
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
            dao.updateHuaweiStatus("local", SyncStatus.SYNCED.name, null),
        )
        assertEquals(
            0,
            dao.updateHealthConnectStatus("local", SyncStatus.SYNCED.name, null),
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
    ) = MeasurementRepository(
        dao = dao,
        profileProvider = { profile },
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = scheduler,
        huaweiSyncEnabled = false,
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

    override fun observeLatest(limit: Int): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochMillis).take(limit),
    )

    override fun observeAll(): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochMillis),
    )

    override fun observeRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<MeasurementEntity>> = flowOf(
        values.values
            .filter { it.measuredAtEpochMillis >= startInclusive }
            .filter { it.measuredAtEpochMillis < endExclusive }
            .sortedBy(MeasurementEntity::measuredAtEpochMillis),
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
        )
        return 1
    }

    override suspend fun delete(id: String): Int {
        if (!values.containsKey(id)) return 0
        events += "delete:$id"
        values.remove(id)
        return 1
    }

    override suspend fun updateHuaweiStatus(id: String, status: String, error: String?): Int {
        val value = values[id] ?: return 0
        if (value.huaweiStatus == SyncStatus.LOCAL_ONLY.name) return 0
        values[id] = value.copy(huaweiStatus = status, huaweiError = error)
        return 1
    }

    override suspend fun updateHealthConnectStatus(
        id: String,
        status: String,
        error: String?,
    ): Int {
        val value = values[id] ?: return 0
        if (value.healthConnectStatus == SyncStatus.LOCAL_ONLY.name) return 0
        values[id] = value.copy(healthConnectStatus = status, healthConnectError = error)
        return 1
    }

    override suspend fun idsNeedingSync(): List<String> = values.values
        .filterNot(MeasurementEntity::isLocalOnly)
        .filter {
            it.huaweiStatus !in setOf(SyncStatus.SYNCED.name, SyncStatus.DISABLED.name) ||
                it.healthConnectStatus != SyncStatus.SYNCED.name
        }
        .sortedBy(MeasurementEntity::measuredAtEpochMillis)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHealthConnectSync(): List<String> = values.values
        .filter { it.healthConnectStatus !in setOf(SyncStatus.SYNCED.name, SyncStatus.LOCAL_ONLY.name) }
        .sortedBy(MeasurementEntity::measuredAtEpochMillis)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHuaweiSync(): List<String> = values.values
        .filter {
            it.huaweiStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.DISABLED.name,
                SyncStatus.LOCAL_ONLY.name,
            )
        }
        .sortedBy(MeasurementEntity::measuredAtEpochMillis)
        .map(MeasurementEntity::id)
}

private fun MeasurementEntity.isLocalOnly(): Boolean =
    huaweiStatus == SyncStatus.LOCAL_ONLY.name ||
        healthConnectStatus == SyncStatus.LOCAL_ONLY.name

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
    measuredAtEpochMillis = measuredAt,
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
