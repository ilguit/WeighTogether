package com.palixander.weightogether.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.weightogether.core.BodyCompositionCalculator
import com.palixander.weightogether.domain.AccountId
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeasurementHistoryRangeTest {
    private lateinit var database: AppDatabase
    private lateinit var persistence: RoomMeasurementPersistence
    private val owner = AccountId("range-owner")
    private val other = AccountId("range-other")

    @Before
    fun createDatabase() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        persistence = RoomMeasurementPersistence(database, BodyCompositionCalculator())
        listOf(owner, other).forEach { account ->
            database.accountDao().insert(
                AccountEntity(account.value, account.value, account.value, null, null, null, false, 0, 0),
            )
        }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun wholeSecondRangeIncludesStartExcludesEndAndSortsBySecondThenId() = runBlocking {
        val early = measurement("z-early", 10)
        val tieA = measurement("a-tie", 11)
        val tieZ = measurement("z-tie", 11)
        insert(tieZ, measurement("end", 12), tieA, measurement("before", 9), early)
        insert(measurement("other-owner", 10, other))

        assertRange(Instant.ofEpochSecond(10), Instant.ofEpochSecond(12), listOf(early, tieA, tieZ))
        assertRange(Instant.ofEpochSecond(12), Instant.ofEpochSecond(13), listOf(measurement("end", 12)))
    }

    @Test
    fun fractionalBoundariesRoundUpToSecondsForBothApis() = runBlocking {
        val middle = measurement("middle", 11)
        val last = measurement("last", 12)
        insert(measurement("start-second", 10), middle, last, measurement("after", 13))

        assertRange(Instant.ofEpochSecond(10, 1), Instant.ofEpochSecond(12, 1), listOf(middle, last))
        assertRange(Instant.ofEpochSecond(10, 1), Instant.ofEpochSecond(12), listOf(middle))
        assertRange(Instant.ofEpochSecond(10, 1), Instant.ofEpochSecond(10, 999_999_999), emptyList())
    }

    @Test
    fun negativeFractionalBoundariesRoundTowardNextSecond() = runBlocking {
        val negative = measurement("negative", -1)
        val zero = measurement("zero", 0)
        insert(measurement("minus-two", -2), negative, zero, measurement("one", 1))

        assertRange(Instant.ofEpochSecond(-2, 1), Instant.ofEpochSecond(0, 1), listOf(negative, zero))
        assertRange(Instant.ofEpochSecond(-2), Instant.ofEpochSecond(-1), listOf(measurement("minus-two", -2)))
        assertRange(Instant.ofEpochSecond(-1, 1), Instant.ofEpochSecond(0), emptyList())
    }

    @Test
    fun emptyAndReversedRangesThrowBeforeFlowCollection() {
        val start = Instant.ofEpochSecond(10, 123)
        listOf(start, start.minusNanos(1)).forEach { end ->
            assertThrows(IllegalArgumentException::class.java) {
                persistence.observeRangeEntities(owner, start, end)
            }
            assertThrows(IllegalArgumentException::class.java) {
                persistence.observeRange(owner, start, end)
            }
        }
    }

    @Test
    fun bothActiveFlowsEmitInsertedRangeRowsWithoutOtherAccountsOrOutsideRows() = runBlocking {
        withTimeout(10_000) {
            val entities = Channel<List<String>>(Channel.UNLIMITED)
            val domain = Channel<List<String>>(Channel.UNLIMITED)
            val start = Instant.ofEpochSecond(10)
            val end = Instant.ofEpochSecond(12)
            val entityJob = launch {
                persistence.observeRangeEntities(owner, start, end).collect { values ->
                    entities.send(values.map { it.id })
                }
            }
            val domainJob = launch {
                persistence.observeRange(owner, start, end).collect { values ->
                    domain.send(values.map { it.measurementId })
                }
            }
            try {
                assertEquals(emptyList<String>(), entities.receive())
                assertEquals(emptyList<String>(), domain.receive())
                database.withTransaction {
                    insert(measurement("inside", 11), measurement("outside", 12), measurement("other", 11, other))
                }
                assertEquals(listOf("inside"), entities.receive())
                assertEquals(listOf("inside"), domain.receive())
            } finally {
                entityJob.cancel()
                domainJob.cancel()
                entities.close()
                domain.close()
            }
        }
    }

    private suspend fun assertRange(start: Instant, end: Instant, expected: List<MeasurementEntity>) {
        withTimeout(10_000) {
            assertEquals(expected, persistence.observeRangeEntities(owner, start, end).first())
            assertEquals(expected.map { it.toAccountMeasurement() }, persistence.observeRange(owner, start, end).first())
        }
    }

    private suspend fun insert(vararg measurements: MeasurementEntity) {
        measurements.forEach { assertTrue(database.multiAccountMeasurementDao().insert(it) != -1L) }
    }

    private fun measurement(id: String, second: Long, account: AccountId = owner) = MeasurementEntity(
        id = id,
        accountId = account.value,
        deviceAddress = "test-scale",
        measuredAtEpochSecond = second,
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
        createdAtEpochMillis = 0,
    )
}
