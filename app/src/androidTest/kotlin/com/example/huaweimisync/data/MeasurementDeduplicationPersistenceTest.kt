package com.example.huaweimisync.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeasurementDeduplicationPersistenceTest {
    private lateinit var database: AppDatabase
    private var now = Instant.parse("2026-08-20T12:00:00Z")
    private val ids = AtomicInteger()

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun secondsZeroThroughNineAggregateButExactlyTenCreatesAnotherAggregate() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(raw(second = 0)) as PendingPersistenceResult.Inserted

        for (offset in 0L..9L) {
            now = now.plusMillis(1)
            val result = persistence.enqueue(raw(second = offset))
            assertTrue("offset=$offset", result is PendingPersistenceResult.AlreadyPending)
            assertEquals(first.pending.id, (result as PendingPersistenceResult.AlreadyPending).pending.id)
        }

        assertTrue(persistence.enqueue(raw(second = 10)) is PendingPersistenceResult.Inserted)
        assertEquals(2, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun differentRawWeightOrDeviceCreatesIndependentAggregate() = runBlocking {
        val persistence = persistence()
        persistence.enqueue(raw(second = 0, rawWeight = 14_000))

        assertTrue(
            persistence.enqueue(raw(second = 5, rawWeight = 14_001)) is
                PendingPersistenceResult.Inserted,
        )
        assertTrue(
            persistence.enqueue(raw(second = 5, device = "11:22:33:44:55:66")) is
                PendingPersistenceResult.Inserted,
        )
        assertEquals(3, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun nearestCandidateWinsWhenMoreThanOneAggregateIsInRange() = runBlocking {
        val persistence = persistence()
        persistence.enqueue(raw(second = 0)) as PendingPersistenceResult.Inserted
        val later = persistence.enqueue(raw(second = 16)) as PendingPersistenceResult.Inserted

        val matched = persistence.enqueue(raw(second = 9))
            as PendingPersistenceResult.AlreadyPending

        assertEquals(later.pending.id, matched.pending.id)
        assertEquals(2, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun weightOnlyThenFullEnrichesWithoutChangingIdentityOrFirstPacketTime() = runBlocking {
        val persistence = persistence()
        val weightOnly = raw(second = 0, full = false, payload = byteArrayOf(1))
        val first = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted
        now = now.plusSeconds(3)

        val result = persistence.enqueue(
            raw(second = 8, full = true, payload = byteArrayOf(2, 3)),
        ) as PendingPersistenceResult.AlreadyPending

        assertTrue(result.wasEnriched)
        assertEquals(first.pending.id, result.pending.id)
        assertEquals(first.pending.measuredAt, result.pending.measuredAt)
        assertEquals(first.pending.enqueuedAt, result.pending.enqueuedAt)
        assertEquals(500, result.pending.impedanceOhm)
        assertTrue(result.pending.hasImpedance)
        assertArrayEquals(byteArrayOf(2, 3), result.pending.rawPayload)
        assertEquals(now.plusSeconds(10), result.pending.finalizeAfter)
    }

    @Test
    fun fullThenWeightOnlyDoesNotDowngradeButExtendsSlidingDeadline() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(
            raw(second = 0, full = true, payload = byteArrayOf(7, 8)),
        ) as PendingPersistenceResult.Inserted
        now = now.plusSeconds(4)

        val result = persistence.enqueue(
            raw(second = 7, full = false, payload = byteArrayOf(9)),
        ) as PendingPersistenceResult.AlreadyPending

        assertFalse(result.wasEnriched)
        assertEquals(first.pending.id, result.pending.id)
        assertEquals(500, result.pending.impedanceOhm)
        assertTrue(result.pending.hasImpedance)
        assertArrayEquals(byteArrayOf(7, 8), result.pending.rawPayload)
        assertEquals(now.plusSeconds(10), result.pending.finalizeAfter)
    }

    @Test
    fun nearbyFinalAndActiveTombstoneSuppressWithoutCreatingPending() = runBlocking {
        val persistence = persistence()
        val accounts = RoomAccountRepository(
            database = database,
            now = { now },
            newId = ::newId,
        )
        val account = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val pendingFinal = persistence.enqueue(raw(second = 0)) as PendingPersistenceResult.Inserted
        assertTrue(
            persistence.finalizePending(pendingFinal.pending.id, account.id) is
                FinalizePendingResult.Finalized,
        )
        assertTrue(
            persistence.enqueue(raw(second = 9)) is PendingPersistenceResult.AlreadyFinalized,
        )

        val pendingDiscard = persistence.enqueue(raw(second = 30)) as PendingPersistenceResult.Inserted
        persistence.discardPending(pendingDiscard.pending.id)
        assertEquals(
            PendingPersistenceResult.Tombstoned,
            persistence.enqueue(raw(second = 39)),
        )
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun concurrentPacketsCreateOneAggregate() = runBlocking {
        val persistence = persistence()

        val results = (0 until 8).map { offset ->
            async(Dispatchers.Default) {
                persistence.enqueue(raw(second = offset.toLong(), full = offset % 2 == 0))
            }
        }.awaitAll()

        assertEquals(1, results.count { it is PendingPersistenceResult.Inserted })
        assertEquals(7, results.count { it is PendingPersistenceResult.AlreadyPending })
        assertEquals(1, database.pendingMeasurementDao().getAll().size)
    }

    private fun persistence() = RoomMeasurementPersistence(
        database = database,
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        huaweiSyncEnabled = false,
        now = { now },
        newId = ::newId,
    )

    private fun newId(): String = UUID.nameUUIDFromBytes(
        "dedup-${ids.incrementAndGet()}".toByteArray(),
    ).toString()

    private fun completeProfile() = AccountProfile.Complete(
        heightCm = 175.0,
        birthDate = LocalDate.of(1990, 1, 1),
        sex = Sex.MALE,
    )

    private fun raw(
        second: Long,
        rawWeight: Int = 14_000,
        device: String = "AA:BB:CC:DD:EE:FF",
        full: Boolean = true,
        payload: ByteArray = byteArrayOf(1, 2, 3),
    ) = RawScaleMeasurement(
        deviceAddress = device,
        measuredAt = Instant.parse("2026-08-20T10:00:00Z").plusSeconds(second),
        weightKg = rawWeight * RawScaleMeasurement.WEIGHT_RESOLUTION_KG,
        impedanceOhm = if (full) 500 else 0,
        isStable = true,
        hasImpedance = full,
        rawPayload = payload,
        rawWeight = rawWeight,
    )
}
