package com.example.huaweimisync.ui.profiles

import com.example.huaweimisync.charts.ChartDateRange
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.domain.NewPet
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetDeletionPreview
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import com.example.huaweimisync.domain.PetRepository
import com.example.huaweimisync.domain.PetSpecies
import com.example.huaweimisync.domain.PetUpdate
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.domain.PetWithMeasurementCount
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetHistoryStateOwnerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.parse("2026-03-29T12:00:00Z"), zone)
    private val luna = pet("luna", "Луна")

    @Test
    fun `presentation distinguishes zero one and multiple measurements`() {
        val range = ChartDateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        val empty = petHistoryPresentation(emptyList(), range, zone, Locale.US)
        val one = petHistoryPresentation(listOf(measurement("one", luna.id, "2026-03-20T10:00:00Z", 4.25)), range, zone, Locale.US)
        val many = petHistoryPresentation(
            listOf(
                measurement("older", luna.id, "2026-03-19T10:00:00Z", 4.0),
                measurement("newer", luna.id, "2026-03-20T10:00:00Z", 4.25),
            ),
            range,
            zone,
            Locale.US,
        )

        assertTrue(empty.first is PetHistoryContent.Empty)
        assertTrue(one.first is PetHistoryContent.Single)
        assertEquals(2, (many.first as PetHistoryContent.Multiple).measurements.size)
        assertEquals(0, empty.second.points.size)
        assertEquals(1, one.second.points.size)
        assertEquals(2, many.second.points.size)
    }

    @Test
    fun `range uses local calendar boundaries across DST`() {
        val range = ChartDateRange(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29))
        val includedAtStart = measurement("start", luna.id, "2026-03-28T23:00:00Z", 4.0)
        val includedAtEnd = measurement("end", luna.id, "2026-03-29T21:59:59Z", 4.1)
        val before = measurement("before", luna.id, "2026-03-28T22:59:59Z", 3.9)
        val after = measurement("after", luna.id, "2026-03-29T22:00:00Z", 4.2)

        val (content, _) = petHistoryPresentation(
            listOf(before, includedAtStart, includedAtEnd, after),
            range,
            zone,
            Locale.US,
        )

        assertEquals(listOf("end", "start"), (content as PetHistoryContent.Multiple).measurements.map { it.id })
    }

    @Test
    fun `rows are newest first while chart is oldest first and values are formatted`() {
        val sameTime = "2026-03-20T10:00:00Z"
        val range = ChartDateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        val (content, series) = petHistoryPresentation(
            listOf(
                measurement("a", luna.id, sameTime, 4.1),
                measurement("z", luna.id, sameTime, 4.25),
                measurement("old", luna.id, "2026-03-19T10:00:00Z", 4.0),
            ),
            range,
            ZoneOffset.UTC,
            Locale.US,
        )

        val rows = (content as PetHistoryContent.Multiple).measurements
        assertEquals(listOf("z", "a", "old"), rows.map { it.id })
        assertEquals("20.03.2026 10:00:00", rows.first().measuredAtText)
        assertEquals("4.25 кг", rows.first().weightText)
        assertEquals(4.0, series.points[0].value, 0.000_001)
        assertEquals(4.1, series.points[1].value, 0.000_001)
        assertEquals(4.25, series.points[2].value, 0.000_001)
    }

    @Test
    fun `owner reports not found and repository errors`() = runBlocking {
        val scope = testScope()
        val missing = PetHistoryStateOwner(luna.id, FakeRepository(), scope, clock, zone, Locale.US)
        scope.launch { missing.uiState.collect() }
        yield()
        assertTrue(missing.uiState.value.isNotFound)
        assertFalse(missing.uiState.value.isLoading)

        val failing = PetHistoryStateOwner(
            luna.id,
            FakeRepository(getPetBlock = { error("database unavailable") }),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { failing.uiState.collect() }
        yield()
        assertEquals("database unavailable", failing.uiState.value.errorMessage)
        assertFalse(failing.uiState.value.isLoading)
        assertNull(failing.uiState.value.pet)
        scope.cancel()
    }

    @Test
    fun `new repository emission updates history reactively`() = runBlocking {
        val history = MutableStateFlow(emptyList<PetMeasurement>())
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to history)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()
        assertTrue(owner.uiState.value.content is PetHistoryContent.Empty)

        history.value = listOf(measurement("new", luna.id, "2026-03-20T10:00:00Z", 4.25))
        yield()

        assertTrue(owner.uiState.value.content is PetHistoryContent.Single)
        assertEquals(4.25, owner.uiState.value.series.points.single().value, 0.0)
        scope.cancel()
    }

    @Test
    fun `delete request can be dismissed without changing history`() = runBlocking {
        val history = MutableStateFlow(listOf(measurement("one", luna.id, "2026-03-20T10:00:00Z", 4.25)))
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to history)),
            scope, clock, zone, Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()

        owner.requestDelete("one")
        assertEquals("one", owner.uiState.value.deleteConfirmation?.measurement?.id)
        owner.dismissDelete()

        assertNull(owner.uiState.value.deleteConfirmation)
        assertEquals(listOf("one"), owner.uiState.value.measurements.map { it.id })
        scope.cancel()
    }

    @Test
    fun `delete uses exact pet and measurement prevents double submit and waits for emission`() = runBlocking {
        val history = MutableStateFlow(listOf(measurement("one", luna.id, "2026-03-20T10:00:00Z", 4.25)))
        val releaseDelete = CompletableDeferred<Unit>()
        val repository = FakeRepository(
            pets = mapOf(luna.id to luna),
            histories = mapOf(luna.id to history),
            deleteBlock = { _, _ -> releaseDelete.await() },
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(luna.id, repository, scope, clock, zone, Locale.US)
        scope.launch { owner.uiState.collect() }
        yield()

        owner.requestDelete("one")
        owner.confirmDelete()
        owner.confirmDelete()
        yield()

        assertTrue(owner.uiState.value.deleteConfirmation?.isDeleting == true)
        assertEquals(listOf(luna.id to "one"), repository.deleteCalls)
        assertEquals(listOf("one"), owner.uiState.value.measurements.map { it.id })

        releaseDelete.complete(Unit)
        yield()
        assertNull(owner.uiState.value.deleteConfirmation)
        assertEquals(listOf("one"), owner.uiState.value.measurements.map { it.id })

        history.value = emptyList()
        yield()
        assertTrue(owner.uiState.value.content is PetHistoryContent.Empty)
        scope.cancel()
    }

    @Test
    fun `delete error preserves history and supports retry and dismissal`() = runBlocking {
        val history = MutableStateFlow(listOf(measurement("one", luna.id, "2026-03-20T10:00:00Z", 4.25)))
        var attempts = 0
        val repository = FakeRepository(
            pets = mapOf(luna.id to luna),
            histories = mapOf(luna.id to history),
            deleteBlock = { _, _ -> if (++attempts == 1) error("write failed") },
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(luna.id, repository, scope, clock, zone, Locale.US)
        scope.launch { owner.uiState.collect() }
        yield()

        owner.requestDelete("one")
        owner.confirmDelete()
        yield()
        assertEquals("write failed", owner.uiState.value.actionErrorMessage)
        assertFalse(owner.uiState.value.deleteConfirmation!!.isDeleting)
        assertEquals(listOf("one"), owner.uiState.value.measurements.map { it.id })

        owner.dismissActionError()
        owner.confirmDelete()
        yield()
        assertEquals(2, attempts)
        assertNull(owner.uiState.value.deleteConfirmation)
        assertNull(owner.uiState.value.actionErrorMessage)
        scope.cancel()
    }

    @Test
    fun `pet switch clears stale delete and old operation cannot affect new pet`() = runBlocking {
        val newPet = pet("new", "Новая")
        val oldHistory = MutableStateFlow(listOf(measurement("same", luna.id, "2026-03-20T10:00:00Z", 4.25)))
        val newHistory = MutableStateFlow(listOf(measurement("same", newPet.id, "2026-03-21T10:00:00Z", 5.0)))
        val releaseDelete = CompletableDeferred<Unit>()
        val repository = FakeRepository(
            pets = mapOf(luna.id to luna, newPet.id to newPet),
            histories = mapOf(luna.id to oldHistory, newPet.id to newHistory),
            deleteBlock = { _, _ -> releaseDelete.await() },
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(luna.id, repository, scope, clock, zone, Locale.US)
        scope.launch { owner.uiState.collect() }
        yield()
        owner.requestDelete("same")
        owner.confirmDelete()
        yield()

        owner.selectPet(newPet.id)
        yield()
        assertEquals(newPet.id, owner.uiState.value.petId)
        assertNull(owner.uiState.value.deleteConfirmation)

        releaseDelete.complete(Unit)
        yield()
        assertEquals(newPet.id, owner.uiState.value.petId)
        assertEquals(listOf(luna.id to "same"), repository.deleteCalls)
        scope.cancel()
    }

    @Test
    fun `changing pet cancels old load and never publishes old pet`() = runBlocking {
        val oldId = PetId("old")
        val newPet = pet("new", "Новая")
        val oldStarted = CompletableDeferred<Unit>()
        val oldNeverCompletes = CompletableDeferred<Pet?>()
        var oldCancelled = false
        val repository = FakeRepository(
            pets = mapOf(newPet.id to newPet),
            histories = mapOf(newPet.id to MutableStateFlow(emptyList())),
            getPetBlock = { id ->
                if (id == oldId) {
                    oldStarted.complete(Unit)
                    try {
                        oldNeverCompletes.await()
                    } finally {
                        oldCancelled = true
                    }
                } else {
                    newPet
                }
            },
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(oldId, repository, scope, clock, zone, Locale.US)
        scope.launch { owner.uiState.collect() }
        oldStarted.await()

        owner.selectPet(newPet.id)
        yield()

        assertTrue(oldCancelled)
        assertEquals(newPet.id, owner.uiState.value.petId)
        assertEquals(newPet, owner.uiState.value.pet)
        assertFalse(owner.uiState.value.isLoading)
        scope.cancel()
    }

    @Test
    fun `preset and custom ranges reload using current local dates`() = runBlocking {
        val history = MutableStateFlow(emptyList<PetMeasurement>())
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to history)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }

        owner.selectRangePreset(ChartRangePreset.LAST_7_DAYS)
        yield()
        assertEquals(LocalDate.of(2026, 3, 23), owner.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 3, 29), owner.uiState.value.endDateInclusive)

        owner.setDateRange(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 3))
        yield()
        assertEquals(ChartRangePreset.CUSTOM, owner.uiState.value.rangePreset)
        assertEquals(LocalDate.of(2026, 1, 2), owner.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 2, 3), owner.uiState.value.endDateInclusive)
        scope.cancel()
    }

    @Test
    fun `repository collection follows active ui collectors without accumulating`() = runBlocking {
        var starts = 0
        var cancellations = 0
        var activeCollections = 0
        var maxActiveCollections = 0
        val instrumentedHistory = flow<List<PetMeasurement>> {
            starts += 1
            activeCollections += 1
            maxActiveCollections = maxOf(maxActiveCollections, activeCollections)
            try {
                emit(emptyList())
                awaitCancellation()
            } finally {
                activeCollections -= 1
                cancellations += 1
            }
        }
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to instrumentedHistory)),
            scope,
            clock,
            zone,
            Locale.US,
        )

        repeat(3) { index ->
            assertEquals(PetHistoryUiState.initial(luna.id, clock), owner.uiState.value)
            val collector = launch { owner.uiState.collect() }
            yield()

            assertEquals(index + 1, starts)
            assertEquals(1, activeCollections)
            assertFalse(owner.uiState.value.isLoading)

            collector.cancelAndJoin()
            yield()

            assertEquals(index + 1, cancellations)
            assertEquals(0, activeCollections)
        }

        assertEquals(1, maxActiveCollections)
        scope.cancel()
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun pet(id: String, name: String) = Pet(
        id = PetId(id),
        displayName = name,
        species = PetSpecies.CAT,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun measurement(id: String, petId: PetId, instant: String, weight: Double) = PetMeasurement(
        id = id,
        petId = petId,
        measuredAt = Instant.parse(instant),
        firstWeightKg = 70.0,
        secondWeightKg = 70.0 + weight,
    )
}

private class FakeRepository(
    private val pets: Map<PetId, Pet> = emptyMap(),
    private val histories: Map<PetId, Flow<List<PetMeasurement>>> = emptyMap(),
    private val getPetBlock: (suspend (PetId) -> Pet?)? = null,
    private val deleteBlock: suspend (PetId, String) -> Unit = { _, _ -> error("unused") },
) : PetRepository {
    val deleteCalls = mutableListOf<Pair<PetId, String>>()
    override fun observePets(): Flow<List<PetWithLatestWeight>> = emptyFlow()
    override fun observeMeasurements(petId: PetId): Flow<List<PetMeasurement>> =
        histories[petId] ?: emptyFlow()

    override suspend fun getPet(id: PetId): Pet? = getPetBlock?.invoke(id) ?: pets[id]
    override suspend fun getPetWithMeasurementCount(id: PetId): PetWithMeasurementCount? = error("unused")
    override suspend fun createPet(pet: NewPet): Pet = error("unused")
    override suspend fun updatePet(pet: PetUpdate): Pet = error("unused")
    override suspend fun previewPetDeletion(id: PetId): PetDeletionPreview = error("unused")
    override suspend fun deletePet(id: PetId): PetDeletionPreview = error("unused")
    override suspend fun deleteMeasurement(petId: PetId, measurementId: String) {
        deleteCalls += petId to measurementId
        deleteBlock(petId, measurementId)
    }
    override suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement = error("unused")
}
