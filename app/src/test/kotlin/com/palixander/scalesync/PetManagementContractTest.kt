package com.palixander.scalesync

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PetManagementContractTest {
    private val today = LocalDate.of(2026, 8, 31)
    private val breedCatalog = PetBreedCatalog()
    private val mixedDog = breedCatalog.search("Бигль", PetSpecies.DOG).single()

    @Test
    fun openingCreateStartsFreshTypedSessionAndOpeningWhileBusyIsIgnored() {
        val previous = PetManagementUiState(error = "Старая ошибка")

        val opened = PetManagementController.showCreate(previous, editorSessionId = 7L)

        assertEquals(7L, opened.editorSessionId)
        assertEquals(PetProfileDraft.create(), opened.editor?.draft)
        assertNull(opened.error)
        val busy = opened.copy(busy = true)
        assertSame(busy, PetManagementController.showCreate(busy, editorSessionId = 8L))
    }

    @Test
    fun quickMeasurementCreationKeepsOnlyTrimmedNameAndSpecies() {
        val pet = newPetForQuickMeasurement("  Рыжик  ", PetSpecies.CAT)

        assertEquals("Рыжик", pet.displayName)
        assertEquals(PetSpecies.CAT, pet.species)
        assertNull(pet.sex)
        assertNull(pet.breedId)
        assertNull(pet.birthDate)
        assertNull(pet.dogAdultWeightCategory)
    }

    @Test
    fun openingEditCopiesTheWholePersistedProfileIntoTheOwnedDraft() {
        val pet = pet(
            id = "luna",
            name = "Луна",
            species = PetSpecies.DOG,
            sex = PetSex.FEMALE,
            breedId = mixedDog.id,
            birthDate = PartialBirthDate.Year(Year.of(2020)),
            category = DogAdultWeightCategory.III,
        )

        val opened = PetManagementController.showEdit(
            state = PetManagementUiState(),
            pet = pet,
            editorSessionId = 11L,
            breedCatalog = breedCatalog,
        )

        assertEquals(11L, opened.editorSessionId)
        assertEquals(PetProfileEditorMode.Edit(pet.id), opened.editor?.draft?.mode)
        assertEquals(pet.displayName, opened.editor?.draft?.displayName)
        assertEquals(pet.species, opened.editor?.draft?.species)
        assertEquals(pet.sex, opened.editor?.draft?.sex)
        assertEquals(pet.breedId, opened.editor?.draft?.breed?.id)
        assertEquals(PetBirthDateInput.Year("2020"), opened.editor?.draft?.birthDate)
        assertEquals(pet.dogAdultWeightCategory, opened.editor?.draft?.dogAdultWeightCategory)
    }

    @Test
    fun actionsUseTheProfileReducerClearVisibleErrorsAndStayLockedWhileSaving() {
        val opened = PetManagementController.showCreate(
            state = PetManagementUiState(),
            editorSessionId = 1L,
        ).copy(
            fieldErrors = PetProfileFieldErrors(displayName = PetNameValidationError.REQUIRED),
            error = "Ошибка хранилища",
        )

        val changed = PetManagementController.onAction(
            opened,
            PetProfileAction.DisplayNameChanged("Луна"),
        )

        assertEquals("Луна", changed.editor?.draft?.displayName)
        assertFalse(changed.fieldErrors.hasErrors)
        assertNull(changed.error)
        val busy = changed.copy(busy = true)
        assertSame(
            busy,
            PetManagementController.onAction(
                busy,
                PetProfileAction.DisplayNameChanged("Не должно примениться"),
            ),
        )
        assertSame(busy, PetManagementController.dismiss(busy))
    }

    @Test
    fun invalidSaveKeepsEditorOpenAndExposesTypedFieldErrors() {
        val opened = PetManagementController.showCreate(
            state = PetManagementUiState(),
            editorSessionId = 3L,
        )

        val preparation = PetManagementController.prepareSave(opened, today, emptyList())

        val invalid = preparation as PetProfileSavePreparation.Invalid
        assertSame(opened.editor, invalid.state.editor)
        assertEquals(PetNameValidationError.REQUIRED, invalid.state.fieldErrors.displayName)
        assertEquals(PetSpeciesValidationError.REQUIRED, invalid.state.fieldErrors.species)
        assertFalse(invalid.state.busy)
    }

    @Test
    fun readyCreateSaveCarriesEveryProfileFieldAndLocksTheSameSession() {
        val breed = PetBreedSelection.Available(mixedDog)
        var state = PetManagementController.showCreate(
            state = PetManagementUiState(),
            editorSessionId = 5L,
        )
        listOf(
            PetProfileAction.DisplayNameChanged("  Луна  "),
            PetProfileAction.SpeciesChangeRequested(PetSpecies.DOG),
            PetProfileAction.SexChanged(PetSex.FEMALE),
            PetProfileAction.BreedChanged(breed),
            PetProfileAction.BirthDateChanged(PetBirthDateInput.Year("2020")),
            PetProfileAction.DogAdultWeightCategoryChanged(DogAdultWeightCategory.IV),
        ).forEach { action -> state = PetManagementController.onAction(state, action) }

        val preparation = PetManagementController.prepareSave(state, today, emptyList())

        val ready = preparation as PetProfileSavePreparation.Ready
        val profile = (ready.request.profile as ValidatedPetProfile.Create).pet
        assertTrue(ready.state.busy)
        assertEquals(5L, ready.request.editorSessionId)
        assertEquals("Луна", profile.displayName)
        assertEquals(PetSpecies.DOG, profile.species)
        assertEquals(PetSex.FEMALE, profile.sex)
        assertEquals(mixedDog.id, profile.breedId)
        assertEquals(PartialBirthDate.Year(Year.of(2020)), profile.birthDate)
        assertEquals(DogAdultWeightCategory.IV, profile.dogAdultWeightCategory)
    }

    @Test
    fun repositoryFailureRestoresTheSameDraftAndLetsTheUserRetry() {
        val draft = PetProfileDraft.create().copy(
            displayName = "Луна",
            species = PetSpecies.CAT,
            sex = PetSex.FEMALE,
            birthDate = PetBirthDateInput.Year("2020"),
        )
        val opened = PetManagementUiState(
            editor = PetProfileEditorState(draft),
            editorSessionId = 9L,
        )
        val ready = PetManagementController.prepareSave(opened, today, emptyList())
            as PetProfileSavePreparation.Ready

        val failed = PetManagementController.finishSave(
            state = ready.state,
            request = ready.request,
            result = PetProfilePersistenceResult.Failure("Хранилище недоступно"),
        )

        assertEquals(draft, failed.editor?.draft)
        assertEquals(9L, failed.editorSessionId)
        assertFalse(failed.busy)
        assertEquals("Хранилище недоступно", failed.error)
        assertTrue(
            PetManagementController.prepareSave(failed, today, emptyList())
                is PetProfileSavePreparation.Ready,
        )
    }

    @Test
    fun staleSaveCompletionCannotCloseOrCorruptANewerEditorSession() {
        val oldState = PetManagementUiState(
            editor = PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Старый",
                    species = PetSpecies.CAT,
                ),
            ),
            editorSessionId = 20L,
        )
        val oldSave = PetManagementController.prepareSave(oldState, today, emptyList())
            as PetProfileSavePreparation.Ready
        val newer = PetManagementController.showEdit(
            state = PetManagementUiState(),
            pet = pet("new", "Новый", PetSpecies.DOG),
            editorSessionId = 21L,
            breedCatalog = breedCatalog,
        )

        assertSame(
            newer,
            PetManagementController.finishSave(
                state = newer,
                request = oldSave.request,
                result = PetProfilePersistenceResult.Success,
            ),
        )
        assertSame(
            newer,
            PetManagementController.finishSave(
                state = newer,
                request = oldSave.request,
                result = PetProfilePersistenceResult.Failure("Старая ошибка"),
            ),
        )
    }

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
