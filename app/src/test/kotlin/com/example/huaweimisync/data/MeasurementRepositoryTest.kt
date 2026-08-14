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
        val scheduled = mutableListOf<String>()
        val repository = repository(dao, scheduled)

        val first = repository.store(raw)
        val second = repository.store(raw.copy(rawPayload = ByteArray(13) { 1 }))

        assertTrue(first is StoreResult.Inserted)
        assertEquals(StoreResult.Duplicate, second)
        assertEquals(1, dao.values.size)
        assertEquals(listOf(dao.values.keys.single()), scheduled)
        assertEquals(SyncStatus.DISABLED.name, dao.values.values.single().huaweiStatus)
    }

    @Test
    fun missingProfileDoesNotInsertOrSchedule() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduled = mutableListOf<String>()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = RecordingSyncScheduler(scheduled),
            huaweiSyncEnabled = false,
        )

        assertEquals(StoreResult.ProfileMissing, repository.store(raw))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun pendingDestinationsAreRequeuedAfterPermissionGrant() = runBlocking {
        val dao = FakeMeasurementDao(
            healthPending = listOf("health-1", "health-2"),
            huaweiPending = listOf("huawei-1"),
        )
        val scheduled = mutableListOf<String>()
        val repository = repository(dao, scheduled)

        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertEquals(listOf("health-1", "health-2", "huawei-1"), scheduled)
    }

    private fun repository(
        dao: MeasurementDao,
        scheduled: MutableList<String>,
    ) = MeasurementRepository(
        dao = dao,
        profileProvider = { profile },
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = RecordingSyncScheduler(scheduled),
        huaweiSyncEnabled = false,
    )
}

private class RecordingSyncScheduler(
    private val enqueued: MutableList<String>,
) : MeasurementSyncScheduler {
    override fun enqueue(measurementId: String) {
        enqueued += measurementId
    }

    override fun cancel(measurementId: String) = Unit
}

private class FakeMeasurementDao(
    private val healthPending: List<String> = emptyList(),
    private val huaweiPending: List<String> = emptyList(),
) : MeasurementDao {
    val values = linkedMapOf<String, MeasurementEntity>()

    override suspend fun insert(measurement: MeasurementEntity): Long {
        if (values.containsKey(measurement.id)) return -1L
        values[measurement.id] = measurement
        return values.size.toLong()
    }

    override suspend fun get(id: String): MeasurementEntity? = values[id]

    override fun observeLatest(limit: Int): Flow<List<MeasurementEntity>> =
        flowOf(values.values.toList().takeLast(limit).reversed())

    override suspend fun updateHuaweiStatus(id: String, status: String, error: String?) {
        values[id]?.let { values[id] = it.copy(huaweiStatus = status, huaweiError = error) }
    }

    override suspend fun updateHealthConnectStatus(id: String, status: String, error: String?) {
        values[id]?.let {
            values[id] = it.copy(healthConnectStatus = status, healthConnectError = error)
        }
    }

    override suspend fun idsNeedingSync(): List<String> =
        (healthPending + huaweiPending).distinct()

    override suspend fun idsNeedingHealthConnectSync(): List<String> = healthPending

    override suspend fun idsNeedingHuaweiSync(): List<String> = huaweiPending
}
