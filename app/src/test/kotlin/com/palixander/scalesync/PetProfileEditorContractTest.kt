package com.palixander.scalesync

import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PetProfileEditorContractTest {
    private val today = LocalDate.of(2026, 8, 31)
    private val breedCatalog = PetBreedCatalog()
    private val dogMixed = requireNotNull(
        breedCatalog.search("Лабрадор", PetSpecies.DOG).singleOrNull(),
    ).let(PetBreedSelection::Available)

    @Test
    fun createDraftConvertsEverySupportedValueToNewPet() {
        val draft = PetProfileDraft(
            mode = PetProfileEditorMode.Create,
            displayName = "  Луна  ",
            species = PetSpecies.DOG,
            sex = PetSex.FEMALE,
            breed = dogMixed,
            birthDate = PetBirthDateInput.Month("2020", "2"),
            dogAdultWeightCategory = DogAdultWeightCategory.III,
        )

        val result = validatePetProfileDraft(draft, today)

        assertTrue(result.isValid)
        assertNull(result.petUpdate)
        assertEquals("Луна", result.newPet?.displayName)
        assertEquals(PetSpecies.DOG, result.newPet?.species)
        assertEquals(PetSex.FEMALE, result.newPet?.sex)
        assertEquals(dogMixed.id, result.newPet?.breedId)
        assertEquals(PartialBirthDate.Month(YearMonth.of(2020, 2)), result.newPet?.birthDate)
        assertEquals(DogAdultWeightCategory.III, result.newPet?.dogAdultWeightCategory)
    }

    @Test
    fun editDraftRoundTripsPersistedProfileToPetUpdate() {
        val original = pet(
            id = "luna",
            name = "Луна",
            species = PetSpecies.DOG,
            sex = PetSex.FEMALE,
            breedId = dogMixed.id,
            birthDate = PartialBirthDate.Day(LocalDate.of(2020, 2, 29)),
            category = DogAdultWeightCategory.IV,
        )

        val draft = PetProfileDraft.edit(original, breedCatalog)
        val result = validatePetProfileDraft(draft, today, listOf(original))

        assertTrue(result.isValid)
        assertNull(result.newPet)
        assertEquals(original.id, result.petUpdate?.id)
        assertEquals(original.displayName, result.petUpdate?.displayName)
        assertEquals(original.species, result.petUpdate?.species)
        assertEquals(original.sex, result.petUpdate?.sex)
        assertEquals(original.breedId, result.petUpdate?.breedId)
        assertEquals(original.birthDate, result.petUpdate?.birthDate)
        assertEquals(original.dogAdultWeightCategory, result.petUpdate?.dogAdultWeightCategory)
    }

    @Test
    fun catBreedRoundTripsThroughCreateAndEditContracts() {
        val maineCoon = breedCatalog.search("Maine Coon Cat", PetSpecies.CAT).single()
        val selection = PetBreedSelection.Available(maineCoon)
        val created = validatePetProfileDraft(
            PetProfileDraft.create().copy(
                displayName = "Барсик",
                species = PetSpecies.CAT,
                breed = selection,
            ),
            today,
        ).newPet

        assertEquals(PetSpecies.CAT, maineCoon.species)
        assertEquals(maineCoon.id, created?.breedId)
        val persisted = pet(
            id = "barsik",
            name = "Барсик",
            species = PetSpecies.CAT,
            breedId = created?.breedId,
        )
        val update = validatePetProfileDraft(
            PetProfileDraft.edit(persisted, breedCatalog),
            today,
            listOf(persisted),
        ).petUpdate
        assertEquals(PetSpecies.CAT, update?.species)
        assertEquals(maineCoon.id, update?.breedId)
    }

    @Test
    fun catalogExposesOnlyCatBreedsDeclaredByWeightReferenceScopes() {
        val expectedIds = WeightReferenceSnapshot.bundled().manifest.scopes
            .filter { it.species.name == "CAT" && it.breedId != null }
            .mapNotNull { it.breedId }
            .toSet()
        val cats = breedCatalog.search("", PetSpecies.CAT)

        assertEquals(expectedIds, cats.map { it.id.value }.toSet())
        assertTrue(cats.all { it.species == PetSpecies.CAT })
        assertTrue(cats.size < com.palixander.scalesync.core.breed.BreedCatalog.bundled()
            .all(com.palixander.scalesync.core.breed.BreedSpecies.CAT).size)
        assertTrue(breedCatalog.search("Maine Coon Cat", PetSpecies.CAT).isNotEmpty())
        assertTrue(breedCatalog.search("мейн", PetSpecies.CAT).isNotEmpty())
        assertTrue(breedCatalog.search("мейн", PetSpecies.DOG).isEmpty())
    }

    @Test
    fun unavailableSnapshotsDegradeTheirSpeciesWithoutLeakingFullCatalog() {
        val noDogs = PetBreedCatalog(
            snapshotResult = BreedReferenceSnapshotLoadResult.Unavailable("test"),
        )
        val noCats = PetBreedCatalog(weightReferenceSnapshot = null)

        assertTrue(noDogs.search("", PetSpecies.DOG).isEmpty())
        assertTrue(noDogs.search("", PetSpecies.CAT).isNotEmpty())
        assertTrue(noCats.search("", PetSpecies.CAT).isEmpty())
        assertTrue(noCats.search("", PetSpecies.DOG).isNotEmpty())
    }

    @Test
    fun reducerFillsAndClearsNullableFieldsWithoutInventingDateParts() {
        var state = PetProfileEditorState(
            PetProfileDraft.create().copy(
                displayName = "Луна",
                species = PetSpecies.DOG,
            ),
        )

        state = reduce(state, PetProfileAction.SexChanged(PetSex.FEMALE))
        state = reduce(state, PetProfileAction.BreedChanged(dogMixed))
        state = reduce(state, PetProfileAction.BirthDateChanged(PetBirthDateInput.Year("2020")))
        state = reduce(
            state,
            PetProfileAction.DogAdultWeightCategoryChanged(DogAdultWeightCategory.II),
        )

        assertEquals(PetSex.FEMALE, state.draft.sex)
        assertEquals(dogMixed, state.draft.breed)
        assertEquals(PetBirthDateInput.Year("2020"), state.draft.birthDate)
        assertEquals(DogAdultWeightCategory.II, state.draft.dogAdultWeightCategory)

        state = reduce(state, PetProfileAction.SexChanged(null))
        state = reduce(state, PetProfileAction.BirthDateChanged(PetBirthDateInput.Empty))
        state = reduce(state, PetProfileAction.BreedChanged(null))

        assertNull(state.draft.sex)
        assertNull(state.draft.breed)
        assertEquals(PetBirthDateInput.Empty, state.draft.birthDate)
        assertEquals(DogAdultWeightCategory.II, state.draft.dogAdultWeightCategory)
        val saved = validatePetProfileDraft(state.draft, today).newPet
        assertNull(saved?.sex)
        assertNull(saved?.breedId)
        assertNull(saved?.birthDate)
        assertEquals(DogAdultWeightCategory.II, saved?.dogAdultWeightCategory)
    }

    @Test
    fun dateInputsPreserveYearMonthAndDayPrecision() {
        val cases = listOf(
            PetBirthDateInput.Year("2020") to PartialBirthDate.Year(Year.of(2020)),
            PetBirthDateInput.Month("2020", "2") to PartialBirthDate.Month(YearMonth.of(2020, 2)),
            PetBirthDateInput.Day("2020", "2", "29") to
                PartialBirthDate.Day(LocalDate.of(2020, 2, 29)),
        )

        cases.forEach { (input, expected) ->
            val validation = validatePetBirthDateInput(input, today)

            assertTrue(input.precision != null)
            assertTrue(validation.isValid)
            assertEquals(expected, validation.value)
            assertEquals(input, PetBirthDateInput.from(validation.value))
        }
    }

    @Test
    fun dateValidationDistinguishesIncompleteInvalidAndFutureInputAgainstExplicitToday() {
        val cases = listOf(
            PetBirthDateInput.Month("2020", "") to PetBirthDateValidationError.INCOMPLETE,
            PetBirthDateInput.Day("2021", "02", "29") to PetBirthDateValidationError.INVALID,
            PetBirthDateInput.Year("not-a-year") to PetBirthDateValidationError.INVALID,
            PetBirthDateInput.Year("2027") to PetBirthDateValidationError.FUTURE,
            PetBirthDateInput.Month("2026", "09") to PetBirthDateValidationError.FUTURE,
            PetBirthDateInput.Day("2026", "09", "01") to PetBirthDateValidationError.FUTURE,
        )

        cases.forEach { (input, expectedError) ->
            val validation = validatePetBirthDateInput(input, today)

            assertFalse(validation.isValid)
            assertEquals(expectedError, validation.error)
            assertNull(validation.value)
        }
        assertTrue(validatePetBirthDateInput(PetBirthDateInput.Empty, today).isValid)
    }

    @Test
    fun catalogSearchIsLocalizedAliasAwareAndAlwaysSpeciesFiltered() {
        val localized = breedCatalog.search("Лабрадор", PetSpecies.DOG)
        val alias = breedCatalog.search("Russian Black Terrier", PetSpecies.DOG)

        assertEquals(1, localized.size)
        assertTrue(localized.all { it.species == PetSpecies.DOG })
        assertEquals(listOf(BreedId("VBO:0200174")), alias.map(PetBreedOption::id))
        assertTrue(alias.all { it.species == PetSpecies.DOG })
        assertTrue(breedCatalog.search("Лабрадор", PetSpecies.CAT).isEmpty())
        assertTrue(breedCatalog.search("", PetSpecies.UNSPECIFIED).isEmpty())
    }

    @Test
    fun `supported cat names are Russian while canonical names and aliases remain searchable`() {
        val expected = mapOf(
            "Domestic Shorthair" to "Домашняя короткошёрстная",
            "Scottish Fold" to "Шотландская вислоухая",
            "Siberian Forest Cat" to "Сибирская",
        )

        expected.forEach { (query, displayName) ->
            val option = breedCatalog.search(query, PetSpecies.CAT).single { it.displayName == displayName }
            assertEquals(displayName, option.displayName)
            assertTrue(option.canonicalName.first().isUpperCase())
        }
    }

    @Test
    fun `catalog keeps the supported dog set independent from cat options`() {
        val dogOptions = breedCatalog.search("", PetSpecies.DOG)

        assertEquals(10, dogOptions.size)
        assertTrue(dogOptions.all { it.species == PetSpecies.DOG })
        assertTrue(breedCatalog.search("", PetSpecies.CAT).all { it.species == PetSpecies.CAT })
    }

    @Test
    fun `editor catalog remains constructible when breed snapshot is unavailable`() {
        listOf("corrupt snapshot", "unsupported schema", "checksum mismatch").forEach { reason ->
            val unavailableCatalog = PetBreedCatalog(
                snapshotResult = BreedReferenceSnapshotLoadResult.Unavailable(reason),
            )

            assertTrue(unavailableCatalog.search("", PetSpecies.DOG).isEmpty())
            assertTrue(
                unavailableCatalog.resolve(BreedId("VBO:0200800"), PetSpecies.DOG) is
                    PetBreedSelection.Unavailable,
            )
            assertNull(
                PetProfileDraft.edit(
                    pet("dog", "Dog", PetSpecies.DOG, breedId = null),
                    unavailableCatalog,
                ).breed,
            )
        }
    }

    @Test
    fun unavailableSavedBreedIdRemainsVisibleAndRoundTripsUntilExplicitlyCleared() {
        val unknownId = BreedId("external:dog:rare-breed")
        val original = pet(
            id = "rare",
            name = "Редкая",
            species = PetSpecies.DOG,
            breedId = unknownId,
        )

        val draft = PetProfileDraft.edit(original, breedCatalog)
        val selection = draft.breed as PetBreedSelection.Unavailable

        assertEquals(unknownId, selection.id)
        assertEquals(PetSpecies.DOG, selection.species)
        assertTrue(petBreedLabel(selection).contains(unknownId.value))
        assertEquals(unknownId, validatePetProfileDraft(draft, today).petUpdate?.breedId)
    }

    @Test
    fun validationRejectsBreedFromAnotherSpeciesAndInapplicableCategory() {
        val dogBreed = breedCatalog.search("Лабрадор", PetSpecies.DOG).first()
        val draft = PetProfileDraft(
            mode = PetProfileEditorMode.Create,
            displayName = "Барсик",
            species = PetSpecies.CAT,
            breed = PetBreedSelection.Available(dogBreed),
            dogAdultWeightCategory = DogAdultWeightCategory.I,
        )

        val result = validatePetProfileDraft(draft, today)

        assertEquals(PetBreedValidationError.SPECIES_MISMATCH, result.errors.breed)
        assertEquals(
            DogAdultWeightCategoryValidationError.NOT_APPLICABLE,
            result.errors.dogAdultWeightCategory,
        )
        assertNull(result.profile)
    }

    @Test
    fun duplicateNameValidationExcludesEditedPetButRejectsOtherPetAndCreate() {
        val luna = pet("luna", "Луна", PetSpecies.DOG)
        val barsik = pet("barsik", "Барсик", PetSpecies.CAT)
        val pets = listOf(luna, barsik)

        val unchanged = validatePetProfileDraft(PetProfileDraft.edit(luna), today, pets)
        val renamed = validatePetProfileDraft(
            PetProfileDraft.edit(luna).copy(displayName = " БАРСИК "),
            today,
            pets,
        )
        val created = validatePetProfileDraft(
            PetProfileDraft.create().copy(displayName = "луна", species = PetSpecies.DOG),
            today,
            pets,
        )

        assertTrue(unchanged.isValid)
        assertEquals(PetNameValidationError.DUPLICATE, renamed.errors.displayName)
        assertEquals(PetNameValidationError.DUPLICATE, created.errors.displayName)
    }

    @Test
    fun speciesChangeWaitsForConfirmationAndCancelLeavesWholeDraftUntouched() {
        val birthDate = PetBirthDateInput.Year("2020")
        val initial = PetProfileEditorState(
            PetProfileDraft(
                mode = PetProfileEditorMode.Create,
                displayName = "Луна",
                species = PetSpecies.DOG,
                sex = PetSex.FEMALE,
                breed = dogMixed,
                birthDate = birthDate,
                dogAdultWeightCategory = DogAdultWeightCategory.III,
            ),
        )

        val requested = reduce(
            initial,
            PetProfileAction.SpeciesChangeRequested(PetSpecies.CAT),
        )

        assertSame(initial.draft, requested.draft)
        assertEquals(PetSpecies.CAT, requested.pendingSpeciesChange?.requestedSpecies)
        assertTrue(requireNotNull(requested.pendingSpeciesChange).clearBreed)
        assertTrue(requireNotNull(requested.pendingSpeciesChange).clearDogAdultWeightCategory)
        assertEquals(initial, reduce(requested, PetProfileAction.CancelSpeciesChange))
    }

    @Test
    fun confirmedSpeciesChangeClearsOnlyIncompatibleFields() {
        val birthDate = PetBirthDateInput.Year("2020")
        val initial = PetProfileEditorState(
            PetProfileDraft(
                mode = PetProfileEditorMode.Create,
                displayName = "Луна",
                species = PetSpecies.DOG,
                sex = PetSex.FEMALE,
                breed = dogMixed,
                birthDate = birthDate,
                dogAdultWeightCategory = DogAdultWeightCategory.III,
            ),
        )

        val confirmed = reduce(
            reduce(initial, PetProfileAction.SpeciesChangeRequested(PetSpecies.CAT)),
            PetProfileAction.ConfirmSpeciesChange,
        )

        assertEquals(PetSpecies.CAT, confirmed.draft.species)
        assertNull(confirmed.draft.breed)
        assertNull(confirmed.draft.dogAdultWeightCategory)
        assertEquals(PetSex.FEMALE, confirmed.draft.sex)
        assertEquals(birthDate, confirmed.draft.birthDate)
        assertEquals("Луна", confirmed.draft.displayName)
        assertNull(confirmed.pendingSpeciesChange)
    }

    @Test
    fun mappedBreedSelectionAssignsSaltCategoryAndReplacesPreviousAutomaticValue() {
        var state = PetProfileEditorState(
            PetProfileDraft.create().copy(displayName = "Луна"),
        )
        state = reduce(state, PetProfileAction.SpeciesChangeRequested(PetSpecies.DOG))
        val labrador = PetBreedSelection.Available(
            breedCatalog.search("Лабрадор", PetSpecies.DOG).single(),
        )
        val beagle = PetBreedSelection.Available(
            breedCatalog.search("Бигль", PetSpecies.DOG).single(),
        )

        assertNull(state.pendingSpeciesChange)
        state = reduce(state, PetProfileAction.BreedChanged(labrador))
        assertEquals(DogAdultWeightCategory.V, state.draft.dogAdultWeightCategory)
        assertEquals(labrador.id, state.automaticallyAssignedDogCategory?.breedId)

        state = reduce(state, PetProfileAction.BreedChanged(beagle))
        assertEquals(DogAdultWeightCategory.III, state.draft.dogAdultWeightCategory)
        assertEquals(beagle.id, state.automaticallyAssignedDogCategory?.breedId)
        val saved = validatePetProfileDraft(state.draft, today).newPet
        assertEquals(beagle.id, saved?.breedId)
        assertEquals(DogAdultWeightCategory.III, saved?.dogAdultWeightCategory)
    }

    @Test
    fun openingLegacyMappedProfileAssignsCategoryAndRestoresAutomaticProvenanceAfterSave() {
        val labrador = PetBreedSelection.Available(
            breedCatalog.search("Лабрадор", PetSpecies.DOG).single(),
        )
        val legacyPet = pet(
            id = "legacy",
            name = "Луна",
            species = PetSpecies.DOG,
            breedId = labrador.id,
        )

        val opened = PetProfileEditorState.edit(legacyPet, breedCatalog)

        assertEquals(DogAdultWeightCategory.V, opened.draft.dogAdultWeightCategory)
        assertEquals(
            AutomaticallyAssignedDogCategory(labrador.id, DogAdultWeightCategory.V),
            opened.automaticallyAssignedDogCategory,
        )
        val savedCategory = validatePetProfileDraft(opened.draft, today).petUpdate
            ?.dogAdultWeightCategory
        val reopened = PetProfileEditorState.edit(
            legacyPet.copy(dogAdultWeightCategory = savedCategory),
            breedCatalog,
        )
        assertEquals(opened.automaticallyAssignedDogCategory, reopened.automaticallyAssignedDogCategory)
    }

    @Test
    fun reopenedAutomaticCategoryChangesWithMappedBreedAndClearsForUnmappedOrNoBreed() {
        val labrador = PetBreedSelection.Available(
            breedCatalog.search("Лабрадор", PetSpecies.DOG).single(),
        )
        val beagle = PetBreedSelection.Available(
            breedCatalog.search("Бигль", PetSpecies.DOG).single(),
        )
        val unmapped = PetBreedSelection.Available(
            breedCatalog.search("Доберман", PetSpecies.DOG).single(),
        )
        val reopened = PetProfileEditorState.edit(
            pet(
                id = "saved",
                name = "Луна",
                species = PetSpecies.DOG,
                breedId = labrador.id,
                category = DogAdultWeightCategory.V,
            ),
            breedCatalog,
        )

        val mapped = reduce(reopened, PetProfileAction.BreedChanged(beagle))
        assertEquals(DogAdultWeightCategory.III, mapped.draft.dogAdultWeightCategory)
        assertEquals(beagle.id, mapped.automaticallyAssignedDogCategory?.breedId)

        val changedToUnmapped = reduce(reopened, PetProfileAction.BreedChanged(unmapped))
        assertNull(changedToUnmapped.draft.dogAdultWeightCategory)
        assertNull(changedToUnmapped.automaticallyAssignedDogCategory)

        val cleared = reduce(reopened, PetProfileAction.BreedChanged(null))
        assertNull(cleared.draft.dogAdultWeightCategory)
        assertNull(cleared.automaticallyAssignedDogCategory)
    }

    @Test
    fun openingManualOrUnmappedCategoryPreservesItWithoutAutomaticProvenance() {
        val labrador = breedCatalog.search("Лабрадор", PetSpecies.DOG).single()
        val doberman = breedCatalog.search("Доберман", PetSpecies.DOG).single()
        val manual = PetProfileEditorState.edit(
            pet(
                id = "manual",
                name = "Луна",
                species = PetSpecies.DOG,
                breedId = labrador.id,
                category = DogAdultWeightCategory.II,
            ),
            breedCatalog,
        )
        val unmapped = PetProfileEditorState.edit(
            pet(
                id = "unmapped",
                name = "Луна",
                species = PetSpecies.DOG,
                breedId = doberman.id,
                category = DogAdultWeightCategory.IV,
            ),
            breedCatalog,
        )
        val unavailable = PetProfileEditorState.edit(
            pet(
                id = "unavailable",
                name = "Луна",
                species = PetSpecies.DOG,
                breedId = labrador.id,
                category = DogAdultWeightCategory.I,
            ),
            PetBreedCatalog(
                snapshotResult = BreedReferenceSnapshotLoadResult.Unavailable("test"),
            ),
        )

        assertEquals(DogAdultWeightCategory.II, manual.draft.dogAdultWeightCategory)
        assertNull(manual.automaticallyAssignedDogCategory)
        assertEquals(DogAdultWeightCategory.IV, unmapped.draft.dogAdultWeightCategory)
        assertNull(unmapped.automaticallyAssignedDogCategory)
        assertEquals(DogAdultWeightCategory.I, unavailable.draft.dogAdultWeightCategory)
        assertNull(unavailable.automaticallyAssignedDogCategory)
    }

    @Test
    fun automaticCategoryDoesNotLeakToUnmappedOrOtherBreedButManualCategoryDoes() {
        val labrador = PetBreedSelection.Available(
            breedCatalog.search("Лабрадор", PetSpecies.DOG).single(),
        )
        val unmapped = PetBreedSelection.Available(
            breedCatalog.search("Доберман", PetSpecies.DOG).single(),
        )
        val initial = PetProfileEditorState(PetProfileDraft.create().copy(species = PetSpecies.DOG))

        val automatic = reduce(initial, PetProfileAction.BreedChanged(labrador))
        val changedToUnmapped = reduce(automatic, PetProfileAction.BreedChanged(unmapped))
        assertNull(changedToUnmapped.draft.dogAdultWeightCategory)
        assertNull(changedToUnmapped.automaticallyAssignedDogCategory)

        val changedToOther = reduce(automatic, PetProfileAction.BreedChanged(null))
        assertNull(changedToOther.draft.dogAdultWeightCategory)
        assertNull(changedToOther.automaticallyAssignedDogCategory)

        val manual = reduce(
            automatic,
            PetProfileAction.DogAdultWeightCategoryChanged(DogAdultWeightCategory.II),
        )
        val manualOnUnmapped = reduce(manual, PetProfileAction.BreedChanged(unmapped))
        assertEquals(DogAdultWeightCategory.II, manualOnUnmapped.draft.dogAdultWeightCategory)
        assertNull(manualOnUnmapped.automaticallyAssignedDogCategory)
    }

    @Test
    fun presentationFunctionsExposeStableRussianLabelsAndCategoryRanges() {
        assertEquals("Самец", petSexLabel(PetSex.MALE))
        assertEquals("Самка", petSexLabel(PetSex.FEMALE))
        assertEquals("Не указан", petSexLabel(null))
        assertEquals("Лабрадор-ретривер", petBreedLabel(dogMixed))
        assertEquals("Год", birthDatePrecisionLabel(BirthDatePrecision.YEAR))
        assertEquals("Месяц", birthDatePrecisionLabel(BirthDatePrecision.MONTH))
        assertEquals("День", birthDatePrecisionLabel(BirthDatePrecision.DAY))
        assertEquals(
            "02.2020 (месяц)",
            partialBirthDateLabel(PartialBirthDate.Month(YearMonth.of(2020, 2))),
        )
        assertEquals("I — < 6,5 кг", dogAdultWeightCategoryLabel(DogAdultWeightCategory.I))
        assertEquals("II — 6,5–9 кг", dogAdultWeightCategoryLabel(DogAdultWeightCategory.II))
        assertEquals("III — 9–15 кг", dogAdultWeightCategoryLabel(DogAdultWeightCategory.III))
        assertEquals("IV — 15–30 кг", dogAdultWeightCategoryLabel(DogAdultWeightCategory.IV))
        assertEquals("V — 30–40 кг", dogAdultWeightCategoryLabel(DogAdultWeightCategory.V))
    }

    private fun reduce(
        state: PetProfileEditorState,
        action: PetProfileAction,
    ): PetProfileEditorState = PetProfileReducer.reduce(state, action)

    private fun pet(
        id: String,
        name: String,
        species: PetSpecies,
        sex: PetSex? = null,
        breedId: BreedId? = null,
        birthDate: PartialBirthDate? = null,
        category: DogAdultWeightCategory? = null,
    ) = Pet(
        id = PetId(id),
        displayName = name,
        species = species,
        createdAt = Instant.parse("2020-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2020-01-01T00:00:00Z"),
        sex = sex,
        breedId = breedId,
        birthDate = birthDate,
        dogAdultWeightCategory = category,
    )
}
