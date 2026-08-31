package com.palixander.scalesync

import com.palixander.scalesync.core.breed.BreedKind
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
        breedCatalog.search("Метис", PetSpecies.DOG).singleOrNull(),
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
    fun reducerFillsAndClearsEveryNullableFieldWithoutInventingDateParts() {
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
        assertNull(state.draft.dogAdultWeightCategory)
        val saved = validatePetProfileDraft(state.draft, today).newPet
        assertNull(saved?.sex)
        assertNull(saved?.breedId)
        assertNull(saved?.birthDate)
        assertNull(saved?.dogAdultWeightCategory)
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
        val localized = breedCatalog.search("Абиссинская", PetSpecies.CAT)
        val alias = breedCatalog.search("Danish Mastiff", PetSpecies.DOG)

        assertEquals(listOf("VBO:0100000"), localized.map { it.id.value })
        assertTrue(localized.all { it.species == PetSpecies.CAT })
        assertTrue(alias.any { "Danish Mastiff" in it.aliases })
        assertTrue(alias.all { it.species == PetSpecies.DOG })
        assertTrue(breedCatalog.search("Danish Mastiff", PetSpecies.CAT).isEmpty())
        assertTrue(breedCatalog.search("", PetSpecies.UNSPECIFIED).isEmpty())
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
        val dogBreed = breedCatalog.search("Broholmer", PetSpecies.DOG).first()
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
    fun compatibleSpeciesAndBreedChangesNeedNoConfirmationAndKeepCategoryConsistent() {
        var state = PetProfileEditorState(
            PetProfileDraft.create().copy(displayName = "Луна"),
        )
        state = reduce(state, PetProfileAction.SpeciesChangeRequested(PetSpecies.DOG))
        state = reduce(state, PetProfileAction.BreedChanged(dogMixed))
        state = reduce(
            state,
            PetProfileAction.DogAdultWeightCategoryChanged(DogAdultWeightCategory.V),
        )
        val ordinaryDog = breedCatalog.search("Broholmer", PetSpecies.DOG).first()
        val catBreed = breedCatalog.search("Абиссинская", PetSpecies.CAT).single()

        assertNull(state.pendingSpeciesChange)
        assertEquals(DogAdultWeightCategory.V, state.draft.dogAdultWeightCategory)
        state = reduce(state, PetProfileAction.BreedChanged(PetBreedSelection.Available(ordinaryDog)))
        assertNull(state.draft.dogAdultWeightCategory)
        val unchanged = reduce(
            state,
            PetProfileAction.BreedChanged(PetBreedSelection.Available(catBreed)),
        )
        assertEquals(state, unchanged)
    }

    @Test
    fun presentationFunctionsExposeStableRussianLabelsAndCategoryRanges() {
        assertEquals("Самец", petSexLabel(PetSex.MALE))
        assertEquals("Самка", petSexLabel(PetSex.FEMALE))
        assertEquals("Не указан", petSexLabel(null))
        assertEquals("Метис", petBreedLabel(dogMixed))
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
