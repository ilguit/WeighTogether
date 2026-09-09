package com.palixander.scalesync

import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedKind
import com.palixander.scalesync.core.breed.BreedRecord
import com.palixander.scalesync.core.breed.BreedSpecies
import com.palixander.scalesync.core.breed.canonicalBreedId
import com.palixander.scalesync.core.breedreference.BreedReferenceBreed
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshot
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferenceSpecies
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.PET_NAME_LENGTH
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetUpdate
import com.palixander.scalesync.domain.normalizePetName
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.domain.reference.DogBreedAdultWeightCategoryMappings
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter

sealed interface PetProfileEditorMode {
    data object Create : PetProfileEditorMode

    data class Edit(val petId: PetId) : PetProfileEditorMode
}

sealed interface PetBirthDateInput {
    val precision: BirthDatePrecision?

    data object Empty : PetBirthDateInput {
        override val precision: BirthDatePrecision? = null
    }

    data class Year(val year: String) : PetBirthDateInput {
        override val precision: BirthDatePrecision = BirthDatePrecision.YEAR
    }

    data class Month(
        val year: String,
        val month: String,
    ) : PetBirthDateInput {
        override val precision: BirthDatePrecision = BirthDatePrecision.MONTH
    }

    data class Day(
        val year: String,
        val month: String,
        val day: String,
    ) : PetBirthDateInput {
        override val precision: BirthDatePrecision = BirthDatePrecision.DAY
    }

    companion object {
        fun from(value: PartialBirthDate?): PetBirthDateInput = when (value) {
            null -> Empty
            is PartialBirthDate.Year -> Year(value.value.value.toString())
            is PartialBirthDate.Month -> Month(
                year = value.value.year.toString(),
                month = value.value.monthValue.toString(),
            )
            is PartialBirthDate.Day -> Day(
                year = value.value.year.toString(),
                month = value.value.monthValue.toString(),
                day = value.value.dayOfMonth.toString(),
            )
        }
    }
}

data class PetBreedOption(
    val id: BreedId,
    val species: PetSpecies,
    val displayName: String,
    val canonicalName: String,
    val aliases: List<String>,
    val kind: BreedKind,
)

sealed interface PetBreedSelection {
    val id: BreedId
    val species: PetSpecies

    data class Available(val option: PetBreedOption) : PetBreedSelection {
        override val id: BreedId = option.id
        override val species: PetSpecies = option.species
    }

    /** A persisted ID absent from the bundled snapshot. It stays associated with its saved species. */
    data class Unavailable(
        override val id: BreedId,
        override val species: PetSpecies,
    ) : PetBreedSelection
}

class PetBreedCatalog(
    private val catalog: BreedCatalog = BreedCatalog.bundled(),
    snapshotResult: BreedReferenceSnapshotLoadResult = BreedReferenceSnapshot.bundledOrUnavailable(catalog),
    weightReferenceSnapshot: WeightReferenceSnapshot? = runCatching {
        WeightReferenceSnapshot.bundled(catalog)
    }.getOrNull(),
) {
    private val snapshot = (snapshotResult as? BreedReferenceSnapshotLoadResult.Available)?.snapshot
    private val supportedDogBreeds = snapshot?.breeds.orEmpty()
    private val supportedCatBreedIds = weightReferenceSnapshot?.manifest?.scopes
        .orEmpty()
        .asSequence()
        .filter { scope ->
            scope.species == ReferenceSpecies.CAT &&
                scope.basis == ReferenceBasis.BREED &&
                scope.breedId != null
        }
        .mapNotNull { it.breedId }
        .toSet()
    private val supportedCatBreeds = supportedCatBreedIds
        .mapNotNull(catalog::findById)
        .filter { it.species == BreedSpecies.CAT }

    /** Searches only product-supported breeds. The full VBO catalog remains internal. */
    fun search(
        query: String,
        species: PetSpecies,
    ): List<PetBreedOption> = when (species) {
        PetSpecies.DOG -> searchDogs(query)
        PetSpecies.CAT -> searchCats(query)
        PetSpecies.UNSPECIFIED -> emptyList()
    }

    private fun searchDogs(query: String): List<PetBreedOption> {
        val needle = query.trim().lowercase()
        return supportedDogBreeds
            .asSequence()
            .filter { breed ->
                needle.isEmpty() || sequenceOf(
                    breed.russianName,
                    breed.englishName,
                    *breed.aliases.mapNotNull(catalog::findById)
                        .flatMap { listOf(it.displayNameRu, it.canonicalName) + it.aliases }
                        .toTypedArray(),
                ).any { it.lowercase().contains(needle) }
            }
            .sortedBy { it.russianName.lowercase() }
            .map(::toPetBreedOption)
            .toList()
    }

    private fun searchCats(query: String): List<PetBreedOption> {
        val matchingIds = catalog.search(query, BreedSpecies.CAT)
            .asSequence()
            .map(BreedRecord::id)
            .toSet()
        val needle = query.trim().lowercase()
        return supportedCatBreeds
            .asSequence()
            .map(::toPetBreedOption)
            .filter { option ->
                needle.isEmpty() || option.id.value in matchingIds || sequenceOf(
                    option.displayName,
                    option.canonicalName,
                    *option.aliases.toTypedArray(),
                ).any { needle in it.lowercase() }
            }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.canonicalName.lowercase() }, { it.id.value }))
            .toList()
    }

    fun resolve(id: BreedId, savedSpecies: PetSpecies): PetBreedSelection {
        val canonicalId = canonicalBreedId(id.value)
        snapshot?.breed(canonicalId)?.let { return PetBreedSelection.Available(toPetBreedOption(it)) }
        supportedCatBreeds.singleOrNull { it.id == canonicalId }
            ?.let { return PetBreedSelection.Available(toPetBreedOption(it)) }
        return PetBreedSelection.Unavailable(BreedId(canonicalId), savedSpecies)
    }

    private fun toPetBreedOption(breed: BreedReferenceBreed): PetBreedOption = PetBreedOption(
        id = BreedId(breed.breedId),
        species = PetSpecies.DOG,
        displayName = breed.russianName,
        canonicalName = breed.englishName,
        aliases = breed.aliases.flatMap { alias ->
            catalog.findById(alias)?.let { listOf(it.displayNameRu, it.canonicalName) + it.aliases }
                .orEmpty()
        },
        kind = BreedKind.VBO,
    )

    private fun toPetBreedOption(breed: BreedRecord): PetBreedOption = PetBreedOption(
        id = BreedId(breed.id),
        species = when (breed.species) {
            BreedSpecies.CAT -> PetSpecies.CAT
            BreedSpecies.DOG -> PetSpecies.DOG
        },
        displayName = CatBreedDisplayNames[breed.id] ?: breed.displayNameRu,
        canonicalName = breed.canonicalName,
        aliases = breed.aliases,
        kind = breed.kind,
    )
}

private val CatBreedDisplayNames = mapOf(
    "VBO:0100119" to "Домашняя короткошёрстная",
    "VBO:0100209" to "Шотландская вислоухая",
    "VBO:0100223" to "Сибирская",
    "VBO:0100169" to "Манчкин",
    "VBO:0100170" to "Манчкин длинношёрстный",
    "VBO:0100303" to "Манчкин короткошёрстный",
)

data class PetProfileDraft(
    val mode: PetProfileEditorMode,
    val displayName: String,
    val species: PetSpecies?,
    val sex: PetSex? = null,
    val breed: PetBreedSelection? = null,
    val birthDate: PetBirthDateInput = PetBirthDateInput.Empty,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
) {
    companion object {
        fun create(): PetProfileDraft = PetProfileDraft(
            mode = PetProfileEditorMode.Create,
            displayName = "",
            species = null,
        )

        fun edit(
            pet: Pet,
            breedCatalog: PetBreedCatalog = PetBreedCatalog(),
        ): PetProfileDraft = PetProfileDraft(
            mode = PetProfileEditorMode.Edit(pet.id),
            displayName = pet.displayName,
            species = pet.species.takeUnless { it == PetSpecies.UNSPECIFIED },
            sex = pet.sex,
            breed = pet.breedId?.let { breedCatalog.resolve(it, pet.species) },
            birthDate = PetBirthDateInput.from(pet.birthDate),
            dogAdultWeightCategory = pet.dogAdultWeightCategory,
        )
    }
}

data class PendingPetSpeciesChange(
    val requestedSpecies: PetSpecies,
    val clearBreed: Boolean,
    val clearDogAdultWeightCategory: Boolean,
) {
    init {
        require(requestedSpecies != PetSpecies.UNSPECIFIED) { "The requested species must be CAT or DOG" }
        require(clearBreed || clearDogAdultWeightCategory) {
            "A confirmation is only needed when a field must be cleared"
        }
    }
}

data class PetProfileEditorState(
    val draft: PetProfileDraft,
    val pendingSpeciesChange: PendingPetSpeciesChange? = null,
    /** Editor-session provenance only; persisted profiles keep the existing schema. */
    val automaticallyAssignedDogCategory: AutomaticallyAssignedDogCategory? = null,
) {
    companion object {
        fun edit(
            pet: Pet,
            breedCatalog: PetBreedCatalog = PetBreedCatalog(),
        ): PetProfileEditorState {
            val draft = PetProfileDraft.edit(pet, breedCatalog)
            val availableBreed = (draft.breed as? PetBreedSelection.Available)
                ?.takeIf { draft.species == PetSpecies.DOG }
            val mappedCategory = availableBreed?.let {
                DogBreedAdultWeightCategoryMappings.find(it.id)?.category
            }
            val category = draft.dogAdultWeightCategory ?: mappedCategory
            val automaticAssignment = mappedCategory
                ?.takeIf { draft.dogAdultWeightCategory == null || draft.dogAdultWeightCategory == it }
                ?.let { AutomaticallyAssignedDogCategory(requireNotNull(availableBreed).id, it) }
            return PetProfileEditorState(
                draft = draft.copy(dogAdultWeightCategory = category),
                automaticallyAssignedDogCategory = automaticAssignment,
            )
        }
    }
}

data class AutomaticallyAssignedDogCategory(
    val breedId: BreedId,
    val category: DogAdultWeightCategory,
)

sealed interface PetProfileAction {
    data class DisplayNameChanged(val value: String) : PetProfileAction
    data class SpeciesChangeRequested(val species: PetSpecies) : PetProfileAction
    data class SexChanged(val sex: PetSex?) : PetProfileAction
    data class BreedChanged(val breed: PetBreedSelection?) : PetProfileAction
    data class BirthDateChanged(val birthDate: PetBirthDateInput) : PetProfileAction
    data class DogAdultWeightCategoryChanged(
        val category: DogAdultWeightCategory?,
    ) : PetProfileAction

    data object ConfirmSpeciesChange : PetProfileAction
    data object CancelSpeciesChange : PetProfileAction
}

object PetProfileReducer {
    fun reduce(
        state: PetProfileEditorState,
        action: PetProfileAction,
    ): PetProfileEditorState {
        val pending = state.pendingSpeciesChange
        if (pending != null) {
            return when (action) {
                PetProfileAction.ConfirmSpeciesChange -> state.copy(
                    draft = state.draft.copy(
                        species = pending.requestedSpecies,
                        breed = state.draft.breed.takeUnless { pending.clearBreed },
                        dogAdultWeightCategory = state.draft.dogAdultWeightCategory
                            .takeUnless { pending.clearDogAdultWeightCategory },
                    ),
                    pendingSpeciesChange = null,
                    automaticallyAssignedDogCategory = state.automaticallyAssignedDogCategory
                        .takeUnless { pending.clearBreed || pending.clearDogAdultWeightCategory },
                )
                PetProfileAction.CancelSpeciesChange -> state.copy(pendingSpeciesChange = null)
                else -> state
            }
        }

        return when (action) {
            is PetProfileAction.DisplayNameChanged -> state.withDraft {
                copy(displayName = action.value)
            }
            is PetProfileAction.SpeciesChangeRequested -> requestSpeciesChange(state, action.species)
            is PetProfileAction.SexChanged -> state.withDraft { copy(sex = action.sex) }
            is PetProfileAction.BreedChanged -> changeBreed(state, action.breed)
            is PetProfileAction.BirthDateChanged -> state.withDraft {
                copy(birthDate = action.birthDate)
            }
            is PetProfileAction.DogAdultWeightCategoryChanged -> changeDogCategory(
                state,
                action.category,
            )
            PetProfileAction.ConfirmSpeciesChange,
            PetProfileAction.CancelSpeciesChange,
            -> state
        }
    }

    private fun requestSpeciesChange(
        state: PetProfileEditorState,
        species: PetSpecies,
    ): PetProfileEditorState {
        require(species != PetSpecies.UNSPECIFIED) { "The requested species must be CAT or DOG" }
        if (species == state.draft.species) return state

        val clearBreed = state.draft.breed?.species?.let { it != species } == true
        val retainedBreed = state.draft.breed.takeUnless { clearBreed }
        val clearCategory = state.draft.dogAdultWeightCategory != null &&
            !isDogAdultWeightCategoryApplicable(species, retainedBreed)
        return if (clearBreed || clearCategory) {
            state.copy(
                pendingSpeciesChange = PendingPetSpeciesChange(
                    requestedSpecies = species,
                    clearBreed = clearBreed,
                    clearDogAdultWeightCategory = clearCategory,
                ),
            )
        } else {
            state.withDraft { copy(species = species) }
        }
    }

    private fun changeBreed(
        state: PetProfileEditorState,
        breed: PetBreedSelection?,
    ): PetProfileEditorState {
        if (breed != null && breed.species != state.draft.species) return state
        val mappedCategory = DogBreedAdultWeightCategoryMappings.find(breed?.id)?.category
        val category = mappedCategory ?: state.draft.dogAdultWeightCategory
            .takeUnless { state.automaticallyAssignedDogCategory != null }
            .takeIf {
                isDogAdultWeightCategoryApplicable(state.draft.species, breed)
            }
        val automaticAssignment = mappedCategory?.let {
            AutomaticallyAssignedDogCategory(requireNotNull(breed).id, it)
        }
        return state.copy(
            draft = state.draft.copy(
                breed = breed,
                dogAdultWeightCategory = category,
            ),
            automaticallyAssignedDogCategory = automaticAssignment,
        )
    }

    private fun changeDogCategory(
        state: PetProfileEditorState,
        category: DogAdultWeightCategory?,
    ): PetProfileEditorState {
        if (category == null) {
            return state.copy(
                draft = state.draft.copy(dogAdultWeightCategory = null),
                automaticallyAssignedDogCategory = null,
            )
        }
        if (!isDogAdultWeightCategoryApplicable(state.draft.species, state.draft.breed)) return state
        return state.copy(
            draft = state.draft.copy(dogAdultWeightCategory = category),
            automaticallyAssignedDogCategory = null,
        )
    }

    private inline fun PetProfileEditorState.withDraft(
        update: PetProfileDraft.() -> PetProfileDraft,
    ): PetProfileEditorState = copy(draft = draft.update())
}

enum class PetNameValidationError {
    REQUIRED,
    TOO_LONG,
    DUPLICATE,
}

enum class PetSpeciesValidationError {
    REQUIRED,
}

enum class PetBreedValidationError {
    SPECIES_MISMATCH,
}

enum class PetBirthDateValidationError {
    INCOMPLETE,
    INVALID,
    FUTURE,
}

enum class DogAdultWeightCategoryValidationError {
    NOT_APPLICABLE,
}

data class PetProfileFieldErrors(
    val displayName: PetNameValidationError? = null,
    val species: PetSpeciesValidationError? = null,
    val breed: PetBreedValidationError? = null,
    val birthDate: PetBirthDateValidationError? = null,
    val dogAdultWeightCategory: DogAdultWeightCategoryValidationError? = null,
) {
    val hasErrors: Boolean
        get() = displayName != null || species != null || breed != null ||
            birthDate != null || dogAdultWeightCategory != null
}

sealed interface ValidatedPetProfile {
    data class Create(val pet: NewPet) : ValidatedPetProfile
    data class Edit(val pet: PetUpdate) : ValidatedPetProfile
}

data class PetProfileValidationResult(
    val errors: PetProfileFieldErrors,
    val profile: ValidatedPetProfile?,
) {
    val isValid: Boolean
        get() = profile != null && !errors.hasErrors

    val newPet: NewPet?
        get() = (profile as? ValidatedPetProfile.Create)?.pet

    val petUpdate: PetUpdate?
        get() = (profile as? ValidatedPetProfile.Edit)?.pet
}

data class PetBirthDateInputValidation(
    val value: PartialBirthDate?,
    val error: PetBirthDateValidationError?,
) {
    val isValid: Boolean
        get() = error == null
}

fun validatePetProfileDraft(
    draft: PetProfileDraft,
    today: LocalDate,
    existingPets: Iterable<Pet> = emptyList(),
): PetProfileValidationResult {
    val trimmedName = draft.displayName.trim()
    val currentPetId = (draft.mode as? PetProfileEditorMode.Edit)?.petId
    val nameError = when {
        trimmedName.isEmpty() -> PetNameValidationError.REQUIRED
        trimmedName.length !in PET_NAME_LENGTH -> PetNameValidationError.TOO_LONG
        existingPets.any {
            it.id != currentPetId && it.normalizedName == normalizePetName(trimmedName)
        } -> PetNameValidationError.DUPLICATE
        else -> null
    }
    val species = draft.species?.takeUnless { it == PetSpecies.UNSPECIFIED }
    val speciesError = if (species == null) PetSpeciesValidationError.REQUIRED else null
    val breedError = if (draft.breed != null && draft.breed.species != species) {
        PetBreedValidationError.SPECIES_MISMATCH
    } else {
        null
    }
    val birthDate = validatePetBirthDateInput(draft.birthDate, today)
    val categoryError = if (
        draft.dogAdultWeightCategory != null &&
        !isDogAdultWeightCategoryApplicable(species, draft.breed)
    ) {
        DogAdultWeightCategoryValidationError.NOT_APPLICABLE
    } else {
        null
    }
    val errors = PetProfileFieldErrors(
        displayName = nameError,
        species = speciesError,
        breed = breedError,
        birthDate = birthDate.error,
        dogAdultWeightCategory = categoryError,
    )
    if (errors.hasErrors) return PetProfileValidationResult(errors, profile = null)

    val requiredSpecies = requireNotNull(species)
    val profile = when (val mode = draft.mode) {
        PetProfileEditorMode.Create -> ValidatedPetProfile.Create(
            NewPet(
                displayName = trimmedName,
                species = requiredSpecies,
                sex = draft.sex,
                breedId = draft.breed?.id?.let { BreedId(canonicalBreedId(it.value)) },
                birthDate = birthDate.value,
                dogAdultWeightCategory = draft.dogAdultWeightCategory,
            ),
        )
        is PetProfileEditorMode.Edit -> ValidatedPetProfile.Edit(
            PetUpdate(
                id = mode.petId,
                displayName = trimmedName,
                species = requiredSpecies,
                sex = draft.sex,
                breedId = draft.breed?.id?.let { BreedId(canonicalBreedId(it.value)) },
                birthDate = birthDate.value,
                dogAdultWeightCategory = draft.dogAdultWeightCategory,
            ),
        )
    }
    return PetProfileValidationResult(errors, profile)
}

fun validatePetBirthDateInput(
    input: PetBirthDateInput,
    today: LocalDate,
): PetBirthDateInputValidation {
    if (input == PetBirthDateInput.Empty) return PetBirthDateInputValidation(null, null)
    if (input.hasBlankComponent()) {
        return PetBirthDateInputValidation(null, PetBirthDateValidationError.INCOMPLETE)
    }

    val parsed = try {
        when (input) {
            PetBirthDateInput.Empty -> null
            is PetBirthDateInput.Year -> PartialBirthDate.Year(
                Year.of(input.year.toPositiveIntOrNull() ?: return invalidBirthDate()),
            )
            is PetBirthDateInput.Month -> PartialBirthDate.Month(
                YearMonth.of(
                    input.year.toPositiveIntOrNull() ?: return invalidBirthDate(),
                    input.month.toPositiveIntOrNull() ?: return invalidBirthDate(),
                ),
            )
            is PetBirthDateInput.Day -> PartialBirthDate.Day(
                LocalDate.of(
                    input.year.toPositiveIntOrNull() ?: return invalidBirthDate(),
                    input.month.toPositiveIntOrNull() ?: return invalidBirthDate(),
                    input.day.toPositiveIntOrNull() ?: return invalidBirthDate(),
                ),
            )
        }
    } catch (_: DateTimeException) {
        return invalidBirthDate()
    }
    val future = when (parsed) {
        null -> false
        is PartialBirthDate.Year -> parsed.value > Year.from(today)
        is PartialBirthDate.Month -> parsed.value > YearMonth.from(today)
        is PartialBirthDate.Day -> parsed.value > today
    }
    return if (future) {
        PetBirthDateInputValidation(null, PetBirthDateValidationError.FUTURE)
    } else {
        PetBirthDateInputValidation(parsed, null)
    }
}

fun isDogAdultWeightCategoryApplicable(
    species: PetSpecies?,
    breed: PetBreedSelection?,
): Boolean = species == PetSpecies.DOG

fun petSexLabel(sex: PetSex?): String = when (sex) {
    PetSex.MALE -> "Самец"
    PetSex.FEMALE -> "Самка"
    null -> "Не указан"
}

fun petBreedLabel(breed: PetBreedSelection?): String = when (breed) {
    is PetBreedSelection.Available -> breed.option.displayName
    is PetBreedSelection.Unavailable -> "Недоступна: ${breed.id.value}"
    null -> "Другая порода"
}

fun birthDatePrecisionLabel(precision: BirthDatePrecision): String = when (precision) {
    BirthDatePrecision.YEAR -> "Год"
    BirthDatePrecision.MONTH -> "Месяц"
    BirthDatePrecision.DAY -> "День"
}

fun formatPartialBirthDate(birthDate: PartialBirthDate): String = when (birthDate) {
    is PartialBirthDate.Year -> birthDate.value.toString()
    is PartialBirthDate.Month -> birthDate.value.format(MonthBirthDateFormatter)
    is PartialBirthDate.Day -> birthDate.value.format(DayBirthDateFormatter)
}

fun partialBirthDateLabel(birthDate: PartialBirthDate): String =
    "${formatPartialBirthDate(birthDate)} (${birthDatePrecisionLabel(birthDate.precision).lowercase()})"

fun dogAdultWeightCategoryLabel(category: DogAdultWeightCategory): String = when (category) {
    DogAdultWeightCategory.I -> "I — < 6,5 кг"
    DogAdultWeightCategory.II -> "II — 6,5–9 кг"
    DogAdultWeightCategory.III -> "III — 9–15 кг"
    DogAdultWeightCategory.IV -> "IV — 15–30 кг"
    DogAdultWeightCategory.V -> "V — 30–40 кг"
}

private val MonthBirthDateFormatter = DateTimeFormatter.ofPattern("MM.yyyy")
private val DayBirthDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun PetBirthDateInput.hasBlankComponent(): Boolean = when (this) {
    PetBirthDateInput.Empty -> false
    is PetBirthDateInput.Year -> year.isBlank()
    is PetBirthDateInput.Month -> year.isBlank() || month.isBlank()
    is PetBirthDateInput.Day -> year.isBlank() || month.isBlank() || day.isBlank()
}

private fun String.toPositiveIntOrNull(): Int? = trim()
    .takeIf { value -> value.isNotEmpty() && value.all { it in '0'..'9' } }
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

private fun invalidBirthDate() = PetBirthDateInputValidation(
    value = null,
    error = PetBirthDateValidationError.INVALID,
)
