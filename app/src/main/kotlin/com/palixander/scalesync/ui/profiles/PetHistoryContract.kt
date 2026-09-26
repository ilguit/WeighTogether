package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText
import com.palixander.scalesync.R

import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.charts.ChartMetricOption
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.formatChartCurrentValue
import com.palixander.scalesync.charts.inclusiveDateRangeToEpochRange
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.reference.DogAdultWeight
import com.palixander.scalesync.domain.reference.IntactStatus
import com.palixander.scalesync.domain.reference.PetWeightReferenceResolution
import com.palixander.scalesync.domain.reference.PetWeightReferenceResolver
import com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.WeightReferenceProvenance
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferenceBoundsStatistic
import com.palixander.scalesync.core.reference.ReferenceCenterStatistic
import com.palixander.scalesync.core.reference.ReferenceProfileMetadata
import com.palixander.scalesync.core.reference.ReferenceKind
import com.palixander.scalesync.core.reference.ReferenceSourceAuthorityClass
import com.palixander.scalesync.core.reference.ReferenceAgeAvailability
import com.palixander.scalesync.core.reference.ReferenceSex
import com.palixander.scalesync.core.reference.ReferenceSpecies
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

val PetWeightChartMetric = ChartMetricOption(
    key = "petWeightKg",
    displayName = "Вес питомца",
    unit = "кг",
    decimalPlaces = 2,
)

sealed interface PetHistoryContent {
    data object Empty : PetHistoryContent
    data class Single(val measurement: PetHistoryMeasurementUi) : PetHistoryContent
    data class Multiple(val measurements: List<PetHistoryMeasurementUi>) : PetHistoryContent
}

data class PetHistoryMeasurementUi(
    val id: String,
    val measuredAtEpochSecond: Long,
    val measuredAtText: String,
    val weightKg: Double,
    val weightText: String,
    val origin: MeasurementOrigin = MeasurementOrigin.LEGACY,
    val isManuallyEdited: Boolean = false,
)

data class PetHistoryDeleteConfirmation(
    val petId: PetId,
    val measurement: PetHistoryMeasurementUi,
    val isDeleting: Boolean = false,
)

data class PetHistoryUiState(
    val petId: PetId,
    val pet: Pet? = null,
    val profileSummary: PetProfileSummary? = null,
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val rangePreset: ChartRangePreset,
    val content: PetHistoryContent = PetHistoryContent.Empty,
    val series: ChartSeries = ChartSeries(PetWeightChartMetric, emptyList()),
    val weightReference: PetHistoryWeightReference = PetHistoryWeightReference.Unavailable(
        WeightReferenceUnavailableReason.UnsupportedSpecies,
        uiText(R.string.pet_reference_unavailable_species),
    ),
    val breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    val breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
    val isLoading: Boolean = true,
    val isNotFound: Boolean = false,
    val errorMessage: UiText? = null,
    val deleteConfirmation: PetHistoryDeleteConfirmation? = null,
    val actionErrorMessage: UiText? = null,
    val scrollToMeasurementId: String? = null,
    val weightEditor: PetWeightEditorState? = null,
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) { "The end date must not precede the start date." }
    }

    val measurements: List<PetHistoryMeasurementUi>
        get() = when (val current = content) {
            PetHistoryContent.Empty -> emptyList()
            is PetHistoryContent.Single -> listOf(current.measurement)
            is PetHistoryContent.Multiple -> current.measurements
        }

    companion object {
        fun initial(
            petId: PetId,
            clock: Clock = Clock.systemDefaultZone(),
        ): PetHistoryUiState {
            val today = LocalDate.now(clock)
            val range = requireNotNull(ChartRangePreset.LAST_30_DAYS.rangeEndingOn(today))
            return PetHistoryUiState(
                petId = petId,
                startDate = range.startDate,
                endDateInclusive = range.endDateInclusive,
                rangePreset = ChartRangePreset.LAST_30_DAYS,
            )
        }
    }
}

sealed interface PetHistoryWeightReference {
    data class Available(
        val basis: ReferenceBasis,
        val provenance: WeightReferenceProvenance = WeightReferenceProvenance.POPULATION,
        val selectedBreedId: com.palixander.scalesync.domain.BreedId? = null,
        /** Russian user-facing clarification when the selected breed cannot supply a full curve. */
        val provenanceExplanation: UiText? = null,
        /** Separate segments must be drawn separately; gaps must never be connected. */
        val segments: List<PetHistoryReferenceSegment>,
        val approximate: Boolean,
        val ageLabel: UiText,
        val basisLabel: UiText,
        val sourceLabel: UiText,
        val citation: String,
        val license: String,
        val constraints: List<String>,
        val sourceAuthorityLabel: UiText? = null,
        val sourceDisclosure: String? = null,
        val sourceAccessedDate: String? = null,
        val accessibilityLabel: UiText,
        val publicationUrl: String? = null,
        val isFittedPopulationPercentiles: Boolean = false,
        val centerStatistic: ReferenceCenterStatistic = ReferenceCenterStatistic.MEDIAN,
        val boundsStatistic: ReferenceBoundsStatistic = ReferenceBoundsStatistic.QUARTILES,
    ) : PetHistoryWeightReference

    data class Unavailable(
        val reason: WeightReferenceUnavailableReason,
        val explanation: UiText,
    ) : PetHistoryWeightReference
}

data class PetHistoryReferenceSegment(
    val profileId: String,
    val sourceId: String,
    val provenance: WeightReferenceProvenance,
    val citation: String,
    val license: String,
    val publicationUrl: String?,
    val points: List<PetHistoryReferencePoint>,
) : List<PetHistoryReferencePoint> by points

data class PetHistoryReferencePoint(
    val date: LocalDate,
    val lowerKg: Double,
    val medianLowerKg: Double,
    val medianUpperKg: Double,
    val upperKg: Double,
)

class PetHistoryReferencePresenter(
    private val snapshot: WeightReferenceSnapshot? = runCatching { WeightReferenceSnapshot.bundled() }.getOrNull(),
    private val resolver: PetWeightReferenceResolver? = snapshot?.let(::PetWeightReferenceResolver),
) {
    fun present(pet: Pet, range: ChartDateRange): PetHistoryWeightReference {
        val availableSnapshot = snapshot ?: return snapshotUnavailable(pet)
        if (resolver == null) return snapshotUnavailable(pet)
        // Missing profile data cannot become available later in the selected range. Resolve the
        // newest date first so an invalid imported historical date does not cause an unbounded
        // walk before discovering that there is no overlay to draw at all.
        val endResolution = resolve(pet, range.endDateInclusive)
        val staticUnavailable = (endResolution as? PetWeightReferenceResolution.Unavailable)
            ?.takeIf { it.reason.isRangeInvariant() }
        if (staticUnavailable != null) {
            return PetHistoryWeightReference.Unavailable(
                staticUnavailable.reason,
                weightReferenceUnavailableExplanation(staticUnavailable.reason, pet.species),
            )
        }

        val sampleDates = referenceSampleDates(range, pet, availableSnapshot)
        if (sampleDates.isEmpty()) {
            val profileId = (endResolution as? PetWeightReferenceResolution.Available)
                ?.reference?.profileId ?: "unknown"
            val reason = WeightReferenceUnavailableReason.ProfileUnavailable(profileId)
            return PetHistoryWeightReference.Unavailable(reason, weightReferenceUnavailableExplanation(reason, pet.species))
        }
        val dated = sampleDates.map { date ->
            date to if (date == range.endDateInclusive) endResolution else resolve(pet, date)
        }

        val available = dated.mapNotNull { (date, result) ->
            (result as? PetWeightReferenceResolution.Available)?.reference?.let { date to it }
        }
        if (available.isEmpty()) {
            val reason = (dated.firstOrNull()?.second as? PetWeightReferenceResolution.Unavailable)?.reason
                ?: WeightReferenceUnavailableReason.ReferenceDataGap("unknown", LongRange.EMPTY)
            return PetHistoryWeightReference.Unavailable(reason, weightReferenceUnavailableExplanation(reason, pet.species))
        }
        val reference = available.last().second
        val metadata = availableSnapshot.metadataFor(reference.profileId, reference.sourceId)
            ?: return PetHistoryWeightReference.Unavailable(
                WeightReferenceUnavailableReason.ProfileUnavailable(reference.profileId),
                weightReferenceUnavailableExplanation(
                    WeightReferenceUnavailableReason.ProfileUnavailable(reference.profileId),
                    pet.species,
                ),
            )
        data class SegmentIdentity(val profileId: String, val sourceId: String, val provenance: WeightReferenceProvenance)
        val segments = mutableListOf<Pair<SegmentIdentity, MutableList<PetHistoryReferencePoint>>>()
        var previousIdentity: SegmentIdentity? = null
        var gapBeforeNextPoint = true
        dated.forEach { (date, resolution) ->
            val reference = (resolution as? PetWeightReferenceResolution.Available)?.reference
            if (reference == null) {
                gapBeforeNextPoint = true
                previousIdentity = null
                return@forEach
            }
            val identity = SegmentIdentity(reference.profileId, reference.sourceId, reference.provenance)
            if (gapBeforeNextPoint || previousIdentity != identity) {
                segments += identity to mutableListOf()
            }
            segments.last().second += PetHistoryReferencePoint(
                date,
                reference.bounds.lowerKg,
                reference.bounds.medianLowerKg,
                reference.bounds.medianUpperKg,
                reference.bounds.upperKg,
            )
            previousIdentity = identity
            gapBeforeNextPoint = false
        }
        val approximate = available.any { it.second.approximate }
        val minAge = available.minOf { it.second.ageDays.first }
        val maxAge = available.maxOf { it.second.ageDays.last }
        return availablePresentation(
            metadata = metadata,
            segments = segments.map { (identity, points) ->
                val segmentMetadata = availableSnapshot.metadataFor(identity.profileId, identity.sourceId)
                    ?: return PetHistoryWeightReference.Unavailable(
                        WeightReferenceUnavailableReason.ProfileUnavailable(identity.profileId),
                        weightReferenceUnavailableExplanation(
                            WeightReferenceUnavailableReason.ProfileUnavailable(identity.profileId),
                            pet.species,
                        ),
                    )
                PetHistoryReferenceSegment(
                    profileId = identity.profileId,
                    sourceId = identity.sourceId,
                    provenance = identity.provenance,
                    citation = segmentMetadata.source.citation,
                    license = segmentMetadata.source.license,
                    publicationUrl = "https://doi.org/${segmentMetadata.source.publicationDoi}",
                    points = points.toList(),
                )
            },
            approximate = approximate,
            minAge = minAge,
            maxAge = maxAge,
            provenance = reference.provenance,
            selectedBreedId = reference.selectedBreedId,
        )
    }

    private fun WeightReferenceUnavailableReason.isRangeInvariant(): Boolean = when (this) {
        WeightReferenceUnavailableReason.InvalidBirthDate,
        is WeightReferenceUnavailableReason.AgeOutOfRange,
        is WeightReferenceUnavailableReason.ReferenceDataGap,
        -> false
        else -> true
    }

    private fun resolve(pet: Pet, date: LocalDate) = requireNotNull(resolver).resolve(
        species = pet.species,
        sex = pet.sex,
        breedId = pet.breedId,
        birthDate = pet.birthDate,
        referenceDate = date,
        dogAdultWeight = pet.dogAdultWeightCategory?.let(DogAdultWeight::Category),
        intactStatus = IntactStatus.UNKNOWN,
    )

    private fun snapshotUnavailable(pet: Pet): PetHistoryWeightReference.Unavailable {
        val reason = WeightReferenceUnavailableReason.ProfileUnavailable("bundled-snapshot")
        return PetHistoryWeightReference.Unavailable(
            reason,
            weightReferenceUnavailableExplanation(reason, pet.species),
        )
    }

    private fun availablePresentation(
        metadata: ReferenceProfileMetadata,
        segments: List<PetHistoryReferenceSegment>,
        approximate: Boolean,
        minAge: Long,
        maxAge: Long,
        provenance: WeightReferenceProvenance,
        selectedBreedId: com.palixander.scalesync.domain.BreedId?,
    ): PetHistoryWeightReference.Available {
        val age = if (minAge == maxAge) "$minAge" else "$minAge–$maxAge"
        val ageLabel = uiText(
            if (approximate) R.string.pet_reference_age_days_approximate else R.string.pet_reference_age_days,
            age,
        )
        val isExactBreedObservation = provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION
        val basisLabel = if (metadata.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES) {
            uiText(R.string.pet_reference_weight_data)
        } else if (isExactBreedObservation || metadata.referenceKind == ReferenceKind.EMPIRICAL_OBSERVATION_MEAN_SD) {
            uiText(R.string.pet_reference_breed_observation)
        } else when (metadata.basis) {
            ReferenceBasis.BREED -> uiText(R.string.pet_reference_breed_basis)
            ReferenceBasis.WEIGHT_CATEGORY -> uiText(R.string.pet_reference_weight_category_basis)
            ReferenceBasis.POPULATION -> uiText(R.string.pet_reference_weight_data)
        }
        return PetHistoryWeightReference.Available(
            metadata.basis,
            provenance,
            selectedBreedId,
            weightReferenceProvenanceExplanation(provenance),
            segments,
            approximate,
            ageLabel,
            basisLabel,
            uiText(R.string.pet_reference_source, metadata.source.citation),
            metadata.source.citation,
            metadata.source.license,
            metadata.constraints,
            metadata.source.authorityClass.localizedLabel(),
            metadata.source.disclosure,
            metadata.source.accessedDate,
            uiText(R.string.pet_reference_accessibility, basisLabel, ageLabel, metadata.source.citation, metadata.source.license),
            metadata.source.publicationDoi.takeIf(String::isNotBlank)?.let { "https://doi.org/$it" }
                ?: metadata.source.dataUrl,
            metadata.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES,
            if (isExactBreedObservation) ReferenceCenterStatistic.MEAN else metadata.centerStatistic,
            if (isExactBreedObservation) ReferenceBoundsStatistic.ONE_STANDARD_DEVIATION else metadata.boundsStatistic,
        )
    }
}

private fun ReferenceSourceAuthorityClass.localizedLabel(): UiText = when (this) {
    ReferenceSourceAuthorityClass.OFFICIAL_BREED_ORGANIZATION -> uiText(R.string.pet_reference_authority_breed)
    ReferenceSourceAuthorityClass.PROFESSIONAL_REFERENCE -> uiText(R.string.pet_reference_authority_professional)
    ReferenceSourceAuthorityClass.RESEARCH_PUBLICATION -> uiText(R.string.pet_reference_authority_research)
    ReferenceSourceAuthorityClass.OPEN_REFERENCE -> uiText(R.string.pet_reference_authority_open)
}

fun weightReferenceProvenanceExplanation(provenance: WeightReferenceProvenance): UiText? = when (provenance) {
    WeightReferenceProvenance.BREED_CURVE ->
        uiText(R.string.pet_reference_provenance_model)
    WeightReferenceProvenance.BREED_EXACT_OBSERVATION ->
        uiText(R.string.pet_reference_provenance_birth)
    WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED ->
        uiText(R.string.pet_reference_provenance_population)
    WeightReferenceProvenance.POPULATION,
    WeightReferenceProvenance.WEIGHT_CATEGORY,
    -> null
}

internal const val MAX_REFERENCE_CHART_SAMPLES = 512

/** Daily for normal filters; evenly bounded for imported histories spanning many years. */
internal fun referenceSampleDates(
    range: ChartDateRange,
    pet: Pet? = null,
    snapshot: WeightReferenceSnapshot? = null,
): List<LocalDate> {
    val spanDays = ChronoUnit.DAYS.between(range.startDate, range.endDateInclusive)
    val dayCount = spanDays + 1
    if (dayCount <= MAX_REFERENCE_CHART_SAMPLES) {
        return List(dayCount.toInt()) { offset -> range.startDate.plusDays(offset.toLong()) }
    }
    val semanticDates = if (pet == null || snapshot == null) emptySet() else {
        referenceSemanticDates(pet, snapshot, range)
    }
    // Every semantic date is needed to preserve a published point or a gap boundary. If a future
    // valid snapshot cannot fit those dates, omit the overlay instead of throwing or drawing a
    // misleading line. A valid range itself is never empty, so [] is an unambiguous fail-closed
    // signal to the presenter.
    if (semanticDates.size > MAX_REFERENCE_CHART_SAMPLES) return emptyList()
    val remaining = (MAX_REFERENCE_CHART_SAMPLES - semanticDates.size).coerceAtLeast(2)
    val sampled = List(remaining) { index ->
        val offset = spanDays * index / (remaining - 1)
        range.startDate.plusDays(offset)
    }
    return (semanticDates + sampled).sorted().takeBoundedPreservingSemantic(semanticDates)
}

private fun referenceSemanticDates(
    pet: Pet,
    snapshot: WeightReferenceSnapshot,
    range: ChartDateRange,
): Set<LocalDate> {
    val birthDate = pet.birthDate ?: return emptySet()
    val births = when (birthDate) {
        is com.palixander.scalesync.domain.PartialBirthDate.Day -> listOf(birthDate.value)
        is com.palixander.scalesync.domain.PartialBirthDate.Month -> listOf(birthDate.value.atDay(1), birthDate.value.atEndOfMonth())
        is com.palixander.scalesync.domain.PartialBirthDate.Year -> listOf(birthDate.value.atDay(1), birthDate.value.atMonth(12).atEndOfMonth())
    }
    val species = when (pet.species) {
        com.palixander.scalesync.domain.PetSpecies.CAT -> ReferenceSpecies.CAT
        com.palixander.scalesync.domain.PetSpecies.DOG -> ReferenceSpecies.DOG
        com.palixander.scalesync.domain.PetSpecies.UNSPECIFIED -> return emptySet()
    }
    val sex = when (pet.sex) {
        com.palixander.scalesync.domain.PetSex.FEMALE -> ReferenceSex.FEMALE
        com.palixander.scalesync.domain.PetSex.MALE -> ReferenceSex.MALE
        null -> return emptySet()
    }
    val profiles = snapshot.profiles.filter { profile ->
        profile.species == species && profile.sex == sex && when (profile.basis) {
            ReferenceBasis.BREED -> profile.breedId == pet.breedId?.value
            ReferenceBasis.POPULATION -> species == ReferenceSpecies.CAT && profile.breedId == null
            ReferenceBasis.WEIGHT_CATEGORY -> species == ReferenceSpecies.DOG &&
                profile.weightCategory == pet.dogAdultWeightCategory?.name
        }
    }
    return buildSet {
        profiles.forEach { profile ->
            val ages = buildSet {
                profile.points.forEach { point -> add(point.ageDays) }
                val scope = snapshot.manifest.scopes.singleOrNull { it.id == profile.id }
                scope?.let { add(it.minimumAgeDays); add(it.maximumAgeDays) }
            }
            ages.forEach { age ->
                val offsets = if (profile.ageAvailability == ReferenceAgeAvailability.EXACT_OBSERVATIONS) {
                    listOf(-1, 0, 1)
                } else {
                    listOf(0)
                }
                births.forEach { birth -> offsets.forEach { delta ->
                    birth.safePlusDays(age.toLong() + delta)?.takeIf { it in range.startDate..range.endDateInclusive }?.let(::add)
                } }
            }
            val scope = snapshot.manifest.scopes.singleOrNull { it.id == profile.id }
            scope?.let {
                births.forEach { birth ->
                    listOf(it.minimumAgeDays.toLong() - 1, it.maximumAgeDays.toLong() + 1).forEach { age ->
                        birth.safePlusDays(age)?.takeIf { date -> date in range.startDate..range.endDateInclusive }?.let(::add)
                    }
                }
            }
        }
    }
}

private fun LocalDate.safePlusDays(days: Long): LocalDate? = runCatching { plusDays(days) }.getOrNull()

private fun List<LocalDate>.takeBoundedPreservingSemantic(semantic: Set<LocalDate>): List<LocalDate> {
    if (size <= MAX_REFERENCE_CHART_SAMPLES) return this
    val sampled = filterNot(semantic::contains)
    val slots = MAX_REFERENCE_CHART_SAMPLES - semantic.size
    val retained = if (slots <= 0) emptyList() else List(slots) { index ->
        sampled[index * (sampled.size - 1) / (slots - 1).coerceAtLeast(1)]
    }
    return (semantic + retained).sorted()
}

fun weightReferenceUnavailableExplanation(reason: WeightReferenceUnavailableReason): UiText = when (reason) {
    WeightReferenceUnavailableReason.MissingSex -> uiText(R.string.pet_reference_unavailable_sex)
    WeightReferenceUnavailableReason.MissingBirthDate -> uiText(R.string.pet_reference_unavailable_birth_date)
    WeightReferenceUnavailableReason.MissingBreed -> uiText(R.string.pet_reference_unavailable_breed)
    WeightReferenceUnavailableReason.MissingDogAdultWeight -> uiText(R.string.pet_reference_unavailable_dog_weight)
    WeightReferenceUnavailableReason.UnsupportedSpecies -> uiText(R.string.pet_reference_unavailable_species)
    is WeightReferenceUnavailableReason.UnknownBreed -> uiText(R.string.pet_reference_unavailable_unknown_breed, reason.breedId)
    is WeightReferenceUnavailableReason.BreedSpeciesMismatch -> uiText(R.string.pet_reference_unavailable_breed_mismatch, reason.breedId)
    is WeightReferenceUnavailableReason.UnsupportedBreed -> uiText(R.string.pet_reference_unavailable_unsupported_breed)
    WeightReferenceUnavailableReason.DshIntactStatusUnknown -> uiText(R.string.pet_reference_unavailable_intact_unknown)
    WeightReferenceUnavailableReason.DshNotIntact -> uiText(R.string.pet_reference_unavailable_not_intact)
    WeightReferenceUnavailableReason.InvalidBirthDate -> uiText(R.string.pet_reference_unavailable_invalid_birth)
    is WeightReferenceUnavailableReason.ProfileUnavailable -> uiText(R.string.pet_reference_unavailable_profile, reason.profileId)
    is WeightReferenceUnavailableReason.ReferenceDataGap -> uiText(R.string.pet_reference_unavailable_gap, reason.profileId)
    is WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum -> uiText(R.string.pet_reference_unavailable_weight_max, reason.weightKg)
    is WeightReferenceUnavailableReason.AgeOutOfRange -> uiText(R.string.pet_reference_unavailable_age_range, reason.supportedMinimumDays, reason.supportedMaximumDays)
}

private fun weightReferenceUnavailableExplanation(
    reason: WeightReferenceUnavailableReason,
    species: com.palixander.scalesync.domain.PetSpecies,
): UiText = if (species == com.palixander.scalesync.domain.PetSpecies.CAT) {
    when (reason) {
        WeightReferenceUnavailableReason.MissingSex ->
            uiText(R.string.pet_breed_reference_prompt_sex)
        WeightReferenceUnavailableReason.MissingBirthDate ->
            uiText(R.string.pet_breed_reference_prompt_birth)
        WeightReferenceUnavailableReason.InvalidBirthDate ->
            uiText(R.string.pet_breed_reference_fix_birth)
        is WeightReferenceUnavailableReason.UnsupportedBreed,
        is WeightReferenceUnavailableReason.UnknownBreed,
        is WeightReferenceUnavailableReason.BreedSpeciesMismatch,
        -> uiText(R.string.pet_breed_reference_unavailable)
        is WeightReferenceUnavailableReason.AgeOutOfRange,
        is WeightReferenceUnavailableReason.ReferenceDataGap,
        -> uiText(R.string.pet_breed_reference_age_gap)
        is WeightReferenceUnavailableReason.ProfileUnavailable ->
            uiText(R.string.pet_breed_reference_temporary)
        else -> weightReferenceUnavailableExplanation(reason)
    }
} else {
    weightReferenceUnavailableExplanation(reason)
}

data class PetHistoryCallbacks(
    val selectRangePreset: (ChartRangePreset) -> Unit,
    val setDateRange: (startDate: LocalDate, endDateInclusive: LocalDate) -> Unit,
    val requestDelete: (measurementId: String) -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val dismissDelete: () -> Unit = {},
    val dismissActionError: () -> Unit = {},
    val onAddWeightRequested: () -> Unit = {},
    val onScrollToMeasurementHandled: () -> Unit = {},
    val editMeasurement: (measurementId: String) -> Unit = {},
    val changeEditedWeight: (String) -> Unit = {},
    val saveEditedWeight: () -> Unit = {},
    val dismissWeightEditor: () -> Unit = {},
)

data class PetWeightEditorState(
    val measurementId: String,
    val petName: String,
    val measuredAtText: String,
    val originalWeightKg: Double,
    val weightInput: String,
    val isSaving: Boolean = false,
    val saveError: UiText? = null,
    val isUnavailable: Boolean = false,
) {
    val parsedWeightKg: Double?
        get() = parsePetWeightInput(weightInput)
    val canSave: Boolean
        get() = !isSaving && !isUnavailable && parsedWeightKg != null &&
            canonicalPetWeight(parsedWeightKg!!) != canonicalPetWeight(originalWeightKg)
}

internal fun canonicalPetWeight(weightKg: Double): String = java.math.BigDecimal.valueOf(weightKg)
    .setScale(3, java.math.RoundingMode.HALF_EVEN)
    .stripTrailingZeros()
    .toPlainString()

internal fun parsePetWeightInput(input: String): Double? {
    val normalized = input.trim().replace(',', '.')
    if (!Regex("[0-9]+(?:\\.[0-9]{0,3})?").matches(normalized)) return null
    val value = normalized.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() && it >= 0.001 }
}

internal fun petHistoryPresentation(
    measurements: List<PetMeasurement>,
    range: ChartDateRange,
    zoneId: ZoneId,
    locale: Locale,
    includeAll: Boolean = false,
): Pair<PetHistoryContent, ChartSeries> {
    val epochRange = inclusiveDateRangeToEpochRange(
        range.startDate,
        range.endDateInclusive,
        zoneId,
    )
    val start = java.time.Instant.ofEpochSecond(epochRange.startInclusiveEpochSecond)
    val endExclusive = java.time.Instant.ofEpochSecond(epochRange.endExclusiveEpochSecond)
    val ordered = measurements
        .asSequence()
        .filter { includeAll || (!it.measuredAt.isBefore(start) && it.measuredAt.isBefore(endExclusive)) }
        .sortedWith(compareByDescending<PetMeasurement> { it.measuredAt }.thenByDescending { it.id })
        .toList()
    val rows = ordered.map { measurement ->
        PetHistoryMeasurementUi(
            id = measurement.id,
            measuredAtEpochSecond = measurement.measuredAt.epochSecond,
            measuredAtText = runCatching {
                formatMeasurementDateTime(measurement.measuredAt, zoneId, locale)
            }.getOrElse { measurement.measuredAt.toString() },
            weightKg = measurement.petWeightKg,
            origin = measurement.origin,
            isManuallyEdited = measurement.isManuallyEdited,
            weightText = formatChartCurrentValue(measurement.petWeightKg, PetWeightChartMetric, locale),
        )
    }
    val content = when (rows.size) {
        0 -> PetHistoryContent.Empty
        1 -> PetHistoryContent.Single(rows.single())
        else -> PetHistoryContent.Multiple(rows)
    }
    val points = ordered
        .asReversed()
        .map { ChartPoint(it.measuredAt.epochSecond, it.petWeightKg) }
    return content to ChartSeries(PetWeightChartMetric, points)
}
