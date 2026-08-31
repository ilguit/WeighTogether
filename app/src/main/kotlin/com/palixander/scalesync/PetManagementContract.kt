package com.palixander.scalesync

import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetDeletionPreview
import com.palixander.scalesync.domain.PetSpecies
import java.time.LocalDate

data class PetManagementUiState(
    val editor: PetProfileEditorState? = null,
    val editorSessionId: Long? = null,
    val fieldErrors: PetProfileFieldErrors = PetProfileFieldErrors(),
    val deletion: PetDeletionPreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

internal data class PetProfileSaveRequest(
    val editorSessionId: Long,
    val profile: ValidatedPetProfile,
)

internal sealed interface PetProfileSavePreparation {
    val state: PetManagementUiState

    data class Ignored(
        override val state: PetManagementUiState,
    ) : PetProfileSavePreparation

    data class Invalid(
        override val state: PetManagementUiState,
    ) : PetProfileSavePreparation

    data class Ready(
        override val state: PetManagementUiState,
        val request: PetProfileSaveRequest,
    ) : PetProfileSavePreparation
}

internal sealed interface PetProfilePersistenceResult {
    data object Success : PetProfilePersistenceResult

    data class Failure(val message: String) : PetProfilePersistenceResult
}

/** Pure state transitions shared by [MainViewModel] and JVM tests. */
internal object PetManagementController {
    fun showCreate(
        state: PetManagementUiState,
        editorSessionId: Long,
    ): PetManagementUiState = if (state.busy) {
        state
    } else {
        PetManagementUiState(
            editor = PetProfileEditorState(PetProfileDraft.create()),
            editorSessionId = editorSessionId,
        )
    }

    fun showEdit(
        state: PetManagementUiState,
        pet: Pet,
        editorSessionId: Long,
        breedCatalog: PetBreedCatalog,
    ): PetManagementUiState = if (state.busy) {
        state
    } else {
        PetManagementUiState(
            editor = PetProfileEditorState(PetProfileDraft.edit(pet, breedCatalog)),
            editorSessionId = editorSessionId,
        )
    }

    fun onAction(
        state: PetManagementUiState,
        action: PetProfileAction,
    ): PetManagementUiState {
        if (state.busy) return state
        val editor = state.editor ?: return state
        return state.copy(
            editor = PetProfileReducer.reduce(editor, action),
            fieldErrors = PetProfileFieldErrors(),
            error = null,
        )
    }

    fun prepareSave(
        state: PetManagementUiState,
        today: LocalDate,
        existingPets: Iterable<Pet>,
    ): PetProfileSavePreparation {
        val editor = state.editor
        val editorSessionId = state.editorSessionId
        if (state.busy || editor == null || editorSessionId == null) {
            return PetProfileSavePreparation.Ignored(state)
        }

        val validation = validatePetProfileDraft(
            draft = editor.draft,
            today = today,
            existingPets = existingPets,
        )
        val profile = validation.profile
        if (profile == null) {
            return PetProfileSavePreparation.Invalid(
                state.copy(
                    fieldErrors = validation.errors,
                    error = null,
                ),
            )
        }

        val savingState = state.copy(
            fieldErrors = PetProfileFieldErrors(),
            busy = true,
            error = null,
        )
        return PetProfileSavePreparation.Ready(
            state = savingState,
            request = PetProfileSaveRequest(editorSessionId, profile),
        )
    }

    fun finishSave(
        state: PetManagementUiState,
        request: PetProfileSaveRequest,
        result: PetProfilePersistenceResult,
    ): PetManagementUiState {
        if (
            state.editorSessionId != request.editorSessionId ||
            state.editor?.draft?.mode != request.profile.mode
        ) {
            return state
        }
        return when (result) {
            PetProfilePersistenceResult.Success -> PetManagementUiState()
            is PetProfilePersistenceResult.Failure -> state.copy(
                busy = false,
                error = result.message,
            )
        }
    }

    fun dismiss(state: PetManagementUiState): PetManagementUiState =
        if (state.busy) state else PetManagementUiState()
}

private val ValidatedPetProfile.mode: PetProfileEditorMode
    get() = when (this) {
        is ValidatedPetProfile.Create -> PetProfileEditorMode.Create
        is ValidatedPetProfile.Edit -> PetProfileEditorMode.Edit(pet.id)
    }

internal fun petSpeciesLabel(species: PetSpecies): String = when (species) {
    PetSpecies.CAT -> "Кошка"
    PetSpecies.DOG -> "Собака"
    PetSpecies.UNSPECIFIED -> "Вид не указан"
}
