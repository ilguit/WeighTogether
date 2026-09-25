package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetDeletionPreview
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.PetRepository
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetUpdate
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.domain.PetWithMeasurementCount
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
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
    fun `adult generic pets and Russian Blue expose bounds through state chart and tooltip for 30 days and all`() = runBlocking {
        data class Scenario(
            val name: String,
            val species: PetSpecies,
            val sex: PetSex,
            val breedId: BreedId?,
            val category: DogAdultWeightCategory? = null,
            val expectedKinds: Set<PetWeightDisplayedSeriesKind>,
        )

        val categoryKinds = setOf(
            PetWeightDisplayedSeriesKind.FACTUAL,
            PetWeightDisplayedSeriesKind.CATEGORY_LOWER,
            PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_LOWER,
            PetWeightDisplayedSeriesKind.CATEGORY_MEDIAN_UPPER,
            PetWeightDisplayedSeriesKind.CATEGORY_UPPER,
        )
        val breedKinds = setOf(
            PetWeightDisplayedSeriesKind.FACTUAL,
            PetWeightDisplayedSeriesKind.BREED_LOWER,
            PetWeightDisplayedSeriesKind.BREED_CENTER,
            PetWeightDisplayedSeriesKind.BREED_UPPER,
        )
        val scenarios = buildList {
            for (breedId in listOf(
                null,
                BreedId("scalesync:dog:breed-unknown"),
                BreedId("scalesync:dog:mixed-breed"),
            )) add(Scenario("dog-${breedId?.value ?: "other"}", PetSpecies.DOG, PetSex.MALE, breedId, DogAdultWeightCategory.III, categoryKinds))
            for (breedId in listOf(
                null,
                BreedId("scalesync:cat:breed-unknown"),
                BreedId("scalesync:cat:mixed-breed"),
            )) add(Scenario("cat-${breedId?.value ?: "other"}", PetSpecies.CAT, PetSex.FEMALE, breedId, expectedKinds = breedKinds))
            for (sex in listOf(PetSex.FEMALE, PetSex.MALE)) {
                add(Scenario("russian-blue-${sex.name}", PetSpecies.CAT, sex, BreedId("VBO:0100200"), expectedKinds = breedKinds))
            }
        }
        val measuredAt = Instant.parse("2026-03-20T10:15:00Z")

        for (scenario in scenarios) {
            val testPet = pet(scenario.name, scenario.name).copy(
                species = scenario.species,
                sex = scenario.sex,
                breedId = scenario.breedId,
                birthDate = PartialBirthDate.Day(LocalDate.of(2020, 1, 1)),
                dogAdultWeightCategory = scenario.category,
            )
            val scope = testScope()
            val owner = PetHistoryStateOwner(
                testPet.id,
                FakeRepository(
                    pets = mapOf(testPet.id to testPet),
                    histories = mapOf(
                        testPet.id to MutableStateFlow(
                            listOf(measurement("daytime", testPet.id, measuredAt.toString(), 4.25)),
                        ),
                    ),
                ),
                scope,
                clock,
                zone,
                Locale.US,
            )
            val collector = scope.launch { owner.uiState.collect() }
            yield()

            for (preset in listOf(ChartRangePreset.LAST_30_DAYS, ChartRangePreset.ALL)) {
                owner.selectRangePreset(preset)
                yield()
                val state = owner.uiState.value
                val displayed = petWeightDisplayedSeries(
                    state.series.points,
                    state.weightReference,
                    state.breedReferenceTimeline,
                    zone,
                )
                assertEquals("${scenario.name} $preset", scenario.expectedKinds, displayed.map { it.kind }.toSet())
                assertEquals("${scenario.name} $preset", 1, displayed.count { it.kind == PetWeightDisplayedSeriesKind.FACTUAL })
                val tooltip = formatPetWeightDisplayedMarker(measuredAt.toEpochMilli(), displayed, zone, Locale.US)
                assertTrue("${scenario.name} $preset factual tooltip: $tooltip", tooltip.contains("Фактический вес: 4.25 кг"))
                assertTrue("${scenario.name} $preset lower tooltip: $tooltip", tooltip.contains("Нижняя"))
                assertTrue("${scenario.name} $preset upper tooltip: $tooltip", tooltip.contains("Верхняя"))
                if (preset == ChartRangePreset.ALL) {
                    assertEquals("${scenario.name} ALL renders a seven-day domain", 6L,
                        java.time.temporal.ChronoUnit.DAYS.between(state.startDate, state.endDateInclusive))
                    assertTrue("${scenario.name} ALL samples renderable reference geometry",
                        (state.weightReference as PetHistoryWeightReference.Available).segments.any { it.size >= 2 })
                }
                if (scenario.species == PetSpecies.DOG && scenario.breedId?.value != "VBO:0200000") {
                    assertTrue(
                        "${scenario.name} $preset has a shaded resolved-reference band",
                        populationWeightReferenceBands(
                            state.weightReference as? PetHistoryWeightReference.Available,
                            zone,
                        ).isNotEmpty(),
                    )
                }
            }

            collector.cancelAndJoin()
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun `owner exposes initial loading before blocked reference dependencies complete`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        var loads = 0
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            initialPetId = luna.id,
            repository = FakeRepository(
                pets = mapOf(luna.id to luna),
                histories = mapOf(luna.id to MutableStateFlow(emptyList<PetMeasurement>())),
            ),
            parentScope = scope,
            clock = clock,
            zoneId = zone,
            locale = Locale.US,
            referenceDependencies = {
                loads += 1
                loadStarted.complete(Unit)
                releaseLoad.await()
                referenceDependencies()
            },
        )

        assertEquals(0, loads)
        assertEquals(PetHistoryUiState.initial(luna.id, clock), owner.uiState.value)

        val collector = scope.launch { owner.uiState.collect() }
        loadStarted.await()

        assertEquals(1, loads)
        assertTrue(owner.uiState.value.isLoading)
        assertNull(owner.uiState.value.pet)

        releaseLoad.complete(Unit)
        yield()

        assertFalse(owner.uiState.value.isLoading)
        assertEquals(luna, owner.uiState.value.pet)
        assertTrue(owner.uiState.value.content is PetHistoryContent.Empty)
        collector.cancelAndJoin()
        owner.close()
        scope.cancel()
    }

    @Test
    fun `shared reference loader starts once for multiple pet owners`() = runBlocking {
        val second = pet("second", "Бим")
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        var loads = 0
        val scope = testScope()
        val loader = PetHistoryReferenceLoader(scope) {
            loads += 1
            loadStarted.complete(Unit)
            releaseLoad.await()
            referenceDependencies()
        }
        val repository = FakeRepository(
            pets = mapOf(luna.id to luna, second.id to second),
            histories = mapOf(
                luna.id to MutableStateFlow(emptyList<PetMeasurement>()),
                second.id to MutableStateFlow(emptyList<PetMeasurement>()),
            ),
        )
        val firstOwner = PetHistoryStateOwner(
            luna.id, repository, scope, clock, zone, Locale.US, loader::load,
        )
        val secondOwner = PetHistoryStateOwner(
            second.id, repository, scope, clock, zone, Locale.US, loader::load,
        )

        assertEquals(0, loads)
        val firstCollector = scope.launch { firstOwner.uiState.collect() }
        loadStarted.await()
        val secondCollector = scope.launch { secondOwner.uiState.collect() }
        yield()

        assertEquals(1, loads)
        assertTrue(firstOwner.uiState.value.isLoading)
        assertTrue(secondOwner.uiState.value.isLoading)

        releaseLoad.complete(Unit)
        yield()

        assertEquals(luna, firstOwner.uiState.value.pet)
        assertEquals(second, secondOwner.uiState.value.pet)
        assertFalse(firstOwner.uiState.value.isLoading)
        assertFalse(secondOwner.uiState.value.isLoading)
        firstCollector.cancelAndJoin()
        secondCollector.cancelAndJoin()
        firstOwner.close()
        secondOwner.close()
        scope.cancel()
    }

    @Test
    fun `reference loader failure becomes terminal owner error`() = runBlocking {
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            initialPetId = luna.id,
            repository = FakeRepository(pets = mapOf(luna.id to luna)),
            parentScope = scope,
            clock = clock,
            zoneId = zone,
            locale = Locale.US,
            referenceDependencies = { error("reference unavailable") },
        )

        val collector = scope.launch { owner.uiState.collect() }
        yield()

        assertFalse(owner.uiState.value.isLoading)
        assertEquals("reference unavailable", owner.uiState.value.errorMessage)
        assertNull(owner.uiState.value.pet)
        collector.cancelAndJoin()
        owner.close()
        scope.cancel()
    }

    private fun referenceDependencies() = PetHistoryReferenceDependencies(
        breedCatalog = PetBreedCatalog(),
        referencePresenter = PetHistoryReferencePresenter(),
        breedReferencePresenter = PetHistoryBreedReferencePresenter(clock = clock),
    )

    @Test
    fun `initial viewport stays selected while references cover scrollable factual history`() = runBlocking {
        val oldDate = LocalDate.of(2026, 1, 20)
        val recentDate = LocalDate.of(2026, 3, 20)
        val puppy = pet("puppy", "Бим").copy(
            species = PetSpecies.DOG,
            sex = PetSex.MALE,
            birthDate = PartialBirthDate.Day(oldDate.minusDays(100)),
            dogAdultWeightCategory = DogAdultWeightCategory.III,
        )
        val history = MutableStateFlow(
            listOf(
                measurement("old", puppy.id, "2026-01-20T10:00:00Z", 4.0),
                measurement("recent", puppy.id, "2026-03-20T10:00:00Z", 5.0),
            ),
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            puppy.id,
            FakeRepository(pets = mapOf(puppy.id to puppy), histories = mapOf(puppy.id to history)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()

        val state = owner.uiState.value
        assertEquals(ChartRangePreset.LAST_30_DAYS, state.rangePreset)
        assertEquals(LocalDate.of(2026, 2, 28), state.startDate)
        assertEquals(LocalDate.of(2026, 3, 29), state.endDateInclusive)
        assertEquals(listOf("recent", "old"), state.measurements.map { it.id })
        assertEquals(2, state.series.points.size)
        val reference = state.weightReference as PetHistoryWeightReference.Available
        assertEquals(oldDate, reference.segments.first().first().date)
        assertEquals(state.endDateInclusive, reference.segments.last().last().date)
        assertEquals(oldDate, state.breedReferenceTimeline.first().date)
        assertEquals(state.endDateInclusive, state.breedReferenceTimeline.last().date)
        val displayed = petWeightDisplayedSeries(
            state.series.points,
            state.weightReference,
            state.breedReferenceTimeline,
            zone,
        )
        val oldTooltip = formatPetWeightDisplayedMarker(
            history.value.first { it.id == "old" }.measuredAt.toEpochMilli(),
            displayed,
            zone,
            Locale.US,
        )
        assertTrue(oldTooltip, oldTooltip.contains("Фактический вес: 4.00 кг"))
        assertTrue(oldTooltip, oldTooltip.contains("Нижняя"))
        assertTrue(oldTooltip, oldTooltip.contains("Верхняя"))
        scope.cancel()
    }

    @Test
    fun `custom viewport keeps both reference pipelines across long history in both pan directions`() = runBlocking {
        val oldDate = LocalDate.of(2001, 1, 15)
        val futureDate = LocalDate.of(2026, 6, 15)
        val russianBlue = pet("russian-blue-pan", "Луна").copy(
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            breedId = BreedId("VBO:0100200"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2000, 1, 1)),
        )
        val old = measurement("old", russianBlue.id, "2001-01-15T10:00:00Z", 3.2)
        val future = measurement("future", russianBlue.id, "2026-06-15T10:00:00Z", 4.1)
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            russianBlue.id,
            FakeRepository(
                pets = mapOf(russianBlue.id to russianBlue),
                histories = mapOf(russianBlue.id to MutableStateFlow(listOf(old, future))),
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        val collector = scope.launch { owner.uiState.collect() }
        yield()

        owner.setDateRange(LocalDate.of(2014, 4, 1), LocalDate.of(2014, 4, 30))
        yield()

        val state = owner.uiState.value
        assertEquals(ChartRangePreset.CUSTOM, state.rangePreset)
        assertEquals(LocalDate.of(2014, 4, 1), state.startDate)
        assertEquals(LocalDate.of(2014, 4, 30), state.endDateInclusive)
        assertEquals(listOf("future", "old"), state.measurements.map { it.id })
        assertEquals(listOf(old.measuredAt, future.measuredAt), state.series.points.map { it.measuredAt })

        val resolved = state.weightReference as PetHistoryWeightReference.Available
        val resolvedPoints = resolved.segments.flatMap { it.points }
        assertEquals(oldDate, resolvedPoints.first().date)
        assertEquals(futureDate, resolvedPoints.last().date)
        assertTrue(resolvedPoints.size <= MAX_REFERENCE_CHART_SAMPLES)
        assertEquals(MAX_REFERENCE_CHART_SAMPLES, state.breedReferenceTimeline.size)
        assertEquals(oldDate, state.breedReferenceTimeline.first().date)
        assertEquals(futureDate, state.breedReferenceTimeline.last().date)

        val displayed = petWeightDisplayedSeries(
            state.series.points,
            state.weightReference,
            state.breedReferenceTimeline,
            zone,
        )
        listOf(old to "3.20", future to "4.10").forEach { (measurement, weight) ->
            val tooltip = formatPetWeightDisplayedMarker(
                measurement.measuredAt.toEpochMilli(),
                displayed,
                zone,
                Locale.US,
            )
            assertTrue(tooltip, tooltip.contains("Фактический вес: $weight кг"))
            assertTrue(tooltip, tooltip.contains("Нижняя"))
            assertTrue(tooltip, tooltip.contains("Верхняя"))
        }

        collector.cancelAndJoin()
        owner.close()
        scope.cancel()
    }

    @Test
    fun `unrenderable instants stay in history but cannot poison chart and reference domain`() = runBlocking {
        val russianBlue = pet("extreme", "Луна").copy(
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            breedId = BreedId("VBO:0100200"),
            birthDate = PartialBirthDate.Day(LocalDate.of(2020, 1, 1)),
        )
        val normal = measurement("normal", russianBlue.id, "2026-03-20T10:15:00Z", 4.1)
        val minimum = measurement("minimum", russianBlue.id, "2026-03-19T10:15:00Z", 3.9)
            .copy(measuredAt = Instant.MIN)
        val maximum = measurement("maximum", russianBlue.id, "2026-03-21T10:15:00Z", 4.2)
            .copy(measuredAt = Instant.MAX)
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            russianBlue.id,
            FakeRepository(
                pets = mapOf(russianBlue.id to russianBlue),
                histories = mapOf(russianBlue.id to MutableStateFlow(listOf(minimum, normal, maximum))),
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        val collector = scope.launch { owner.uiState.collect() }
        yield()

        val state = owner.uiState.value
        assertNull(state.errorMessage)
        assertEquals(setOf("minimum", "normal", "maximum"), state.measurements.map { it.id }.toSet())
        assertEquals(listOf(normal.measuredAt), state.series.points.map { it.measuredAt })
        val reference = state.weightReference as PetHistoryWeightReference.Available
        assertEquals(state.startDate, reference.segments.first().first().date)
        assertEquals(state.endDateInclusive, reference.segments.last().last().date)

        collector.cancelAndJoin()
        owner.close()
        scope.cancel()
    }

    @Test
    fun `initial range keeps empty history on last 30 calendar days`() = runBlocking {
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

        val state = owner.uiState.value
        assertEquals(ChartRangePreset.LAST_30_DAYS, state.rangePreset)
        assertEquals(LocalDate.of(2026, 2, 28), state.startDate)
        assertEquals(LocalDate.of(2026, 3, 29), state.endDateInclusive)
        assertTrue(state.content is PetHistoryContent.Empty)
        assertTrue(state.series.points.isEmpty())
        assertEquals(30, state.breedReferenceTimeline.size)
        assertEquals(state.startDate, state.breedReferenceTimeline.first().date)
        assertEquals(state.endDateInclusive, state.breedReferenceTimeline.last().date)
        scope.cancel()
    }

    @Test
    fun `manual all range uses complete measurement bounds`() = runBlocking {
        val oldDate = LocalDate.of(2026, 1, 20)
        val recentDate = LocalDate.of(2026, 3, 20)
        val history = MutableStateFlow(
            listOf(
                measurement("old", luna.id, "2026-01-20T10:00:00Z", 4.0),
                measurement("recent", luna.id, "2026-03-20T10:00:00Z", 5.0),
            ),
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to history)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        val collector = scope.launch { owner.uiState.collect() }
        yield()

        owner.selectRangePreset(ChartRangePreset.ALL)
        yield()

        val state = owner.uiState.value
        assertEquals(ChartRangePreset.ALL, state.rangePreset)
        assertEquals(oldDate, state.startDate)
        assertEquals(recentDate, state.endDateInclusive)
        assertEquals(listOf("recent", "old"), state.measurements.map { it.id })
        assertEquals(2, state.series.points.size)
        collector.cancelAndJoin()
        scope.cancel()
    }

    @Test
    fun `selected dates change chart viewport while list and series keep full history`() = runBlocking {
        val old = measurement("old", luna.id, "2025-12-01T10:00:00Z", 3.8)
        val recent = measurement("recent", luna.id, "2026-03-20T10:00:00Z", 4.25)
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(
                pets = mapOf(luna.id to luna),
                histories = mapOf(luna.id to MutableStateFlow(listOf(old, recent))),
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        val collector = scope.launch { owner.uiState.collect() }
        yield()

        owner.selectRangePreset(ChartRangePreset.LAST_30_DAYS)
        yield()

        assertEquals(listOf("recent", "old"), owner.uiState.value.measurements.map { it.id })
        assertEquals(LocalDate.of(2026, 2, 28), owner.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 3, 29), owner.uiState.value.endDateInclusive)
        assertEquals(listOf(old.measuredAt, recent.measuredAt), owner.uiState.value.series.points.map { it.measuredAt })
        collector.cancelAndJoin()
        scope.cancel()
    }

    @Test
    fun `editor keeps draft on failure blocks double save and returns to updated row on success`() = runBlocking {
        val original = measurement("one", luna.id, "2026-03-20T10:00:00Z", 4.12)
        val history = MutableStateFlow(listOf(original))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var calls = 0
        var fail = true
        val repository = FakeRepository(
            pets = mapOf(luna.id to luna),
            histories = mapOf(luna.id to history),
            updateWeightBlock = { _, id, weight ->
                calls++
                saveStarted.complete(Unit)
                releaseSave.await()
                if (fail) error("database unavailable")
                original.copy(id = id, petWeightKg = weight, isManuallyEdited = true).also {
                    history.value = listOf(it)
                }
            },
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(luna.id, repository, scope, clock, zone, Locale.US)
        val collector = scope.launch { owner.uiState.collect() }
        yield()

        owner.editMeasurement("one")
        owner.changeEditedWeight("4,125")
        owner.saveEditedWeight()
        owner.saveEditedWeight()
        saveStarted.await()
        assertEquals(1, calls)
        releaseSave.complete(Unit)
        yield()
        assertEquals("4,125", owner.uiState.value.weightEditor?.weightInput)
        assertEquals("Не удалось сохранить изменения. Попробуйте ещё раз", owner.uiState.value.weightEditor?.saveError)

        fail = false
        owner.saveEditedWeight()
        yield()
        yield()
        assertNull(owner.uiState.value.weightEditor)
        assertEquals("one", owner.uiState.value.scrollToMeasurementId)
        assertEquals(4.125, owner.uiState.value.measurements.single().weightKg, 0.0)
        collector.cancelAndJoin()
        scope.cancel()
    }

    @Test
    fun `saved earlier measurement expands range and deleting it keeps existing history visible`() = runBlocking {
        val old = measurement("old", luna.id, "1800-01-01T10:00:00Z", 4.125)
        val existing = measurement("existing", luna.id, "2026-03-20T10:00:00Z", 4.25)
        val history = MutableStateFlow(listOf(old, existing))
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to history)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        var collector = scope.launch { owner.uiState.collect() }
        yield()
        owner.selectRangePreset(ChartRangePreset.LAST_30_DAYS)
        yield()
        val initialEndDate = owner.uiState.value.endDateInclusive

        owner.showSavedMeasurement(com.palixander.scalesync.domain.ManualWeightResult.Saved(old.id, old.measuredAt))
        yield()

        assertEquals(LocalDate.of(1800, 1, 1), owner.uiState.value.startDate)
        assertEquals(initialEndDate, owner.uiState.value.endDateInclusive)
        assertEquals(ChartRangePreset.CUSTOM, owner.uiState.value.rangePreset)
        assertEquals(listOf("existing", "old"), owner.uiState.value.measurements.map { it.id })
        collector.cancelAndJoin()
        collector = scope.launch { owner.uiState.collect() }
        yield()
        assertEquals("old", owner.uiState.value.scrollToMeasurementId)
        owner.callbacks.onScrollToMeasurementHandled()
        yield()
        assertNull(owner.uiState.value.scrollToMeasurementId)

        owner.requestDelete(old.id)
        owner.confirmDelete()
        history.value = listOf(existing)
        yield()

        assertEquals(listOf("existing"), owner.uiState.value.measurements.map { it.id })
        assertEquals(LocalDate.of(1800, 1, 1), owner.uiState.value.startDate)
        assertEquals(initialEndDate, owner.uiState.value.endDateInclusive)
        collector.cancelAndJoin()
        scope.cancel()
    }

    @Test
    fun `saved later measurement expands only range end`() = runBlocking {
        val later = measurement("later", luna.id, "2026-04-10T10:00:00Z", 4.5)
        val history = MutableStateFlow(listOf(later))
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
        owner.selectRangePreset(ChartRangePreset.LAST_30_DAYS)
        yield()
        val initialStartDate = owner.uiState.value.startDate

        owner.showSavedMeasurement(com.palixander.scalesync.domain.ManualWeightResult.Saved(later.id, later.measuredAt))
        yield()

        assertEquals(initialStartDate, owner.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 4, 10), owner.uiState.value.endDateInclusive)
        assertEquals(ChartRangePreset.CUSTOM, owner.uiState.value.rangePreset)
        assertEquals(listOf("later"), owner.uiState.value.measurements.map { it.id })
        assertEquals("later", owner.uiState.value.scrollToMeasurementId)
        scope.cancel()
    }

    @Test
    fun `saved measurement inside range preserves preset and boundaries`() = runBlocking {
        val inside = measurement("inside", luna.id, "2026-03-20T10:00:00Z", 4.25)
        val history = MutableStateFlow(listOf(inside))
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
        val initialStartDate = owner.uiState.value.startDate
        val initialEndDate = owner.uiState.value.endDateInclusive

        owner.showSavedMeasurement(com.palixander.scalesync.domain.ManualWeightResult.Saved(inside.id, inside.measuredAt))
        yield()

        assertEquals(initialStartDate, owner.uiState.value.startDate)
        assertEquals(initialEndDate, owner.uiState.value.endDateInclusive)
        assertEquals(ChartRangePreset.LAST_30_DAYS, owner.uiState.value.rangePreset)
        assertEquals(listOf("inside"), owner.uiState.value.measurements.map { it.id })
        assertEquals("inside", owner.uiState.value.scrollToMeasurementId)
        scope.cancel()
    }

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
    fun `all presentation retains imported instants outside zoned local date range`() {
        val ordinary = measurement("ordinary", luna.id, "2026-03-20T10:00:00Z", 4.25)
        val extreme = ordinary.copy(id = "extreme", measuredAt = java.time.Instant.MAX)

        val (content, series) = petHistoryPresentation(
            listOf(ordinary, extreme),
            ChartDateRange(LocalDate.of(2026, 3, 20), LocalDate.MAX),
            ZoneOffset.UTC,
            Locale.US,
            includeAll = true,
        )

        val rows = (content as PetHistoryContent.Multiple).measurements
        assertEquals(listOf("extreme", "ordinary"), rows.map { it.id })
        assertEquals(2, series.points.size)
        assertTrue(rows.first().measuredAtText.isNotBlank())
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
    fun `pet repository emission refreshes selected pet and summary without reopening`() = runBlocking {
        val pets = MutableStateFlow(listOf(PetWithLatestWeight(luna, null)))
        val history = MutableStateFlow(emptyList<PetMeasurement>())
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(
                pets = mapOf(luna.id to luna),
                histories = mapOf(luna.id to history),
                observedPets = pets,
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()

        val updated = pet("luna", "Луна новая").copy(
            sex = com.palixander.scalesync.domain.PetSex.FEMALE,
        )
        pets.value = listOf(PetWithLatestWeight(updated, null))
        yield()

        assertEquals(updated, owner.uiState.value.pet)
        assertEquals(
            listOf(PetProfileSummaryItem("Пол", "Самка")),
            owner.uiState.value.profileSummary?.items,
        )
        assertTrue(owner.uiState.value.content is PetHistoryContent.Empty)
        scope.cancel()
    }

    @Test
    fun `removing selected pet becomes not found and clears stale profile`() = runBlocking {
        val pets = MutableStateFlow(listOf(PetWithLatestWeight(luna, null)))
        val history = MutableStateFlow(emptyList<PetMeasurement>())
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(
                pets = mapOf(luna.id to luna),
                histories = mapOf(luna.id to history),
                observedPets = pets,
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()

        pets.value = emptyList()
        yield()

        assertTrue(owner.uiState.value.isNotFound)
        assertFalse(owner.uiState.value.isLoading)
        assertNull(owner.uiState.value.pet)
        assertNull(owner.uiState.value.profileSummary)
        assertTrue(owner.uiState.value.content is PetHistoryContent.Empty)
        scope.cancel()
    }

    @Test
    fun `pet observation is isolated to current selection while measurements remain`() = runBlocking {
        val second = pet("second", "Бим")
        val pets = MutableStateFlow(
            listOf(PetWithLatestWeight(luna, null), PetWithLatestWeight(second, null)),
        )
        val lunaHistory = MutableStateFlow(
            listOf(measurement("luna-row", luna.id, "2026-03-20T10:00:00Z", 4.25)),
        )
        val secondHistory = MutableStateFlow(
            listOf(measurement("second-row", second.id, "2026-03-21T10:00:00Z", 5.0)),
        )
        val scope = testScope()
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(
                pets = mapOf(luna.id to luna, second.id to second),
                histories = mapOf(luna.id to lunaHistory, second.id to secondHistory),
                observedPets = pets,
            ),
            scope,
            clock,
            zone,
            Locale.US,
        )
        scope.launch { owner.uiState.collect() }
        yield()

        owner.selectPet(second.id)
        yield()
        pets.value = listOf(
            PetWithLatestWeight(pet("luna", "Чужое обновление"), null),
            PetWithLatestWeight(pet("second", "Бим новый"), null),
        )
        yield()

        assertEquals(second.id, owner.uiState.value.petId)
        assertEquals("Бим новый", owner.uiState.value.pet?.displayName)
        assertEquals(listOf("second-row"), owner.uiState.value.measurements.map { it.id })
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

    @Test
    fun `closing repeated owners stops upstream collections without cancelling parent`() = runBlocking {
        var activeCollections = 0
        var cancellations = 0
        val parentJob = SupervisorJob()
        val scope = CoroutineScope(parentJob + Dispatchers.Unconfined)

        repeat(3) { index ->
            val cancelled = CompletableDeferred<Unit>()
            val instrumentedHistory = flow<List<PetMeasurement>> {
                activeCollections += 1
                try {
                    emit(emptyList())
                    awaitCancellation()
                } finally {
                    activeCollections -= 1
                    cancellations += 1
                    cancelled.complete(Unit)
                }
            }
            val owner = PetHistoryStateOwner(
                luna.id,
                FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to instrumentedHistory)),
                scope,
                clock,
                zone,
                Locale.US,
            )
            val collector = launch { owner.uiState.collect() }
            yield()
            assertEquals(1, activeCollections)

            owner.close()
            owner.close()
            cancelled.await()

            assertEquals(0, activeCollections)
            assertEquals(index + 1, cancellations)
            assertTrue(parentJob.isActive)
            collector.cancelAndJoin()
        }

        parentJob.cancel()
    }

    @Test
    fun `parent cancellation stops owner upstream collection`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val instrumentedHistory = flow<List<PetMeasurement>> {
            try {
                emit(emptyList())
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val parentJob = SupervisorJob()
        val scope = CoroutineScope(parentJob + Dispatchers.Unconfined)
        val owner = PetHistoryStateOwner(
            luna.id,
            FakeRepository(pets = mapOf(luna.id to luna), histories = mapOf(luna.id to instrumentedHistory)),
            scope,
            clock,
            zone,
            Locale.US,
        )
        val collector = launch { owner.uiState.collect() }
        yield()

        parentJob.cancel()
        cancelled.await()

        assertFalse(parentJob.isActive)
        collector.cancelAndJoin()
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
    private val observedPets: Flow<List<PetWithLatestWeight>> = emptyFlow(),
    private val getPetBlock: (suspend (PetId) -> Pet?)? = null,
    private val deleteBlock: suspend (PetId, String) -> Unit = { _, _ -> error("unused") },
    private val updateWeightBlock: suspend (PetId, String, Double) -> PetMeasurement = { _, _, _ -> error("unused") },
) : PetRepository {
    val deleteCalls = mutableListOf<Pair<PetId, String>>()
    override fun observePets(): Flow<List<PetWithLatestWeight>> = observedPets
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
    override suspend fun updateMeasurementWeight(
        petId: PetId,
        measurementId: String,
        petWeightKg: Double,
    ): PetMeasurement = updateWeightBlock(petId, measurementId, petWeightKg)
    override suspend fun recordCompletedMeasurement(
        petId: PetId,
        measuredAt: Instant,
        firstWeightKg: Double,
        secondWeightKg: Double,
    ): PetMeasurement = error("unused")
}
