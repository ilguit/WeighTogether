package com.example.huaweimisync.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.core.RawScaleMeasurement
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.FinalizePendingResult
import com.example.huaweimisync.domain.NewAccount
import com.example.huaweimisync.domain.routing.MatchingEngine
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
    fun exactStableReplayIsSuppressedWithoutMutatingPendingOrDeadline() = runBlocking {
        val persistence = persistence()
        val packet = raw(second = 0)
        val first = persistence.enqueue(packet) as PendingPersistenceResult.Inserted
        val before = database.pendingMeasurementDao().get(first.pending.id.value)

        now = now.plusSeconds(5)
        val restartedPersistence = persistence()
        assertEquals(PendingPersistenceResult.ExactReplay, restartedPersistence.enqueue(packet))

        assertEquals(before, database.pendingMeasurementDao().get(first.pending.id.value))
        assertEquals(
            packet.rawPayload.toList(),
            database.acceptedStableMeasurementDao().getLatest()!!.rawPayload.toList(),
        )
    }

    @Test
    fun invalidTimePacketReplayUsesNormalizedPacketContentInsteadOfReceivedAt() = runBlocking {
        val persistence = persistence()
        val parser = MiScalePacketParser(ZoneId.of("UTC"))
        val payload = invalidTimePayload(rawWeight = 14_000)
        val first = requireNotNull(
            parser.parse(payload, "aa:bb:cc:dd:ee:ff", Instant.parse("2026-08-20T12:00:00Z")),
        )
        assertTrue(persistence.enqueue(first) is PendingPersistenceResult.Inserted)

        val replay = requireNotNull(
            parser.parse(payload.copyOf(), "AA:BB:CC:DD:EE:FF", Instant.parse("2026-08-20T12:00:05Z")),
        )
        assertEquals(PendingPersistenceResult.ExactReplay, persistence.enqueue(replay))

        val changedPayload = invalidTimePayload(rawWeight = 14_001)
        val changed = requireNotNull(
            parser.parse(changedPayload, "AA:BB:CC:DD:EE:FF", Instant.parse("2026-08-20T12:00:05Z")),
        )
        assertTrue(persistence.enqueue(changed) is PendingPersistenceResult.Inserted)
    }

    @Test
    fun suppressedFinalizedPacketDoesNotReplaceAcceptedReplayBaseline() = runBlocking {
        val persistence = persistence()
        val accounts = RoomAccountRepository(database, now = { now }, newId = ::newId)
        val account = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val accepted = raw(second = 0, payload = byteArrayOf(1, 2, 3))
        val pending = persistence.enqueue(accepted) as PendingPersistenceResult.Inserted
        val finalized = persistence.finalizePending(pending.pending.id, account.id)
            as FinalizePendingResult.Finalized

        val suppressed = raw(second = 5, payload = byteArrayOf(9, 8, 7))
        assertTrue(persistence.enqueue(suppressed) is PendingPersistenceResult.AlreadyFinalized)
        assertEquals(1, database.measurementDao().delete(finalized.measurement.measurementId))

        assertEquals(PendingPersistenceResult.ExactReplay, persistence.enqueue(accepted))
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
    }

    @Test
    fun changedPacketReplacesLatestAndWeightOnlyCanBeEnriched() = runBlocking {
        val persistence = persistence()
        val weightOnly = raw(second = 0, full = false)
        val first = persistence.enqueue(weightOnly) as PendingPersistenceResult.Inserted

        val full = raw(second = 1, full = true, payload = byteArrayOf(9, 8, 7))
        val enriched = persistence.enqueue(full) as PendingPersistenceResult.AlreadyPending

        assertEquals(first.pending.id, enriched.pending.id)
        assertTrue(enriched.wasEnriched)
        val saved = database.acceptedStableMeasurementDao().getLatest()!!.toRawScaleMeasurement()
        assertEquals(full.deviceAddress, saved.deviceAddress)
        assertEquals(full.measuredAt, saved.measuredAt)
        assertEquals(full.rawWeight, saved.rawWeight)
        assertEquals(full.impedanceOhm, saved.impedanceOhm)
        assertEquals(full.rawPayload.toList(), saved.rawPayload.toList())
        assertEquals(PendingPersistenceResult.ExactReplay, persistence.enqueue(full.copy()))
    }

    @Test
    fun acceptedBaselineIsDeviceScopedWhileGlobalLatestStillSuppressesReplay() = runBlocking {
        val persistence = persistence()
        val scaleA = raw(
            second = 0,
            device = "AA:BB:CC:DD:EE:FF",
            payload = byteArrayOf(1),
        )
        val scaleB = raw(
            second = 20,
            device = "11:22:33:44:55:66",
            payload = byteArrayOf(2),
        )

        assertTrue(persistence.enqueue(scaleA) is PendingPersistenceResult.Inserted)
        assertTrue(persistence.enqueue(scaleB) is PendingPersistenceResult.Inserted)

        assertEquals(
            scaleA,
            persistence.latestAcceptedStableMeasurement(" aa:bb:cc:dd:ee:ff "),
        )
        assertEquals(scaleB, persistence.latestAcceptedStableMeasurement(scaleB.deviceAddress))
        assertEquals(
            scaleB.rawPayload.toList(),
            database.acceptedStableMeasurementDao().getLatest()!!.rawPayload.toList(),
        )
        assertEquals(
            scaleA.rawPayload.toList(),
            database.acceptedStableMeasurementDao()
                .getForDevice(scaleA.deviceAddress.lowercase())!!.rawPayload.toList(),
        )

        // #40 keeps its global replay contract: replay suppression still compares global latest.
        assertEquals(PendingPersistenceResult.ExactReplay, persistence.enqueue(scaleB.copy()))
    }

    @Test
    fun deviceLookupFallsBackToMatchingLegacyGlobalRow() = runBlocking {
        val legacy = raw(second = 0, device = "AA:BB:CC:DD:EE:FF")
        database.acceptedStableMeasurementDao().replaceLatest(
            AcceptedStableMeasurementEntity.latest(legacy),
        )
        val persistence = persistence()

        assertEquals(
            legacy,
            persistence.latestAcceptedStableMeasurement("aa:bb:cc:dd:ee:ff"),
        )
        assertEquals(
            null,
            persistence.latestAcceptedStableMeasurement("11:22:33:44:55:66"),
        )
    }

    @Test
    fun sameRawWeightAtZeroNineTenAndThirtySecondsUsesStrictWindow() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(raw(second = 0)) as PendingPersistenceResult.Inserted
        val sameSecond = persistence.enqueue(raw(second = 0)) as PendingPersistenceResult.AlreadyPending
        val nineSeconds = persistence.enqueue(raw(second = 9)) as PendingPersistenceResult.AlreadyPending
        val tenSeconds = persistence.enqueue(raw(second = 10)) as PendingPersistenceResult.Inserted
        val thirtySeconds = persistence.enqueue(raw(second = 30)) as PendingPersistenceResult.Inserted

        assertEquals(first.pending.id, sameSecond.pending.id)
        assertEquals(first.pending.id, nineSeconds.pending.id)
        assertTrue(tenSeconds.pending.id != first.pending.id)
        assertTrue(thirtySeconds.pending.id != first.pending.id)
        assertTrue(thirtySeconds.pending.id != tenSeconds.pending.id)
        assertEquals(3, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun differentRawWeightAtZeroNineTenAndThirtySecondsAlwaysCreatesIndependentAggregate() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(raw(second = 0, rawWeight = 14_000))
            as PendingPersistenceResult.Inserted

        val differentWeights = listOf(0L, 9L, 10L, 30L).mapIndexed { index, second ->
            persistence.enqueue(raw(second = second, rawWeight = 14_001 + index))
                as PendingPersistenceResult.Inserted
        }

        assertTrue(differentWeights.all { it.pending.id != first.pending.id })
        assertEquals(5, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun differentDeviceCreatesIndependentAggregateInsideWindow() = runBlocking {
        val persistence = persistence()
        persistence.enqueue(raw(second = 0))

        assertTrue(
            persistence.enqueue(raw(second = 5, device = "11:22:33:44:55:66")) is
                PendingPersistenceResult.Inserted,
        )
        assertEquals(2, database.pendingMeasurementDao().getAll().size)
    }

    @Test
    fun differentRawWeightsAtSameSecondFinalizeIntoTwoIndependentRecords() = runBlocking {
        val persistence = persistence()
        val accounts = RoomAccountRepository(database, now = { now }, newId = ::newId)
        val account = accounts.createAccount(NewAccount("Alice", completeProfile()))
        val first = persistence.enqueue(raw(second = 0, rawWeight = 14_000))
            as PendingPersistenceResult.Inserted
        val second = persistence.enqueue(raw(second = 0, rawWeight = 14_001))
            as PendingPersistenceResult.Inserted

        assertTrue(
            persistence.finalizePending(first.pending.id, account.id) is
                FinalizePendingResult.Finalized,
        )
        assertTrue(
            persistence.finalizePending(second.pending.id, account.id) is
                FinalizePendingResult.Finalized,
        )

        val records = listOf(
            requireNotNull(
                database.multiAccountMeasurementDao().getByPendingId(first.pending.id.value),
            ),
            requireNotNull(
                database.multiAccountMeasurementDao().getByPendingId(second.pending.id.value),
            ),
        )
        assertEquals(setOf(14_000, 14_001), records.map { it.rawWeight }.toSet())
        assertEquals(2, records.map { it.sourcePendingId }.distinct().size)
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
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
    fun extendedFullPacketEnrichesNearestActiveIncompletePredecessor() = runBlocking {
        val persistence = persistence()
        val farther = persistence.enqueue(raw(second = 0, full = false))
            as PendingPersistenceResult.Inserted
        val nearer = persistence.enqueue(raw(second = 12, full = false))
            as PendingPersistenceResult.Inserted
        now = now.plusSeconds(3)

        val enriched = persistence.enqueue(
            raw(second = 29, full = true, payload = byteArrayOf(9, 8, 7)),
        ) as PendingPersistenceResult.AlreadyPending

        assertEquals(nearer.pending.id, enriched.pending.id)
        assertEquals(nearer.pending.measuredAt, enriched.pending.measuredAt)
        assertEquals(nearer.pending.provisionalAccountId, enriched.pending.provisionalAccountId)
        assertTrue(enriched.wasEnriched)
        assertTrue(enriched.shouldScheduleFinalization)
        assertArrayEquals(byteArrayOf(9, 8, 7), enriched.pending.rawPayload)
        assertEquals(now.plusSeconds(10), enriched.pending.finalizeAfter)
        assertEquals(2, database.pendingMeasurementDao().getAll().size)
        assertFalse(database.pendingMeasurementDao().get(farther.pending.id.value)!!.hasImpedance)
    }

    @Test
    fun extendedPacketDoesNotEnrichDuePending() = runBlocking {
        val persistence = persistence()
        val first = persistence.enqueue(raw(second = 0, full = false))
            as PendingPersistenceResult.Inserted
        now = first.pending.finalizeAfter

        val result = persistence.enqueue(raw(second = 24, full = true))

        assertTrue(result is PendingPersistenceResult.Inserted)
        assertEquals(2, database.pendingMeasurementDao().getAll().size)
        assertFalse(database.pendingMeasurementDao().get(first.pending.id.value)!!.hasImpedance)
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

    @Test
    fun staleFinalizationCannotCloseExtendedWindowAndFinalizesExactlyOnce() = runBlocking {
        val persistence = persistence()
        val accounts = RoomAccountRepository(database, now = { now }, newId = ::newId)
        accounts.createAccount(NewAccount("Alice", completeProfile()))
        val first = persistence.enqueue(raw(second = 0, payload = byteArrayOf(1)))
            as PendingPersistenceResult.Inserted
        val originalDeadline = first.pending.finalizeAfter

        now = now.plusSeconds(5)
        val extended = persistence.enqueue(raw(second = 8, payload = byteArrayOf(9, 8, 7)))
            as PendingPersistenceResult.AlreadyPending
        val stale = persistence.routeDueAtomically(
            first.pending.id,
            originalDeadline,
            MatchingEngine(),
        )

        assertTrue(stale is AtomicDueRoutingResult.NotDue)
        assertEquals(extended.pending.finalizeAfter, (stale as AtomicDueRoutingResult.NotDue).pending.finalizeAfter)
        assertTrue(database.multiAccountMeasurementDao().getByPendingId(first.pending.id.value) == null)

        val finalized = persistence.routeDueAtomically(
            first.pending.id,
            extended.pending.finalizeAfter,
            MatchingEngine(),
        )
        val repeated = persistence.routeDueAtomically(
            first.pending.id,
            extended.pending.finalizeAfter,
            MatchingEngine(),
        )

        assertTrue(finalized is AtomicDueRoutingResult.Finalized)
        assertTrue(repeated is AtomicDueRoutingResult.AlreadyFinalized)
        val entity = database.multiAccountMeasurementDao().getByPendingId(first.pending.id.value)!!
        assertEquals(first.pending.id.value, entity.sourcePendingId)
        assertEquals(first.pending.measuredAt.epochSecond, entity.measuredAtEpochSecond)
        assertEquals(first.pending.rawWeight, entity.rawWeight)
        assertEquals("090807", entity.rawPayloadHex)
        assertTrue(database.pendingMeasurementDao().getAll().isEmpty())
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

    private fun invalidTimePayload(rawWeight: Int): ByteArray = byteArrayOf(
        0x00,
        0x22,
        0x00, 0x00,
        0x08,
        0x14,
        0x0a,
        0x00,
        0x00,
        0xf4.toByte(), 0x01,
        rawWeight.toByte(), (rawWeight ushr 8).toByte(),
    )
}
