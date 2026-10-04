package com.palixander.weightogether

import com.palixander.weightogether.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.weightogether.core.reference.WeightReferenceSnapshot
import com.palixander.weightogether.domain.BirthDatePrecision
import com.palixander.weightogether.domain.BreedId
import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.uiText
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
    @Test
    fun optionalHeightAcceptsBothDecimalSeparatorsAndClearsOnEdit() {
        val draft = PetProfileDraft.create().copy(displayName = "Cat", species = PetSpecies.CAT)
        for (input in listOf("25.125", "25,125", " 25,125 ")) {
            assertEquals(25.125, validatePetProfileDraft(draft.copy(heightCm = input), today).newPet!!.heightCm!!, 0.0)
        }
        assertNull(validatePetProfileDraft(draft, today).newPet!!.heightCm)
        val original = pet(id = "cat", name = "Cat", species = PetSpecies.CAT).copy(heightCm = 25.125)
        val edited = PetProfileDraft.edit(original)
        assertEquals(25.125, validatePetProfileDraft(edited.copy(displayName = "Kitty"), today).petUpdate!!.heightCm!!, 0.0)
        val cleared = PetProfileReducer.reduce(PetProfileEditorState(edited), PetProfileAction.HeightChanged(" "))
        assertNull(validatePetProfileDraft(cleared.draft, today).petUpdate!!.heightCm)
    }

    @Test
    fun invalidHeightPreventsProfileSaving() {
        val draft = PetProfileDraft.create().copy(displayName = "Cat", species = PetSpecies.CAT)
        for (input in listOf("0", "-1", "NaN", "Infinity", "1e309", "abc", "1,2.3", "9".repeat(400))) {
            val result = validatePetProfileDraft(draft.copy(heightCm = input), today)
            assertEquals(input, PetHeightValidationError.INVALID, result.errors.heightCm)
            assertFalse(input, result.isValid)
            assertNull(result.profile)
        }
    }

    @Test
    fun editingPreservesHeightAcrossSmallAndLargeFiniteValues() {
        for (height in listOf(Double.MIN_VALUE, 0.0000001, 25.123456789, Double.MAX_VALUE)) {
            val original = pet(id = "cat", name = "Cat", species = PetSpecies.CAT).copy(heightCm = height)
            assertEquals(height, validatePetProfileDraft(PetProfileDraft.edit(original), today).petUpdate!!.heightCm!!, 0.0)
        }
    }

    @Test
    fun `restore persisted action replaces dirty draft and pending confirmation`() {
        val persisted = PetProfileEditorState(
            PetProfileDraft.create().copy(displayName = "Луна", species = PetSpecies.CAT),
        )
        val dirty = PetProfileEditorState(
            draft = persisted.draft.copy(displayName = "Другое имя", species = PetSpecies.DOG),
            pendingSpeciesChange = PendingPetSpeciesChange(
                requestedSpecies = PetSpecies.CAT,
                clearBreed = true,
                clearDogAdultWeightCategory = false,
            ),
        )

        assertEquals(
            persisted,
            PetProfileReducer.reduce(dirty, PetProfileAction.RestorePersisted(persisted)),
        )
    }

    @Test
    fun `photo action is persisted by create validation`() {
        val photo = "profile-photos/pets/new/profile.webp"
        val initial = PetProfileEditorState(
            PetProfileDraft.create().copy(displayName = "Луна", species = PetSpecies.CAT),
        )
        val changed = PetProfileReducer.reduce(initial, PetProfileAction.PhotoChanged(photo))

        assertEquals(photo, changed.draft.photoPath)
        val saved = validatePetProfileDraft(changed.draft, LocalDate.of(2026, 1, 1)).newPet
        assertEquals(photo, saved?.photoPath)
    }
    private val today = LocalDate.of(2026, 8, 31)
    private val breedCatalog = PetBreedCatalog()
    private val dogMixed = breedCatalog.resolve(BreedId("scalesync:dog:mixed-breed"), PetSpecies.DOG)

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
        val expectedIds = WeightReferenceSnapshot.bundled().profiles
            .filter {
                it.species.name == "CAT" &&
                    it.referenceKind.name == "MODELLED_BREED_ADULT_RANGE" &&
                    it.breedId != null
            }
            .mapNotNull { it.breedId }
            .toSet()
        val cats = breedCatalog.search("", PetSpecies.CAT)

        assertEquals(31, cats.size)
        assertEquals(expectedIds, cats.map { it.id.value }.toSet())
        assertTrue(cats.all { it.species == PetSpecies.CAT })
        assertTrue(cats.size < com.palixander.weightogether.core.breed.BreedCatalog.bundled()
            .all(com.palixander.weightogether.core.breed.BreedSpecies.CAT).size)
        assertTrue(breedCatalog.search("Maine Coon Cat", PetSpecies.CAT).isNotEmpty())
        assertTrue(breedCatalog.search("мейн", PetSpecies.CAT).isNotEmpty())
        assertTrue(breedCatalog.search("мейн", PetSpecies.DOG).isEmpty())
        assertEquals(1, cats.count { it.id == BreedId("VBO:0100230") && it.displayName == "Сфинкс" })
        assertTrue(cats.none { it.id == BreedId("VBO:0100061") })
        assertEquals(
            mapOf(
                BreedId("VBO:0100169") to "Манчкин",
                BreedId("VBO:0100170") to "Манчкин длинношёрстный",
                BreedId("VBO:0100303") to "Манчкин короткошёрстный",
            ),
            cats.filter { it.id.value in setOf("VBO:0100169", "VBO:0100170", "VBO:0100303") }
                .associate { it.id to it.displayName },
        )
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
            "VBO:0100000" to "Абиссинская", "VBO:0100018" to "Американская короткошёрстная",
            "VBO:0100036" to "Балинезийская", "VBO:0100040" to "Бенгальская",
            "VBO:0100045" to "Бомбейская", "VBO:0100052" to "Британская короткошёрстная",
            "VBO:0100053" to "Бурманская", "VBO:0100056" to "Бурмилла",
            "VBO:0100077" to "Корниш-рекс", "VBO:0100084" to "Девон-рекс",
            "VBO:0100090" to "Египетская мау", "VBO:0100154" to "Мейн-кун",
            "VBO:0100169" to "Манчкин", "VBO:0100170" to "Манчкин длинношёрстный",
            "VBO:0100173" to "Невская маскарадная", "VBO:0100178" to "Норвежская лесная",
            "VBO:0100183" to "Ориентальная длинношёрстная", "VBO:0100184" to "Ориентальная короткошёрстная",
            "VBO:0100188" to "Персидская", "VBO:0100189" to "Петерболд",
            "VBO:0100196" to "Рэгдолл", "VBO:0100200" to "Русская голубая",
            "VBO:0100209" to "Шотландская вислоухая", "VBO:0100216" to "Селкирк-рекс длинношёрстный",
            "VBO:0100221" to "Сиамская", "VBO:0100223" to "Сибирская",
            "VBO:0100230" to "Сфинкс", "VBO:0100235" to "Тайская",
            "VBO:0100245" to "Тойгер", "VBO:0100249" to "Турецкая ангора",
            "VBO:0100303" to "Манчкин короткошёрстный",
        )

        val options = breedCatalog.search("", PetSpecies.CAT)
        assertEquals(expected, options.associate { it.id.value to it.displayName })
        assertEquals(expected.values.sortedBy(String::lowercase), options.map(PetBreedOption::displayName))
        assertTrue(options.none { option -> option.displayName.any { it in 'A'..'Z' || it in 'a'..'z' } })
        assertEquals("Русская голубая", breedCatalog.search("Russian Blue", PetSpecies.CAT).single().displayName)
        assertEquals("Сибирская", breedCatalog.search("Siberian Forest Cat", PetSpecies.CAT)
            .single { it.id == BreedId("VBO:0100223") }.displayName)
        assertEquals("Балинезийская", breedCatalog.search("Thai Siamese", PetSpecies.CAT)
            .single { it.id == BreedId("VBO:0100036") }.displayName)
        assertEquals("Ориентальная короткошёрстная", breedCatalog.search("Ориентальная", PetSpecies.CAT)
            .single { it.id == BreedId("VBO:0100184") }.displayName)
    }

    @Test
    fun `catalog keeps the supported dog set independent from cat options`() {
        val dogOptions = breedCatalog.search("", PetSpecies.DOG)

        assertEquals(51, dogOptions.size)
        assertTrue(dogOptions.all { it.species == PetSpecies.DOG })
        assertTrue(breedCatalog.search("", PetSpecies.CAT).all { it.species == PetSpecies.CAT })
    }

    @Test
    fun `new dog options use approved Russian names in alphabetic order`() {
        val expected = listOf(
            "Американская акита", "Английский бульдог", "Бернский зенненхунд", "Бишон-фризе",
            "Веймаранер короткошёрстный", "Вест-хайленд-уайт-терьер", "Йоркширский терьер",
            "Керри-блю-терьер", "Китайская хохлатая собака", "Колли длинношёрстный",
            "Малая итальянская борзая", "Миниатюрный бультерьер", "Норвич-терьер", "Ротвейлер",
            "Тайский риджбек", "Такса миниатюрная гладкошёрстная", "Чихуахуа длинношёрстный",
            "Чихуахуа короткошёрстный", "Шетландская овчарка", "Шотландский терьер",
        )
        val newIds = setOf(
            "VBO:0201415", "VBO:0201448", "VBO:0200161", "VBO:0200339", "VBO:0200962",
            "VBO:0200485", "VBO:0200163", "VBO:0201198", "VBO:0200713", "VBO:0201403",
            "VBO:0200340", "VBO:0200345", "VBO:0201348", "VBO:0200410", "VBO:0200027",
            "VBO:0201217", "VBO:0200882", "VBO:0200764", "VBO:0200375", "VBO:0201143",
        )

        val options = breedCatalog.search("", PetSpecies.DOG).filter { it.id.value in newIds }

        assertEquals(expected, options.map(PetBreedOption::displayName))
        assertTrue(options.none { option -> option.displayName.any { it in 'A'..'Z' || it in 'a'..'z' } })
        assertEquals("Бернский зенненхунд", breedCatalog.search("Bernese Mountain Dog", PetSpecies.DOG).single().displayName)
    }

    @Test
    fun `German Shepherd is searchable and keeps its stable id through profile round trip`() {
        val expectedId = BreedId("VBO:0200577")
        val localized = breedCatalog.search("немецкая", PetSpecies.DOG).single()
        val english = breedCatalog.search("German Shepherd", PetSpecies.DOG).single()

        assertEquals(expectedId, localized.id)
        assertEquals("Немецкая овчарка", localized.displayName)
        assertEquals(localized, english)

        val created = validatePetProfileDraft(
            PetProfileDraft.create().copy(
                displayName = "Рекс",
                species = PetSpecies.DOG,
                sex = PetSex.MALE,
                breed = PetBreedSelection.Available(localized),
            ),
            today,
        ).newPet
        assertEquals(expectedId, created?.breedId)

        val persisted = pet(
            id = "rex",
            name = "Рекс",
            species = PetSpecies.DOG,
            sex = PetSex.MALE,
            breedId = created?.breedId,
        )
        val restored = PetProfileDraft.edit(persisted, breedCatalog)
        assertEquals(expectedId, restored.breed?.id)
        assertTrue(restored.breed is PetBreedSelection.Available)
        assertEquals(expectedId, validatePetProfileDraft(restored, today, listOf(persisted)).petUpdate?.breedId)
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
        assertEquals(uiText(R.string.pet_breed_unavailable, unknownId.value), petBreedLabel(selection))
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
    fun namedBreedsClearManualCategoryAndDoNotAssignOneWhenSwitchedOrCleared() {
        var state = PetProfileEditorState(PetProfileDraft.create().copy(
            displayName = "Бим", species = PetSpecies.DOG,
            dogAdultWeightCategory = DogAdultWeightCategory.II,
        ))
        for (name in listOf("Лабрадор", "Бигль", "Доберман")) {
            val breed = PetBreedSelection.Available(breedCatalog.search(name, PetSpecies.DOG).single())
            state = reduce(state, PetProfileAction.BreedChanged(breed))
            assertNull(state.draft.dogAdultWeightCategory)
            assertFalse(isDogAdultWeightCategoryApplicable(state.draft.species, state.draft.breed))
            val saved = validatePetProfileDraft(state.draft, today)
            assertTrue(saved.isValid)
            assertEquals(breed.id, saved.newPet?.breedId)
            state = reduce(state, PetProfileAction.DogAdultWeightCategoryChanged(DogAdultWeightCategory.V))
            assertNull(state.draft.dogAdultWeightCategory)
        }
        state = reduce(state, PetProfileAction.BreedChanged(null))
        assertNull(state.draft.dogAdultWeightCategory)
        assertTrue(isDogAdultWeightCategoryApplicable(state.draft.species, state.draft.breed))
    }

    @Test
    fun openingNamedOrUnavailableBreedClearsLegacyCategoryAndSavesBreedUnchanged() {
        for (id in listOf("VBO:0200800", "external:dog:rare")) {
            for (category in listOf(null, DogAdultWeightCategory.II, DogAdultWeightCategory.V)) {
                val original = pet("dog", "Бим", PetSpecies.DOG, breedId = BreedId(id), category = category)
                val opened = PetProfileEditorState.edit(original, breedCatalog)
                assertNull(opened.draft.dogAdultWeightCategory)
                val saved = validatePetProfileDraft(opened.draft, today)
                assertTrue(saved.isValid)
                assertEquals(original.breedId, saved.petUpdate?.breedId)
                assertNull(saved.petUpdate?.dogAdultWeightCategory)
                assertEquals(category, original.dogAdultWeightCategory)
            }
        }
    }

    @Test
    fun savedSpecialDogBreedsResolveWithLocalizedLabelsWithoutExpandingPicker() {
        for ((id, label) in listOf("scalesync:dog:breed-unknown" to "Без породы", "scalesync:dog:mixed-breed" to "Метис")) {
            val resolved = breedCatalog.resolve(BreedId(id), PetSpecies.DOG)
            assertTrue(resolved is PetBreedSelection.Available)
            assertEquals(UiText.Raw(label), petBreedLabel(resolved))
            assertTrue(breedCatalog.search("", PetSpecies.DOG).none { it.id.value == id })
        }
    }

    @Test
    fun unspecifiedUnknownAndMixedBreedsRetainManualCategoryAcrossEditsAndSwitches() {
        val breeds = listOf(null, "scalesync:dog:breed-unknown", "scalesync:dog:mixed-breed")
        for (id in breeds) {
            val original = pet("dog", "Бим", PetSpecies.DOG, breedId = id?.let(::BreedId), category = DogAdultWeightCategory.III)
            var state = PetProfileEditorState.edit(original, breedCatalog)
            assertEquals(DogAdultWeightCategory.III, state.draft.dogAdultWeightCategory)
            for (nextId in breeds) {
                val breed = nextId?.let { breedCatalog.resolve(BreedId(it), PetSpecies.DOG) }
                state = reduce(state, PetProfileAction.BreedChanged(breed))
                assertTrue(isDogAdultWeightCategoryApplicable(PetSpecies.DOG, breed))
                assertFalse(isDogAdultWeightCategoryApplicable(PetSpecies.CAT, breed))
                assertEquals(DogAdultWeightCategory.III, validatePetProfileDraft(state.draft, today).petUpdate?.dogAdultWeightCategory)
            }
        }
    }

    @Test
    fun presentationFunctionsExposeLocalizedTextContracts() {
        assertEquals(uiText(R.string.pet_sex_male), petSexLabel(PetSex.MALE))
        assertEquals(uiText(R.string.pet_sex_female), petSexLabel(PetSex.FEMALE))
        assertEquals(uiText(R.string.pet_sex_unspecified), petSexLabel(null))
        assertEquals(UiText.Raw((dogMixed as PetBreedSelection.Available).option.displayName), petBreedLabel(dogMixed))
        assertEquals(uiText(R.string.birth_precision_year), birthDatePrecisionLabel(BirthDatePrecision.YEAR))
        assertEquals(uiText(R.string.birth_precision_month), birthDatePrecisionLabel(BirthDatePrecision.MONTH))
        assertEquals(uiText(R.string.birth_precision_day), birthDatePrecisionLabel(BirthDatePrecision.DAY))
        assertEquals(
            uiText(R.string.birth_date_with_precision, "02.2020", uiText(R.string.birth_precision_month)),
            partialBirthDateLabel(PartialBirthDate.Month(YearMonth.of(2020, 2))),
        )
        assertEquals(uiText(R.string.dog_weight_i), dogAdultWeightCategoryLabel(DogAdultWeightCategory.I))
        assertEquals(uiText(R.string.dog_weight_ii), dogAdultWeightCategoryLabel(DogAdultWeightCategory.II))
        assertEquals(uiText(R.string.dog_weight_iii), dogAdultWeightCategoryLabel(DogAdultWeightCategory.III))
        assertEquals(uiText(R.string.dog_weight_iv), dogAdultWeightCategoryLabel(DogAdultWeightCategory.IV))
        assertEquals(uiText(R.string.dog_weight_v), dogAdultWeightCategoryLabel(DogAdultWeightCategory.V))
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
