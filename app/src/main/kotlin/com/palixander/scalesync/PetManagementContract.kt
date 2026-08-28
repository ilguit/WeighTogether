package com.palixander.scalesync

import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetDeletionPreview
import com.palixander.scalesync.domain.PetSpecies

sealed interface PetEditorMode {
    data object Create : PetEditorMode
    data class Edit(val pet: Pet) : PetEditorMode
}

data class PetManagementUiState(
    val editor: PetEditorMode? = null,
    val deletion: PetDeletionPreview? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

internal fun petSpeciesLabel(species: PetSpecies): String = when (species) {
    PetSpecies.CAT -> "Кошка"
    PetSpecies.DOG -> "Собака"
    PetSpecies.UNSPECIFIED -> "Вид не указан"
}

internal fun isPetEditorValid(name: String, species: PetSpecies?): Boolean =
    name.trim().length in com.palixander.scalesync.domain.PET_NAME_LENGTH &&
        species != null && species != PetSpecies.UNSPECIFIED
