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
import com.palixander.scalesync.core.reference.ReferenceKind
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
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    private val supportedCatBreedIds = weightReferenceSnapshot?.profiles
        .orEmpty()
        .asSequence()
        .filter { profile ->
            profile.species == ReferenceSpecies.CAT &&
                profile.basis == ReferenceBasis.BREED &&
                profile.referenceKind == ReferenceKind.MODELLED_BREED_ADULT_RANGE &&
                profile.breedId != null
        }
        .mapNotNull { profile -> profile.breedId }
        .toSet()
    private val supportedCatBreeds = supportedCatBreedIds
        .mapNotNull(catalog::findById)
        .filter { it.species == BreedSpecies.CAT }
    private val selectableBreeds = buildMap {
        supportedDogBreeds.forEach { put(it.breedId, PetSpecies.DOG) }
        supportedCatBreeds.forEach { put(it.id, PetSpecies.CAT) }
    }
    private val localization = PetBreedLocalization.bundled(selectableBreeds, catalog)

    /** Searches only product-supported breeds. The full VBO catalog remains internal. */
    fun search(
        query: String,
        species: PetSpecies,
        locale: Locale = Locale.forLanguageTag("ru"),
    ): List<PetBreedOption> = when (species) {
        PetSpecies.DOG -> searchDogs(query, locale)
        PetSpecies.CAT -> searchCats(query, locale)
        PetSpecies.UNSPECIFIED -> emptyList()
    }

    private fun searchDogs(query: String, locale: Locale): List<PetBreedOption> {
        val needle = PetBreedLocalization.normalize(query)
        return supportedDogBreeds
            .asSequence()
            .map { toPetBreedOption(it, locale) }
            .filter { breed ->
                needle.isEmpty() || sequenceOf(
                    breed.displayName, breed.canonicalName, *breed.aliases.toTypedArray(),
                ).any { PetBreedLocalization.normalize(it).contains(needle) }
            }
            .sortedWith(PetBreedLocalization.comparator(locale))
            .toList()
    }

    private fun searchCats(query: String, locale: Locale): List<PetBreedOption> {
        val needle = PetBreedLocalization.normalize(query)
        return supportedCatBreeds
            .asSequence()
            .map { toPetBreedOption(it, locale) }
            .filter { option ->
                needle.isEmpty() || sequenceOf(
                    option.displayName,
                    option.canonicalName,
                    *option.aliases.toTypedArray(),
                ).any { needle in PetBreedLocalization.normalize(it) }
            }
            .sortedWith(PetBreedLocalization.comparator(locale))
            .toList()
    }

    fun resolve(
        id: BreedId,
        savedSpecies: PetSpecies,
        locale: Locale = Locale.forLanguageTag("ru"),
    ): PetBreedSelection {
        val canonicalId = canonicalBreedId(id.value)
        snapshot?.breed(canonicalId)?.let { return PetBreedSelection.Available(toPetBreedOption(it, locale)) }
        supportedCatBreeds.singleOrNull { it.id == canonicalId }
            ?.let { return PetBreedSelection.Available(toPetBreedOption(it, locale)) }
        return PetBreedSelection.Unavailable(BreedId(canonicalId), savedSpecies)
    }

    private fun toPetBreedOption(breed: BreedReferenceBreed, locale: Locale): PetBreedOption = PetBreedOption(
        id = BreedId(breed.breedId),
        species = PetSpecies.DOG,
        displayName = localization.displayName(breed.breedId, breed.englishName, breed.russianName, locale),
        canonicalName = breed.englishName,
        aliases = breed.aliases.flatMap { alias ->
            catalog.findById(alias)?.let { listOf(it.displayNameRu, it.canonicalName) + it.aliases }
                .orEmpty()
        },
        kind = BreedKind.VBO,
    )

    private fun toPetBreedOption(breed: BreedRecord, locale: Locale): PetBreedOption = PetBreedOption(
        id = BreedId(breed.id),
        species = when (breed.species) {
            BreedSpecies.CAT -> PetSpecies.CAT
            BreedSpecies.DOG -> PetSpecies.DOG
        },
        displayName = localization.displayName(
            breed.id,
            breed.canonicalName,
            breed.displayNameRu,
            locale,
        ),
        canonicalName = breed.canonicalName,
        aliases = breed.aliases,
        kind = breed.kind,
    )
}

data class PetProfileDraft(
    val mode: PetProfileEditorMode,
    val displayName: String,
    val species: PetSpecies?,
    val sex: PetSex? = null,
    val breed: PetBreedSelection? = null,
    val birthDate: PetBirthDateInput = PetBirthDateInput.Empty,
    val dogAdultWeightCategory: DogAdultWeightCategory? = null,
    val photoPath: String? = null,
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
            photoPath = pet.photoPath,
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
    data class RestorePersisted(val state: PetProfileEditorState) : PetProfileAction
    data class DisplayNameChanged(val value: String) : PetProfileAction
    data class SpeciesChangeRequested(val species: PetSpecies) : PetProfileAction
    data class SexChanged(val sex: PetSex?) : PetProfileAction
    data class BreedChanged(val breed: PetBreedSelection?) : PetProfileAction
    data class BirthDateChanged(val birthDate: PetBirthDateInput) : PetProfileAction
    data class DogAdultWeightCategoryChanged(
        val category: DogAdultWeightCategory?,
    ) : PetProfileAction
    data class PhotoChanged(val photoPath: String?) : PetProfileAction

    data object ConfirmSpeciesChange : PetProfileAction
    data object CancelSpeciesChange : PetProfileAction
}

object PetProfileReducer {
    fun reduce(
        state: PetProfileEditorState,
        action: PetProfileAction,
    ): PetProfileEditorState {
        val pending = state.pendingSpeciesChange
        if (action is PetProfileAction.RestorePersisted) return action.state
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
            is PetProfileAction.RestorePersisted -> action.state
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
            is PetProfileAction.PhotoChanged -> state.withDraft { copy(photoPath = action.photoPath) }
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
                photoPath = draft.photoPath,
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
                photoPath = draft.photoPath,
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

fun petSexLabel(sex: PetSex?): UiText = when (sex) {
    PetSex.MALE -> uiText(R.string.pet_sex_male)
    PetSex.FEMALE -> uiText(R.string.pet_sex_female)
    null -> uiText(R.string.pet_sex_unspecified)
}

fun petBreedLabel(breed: PetBreedSelection?): UiText = when (breed) {
    is PetBreedSelection.Available -> UiText.Raw(breed.option.displayName)
    is PetBreedSelection.Unavailable -> uiText(R.string.pet_breed_unavailable, breed.id.value)
    null -> uiText(R.string.pet_breed_other)
}

fun birthDatePrecisionLabel(precision: BirthDatePrecision): UiText = when (precision) {
    BirthDatePrecision.YEAR -> uiText(R.string.birth_precision_year)
    BirthDatePrecision.MONTH -> uiText(R.string.birth_precision_month)
    BirthDatePrecision.DAY -> uiText(R.string.birth_precision_day)
}

fun formatPartialBirthDate(birthDate: PartialBirthDate): String = when (birthDate) {
    is PartialBirthDate.Year -> birthDate.value.toString()
    is PartialBirthDate.Month -> birthDate.value.format(MonthBirthDateFormatter)
    is PartialBirthDate.Day -> birthDate.value.format(DayBirthDateFormatter)
}

fun partialBirthDateLabel(birthDate: PartialBirthDate): UiText = uiText(
    R.string.birth_date_with_precision,
    formatPartialBirthDate(birthDate),
    birthDatePrecisionLabel(birthDate.precision),
)

fun dogAdultWeightCategoryLabel(category: DogAdultWeightCategory): UiText = when (category) {
    DogAdultWeightCategory.I -> uiText(R.string.dog_weight_i)
    DogAdultWeightCategory.II -> uiText(R.string.dog_weight_ii)
    DogAdultWeightCategory.III -> uiText(R.string.dog_weight_iii)
    DogAdultWeightCategory.IV -> uiText(R.string.dog_weight_iv)
    DogAdultWeightCategory.V -> uiText(R.string.dog_weight_v)
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
