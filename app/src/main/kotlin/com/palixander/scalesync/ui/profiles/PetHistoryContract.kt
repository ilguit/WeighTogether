package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.domain.MeasurementOrigin

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
        "Эталон недоступен: вид питомца не указан.",
    ),
    val breedReference: PetHistoryBreedReference = PetHistoryBreedReference.Hidden,
    val breedReferenceTimeline: List<PetHistoryBreedReferenceTimelinePoint> = emptyList(),
    val isLoading: Boolean = true,
    val isNotFound: Boolean = false,
    val errorMessage: String? = null,
    val deleteConfirmation: PetHistoryDeleteConfirmation? = null,
    val actionErrorMessage: String? = null,
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
            return PetHistoryUiState(
                petId = petId,
                startDate = today,
                endDateInclusive = today,
                rangePreset = ChartRangePreset.ALL,
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
        val provenanceExplanation: String? = null,
        /** Separate segments must be drawn separately; gaps must never be connected. */
        val segments: List<PetHistoryReferenceSegment>,
        val approximate: Boolean,
        val ageLabel: String,
        val basisLabel: String,
        val sourceLabel: String,
        val citation: String,
        val license: String,
        val constraints: List<String>,
        val sourceAuthorityLabel: String? = null,
        val sourceDisclosure: String? = null,
        val sourceAccessedDate: String? = null,
        val accessibilityLabel: String,
        val publicationUrl: String? = null,
        val isFittedPopulationPercentiles: Boolean = false,
        val centerStatistic: ReferenceCenterStatistic = ReferenceCenterStatistic.MEDIAN,
        val boundsStatistic: ReferenceBoundsStatistic = ReferenceBoundsStatistic.QUARTILES,
    ) : PetHistoryWeightReference

    data class Unavailable(
        val reason: WeightReferenceUnavailableReason,
        val explanation: String,
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
        val age = if (minAge == maxAge) "$minAge дн." else "$minAge–$maxAge дн."
        val ageLabel = "Возраст: ${if (approximate) "примерно " else ""}$age"
        val isExactBreedObservation = provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION
        val basisLabel = if (metadata.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES) {
            "Справочные данные о весе"
        } else if (isExactBreedObservation || metadata.referenceKind == ReferenceKind.EMPIRICAL_OBSERVATION_MEAN_SD) {
            "Наблюдение по породе: среднее ± одно стандартное отклонение"
        } else when (metadata.basis) {
            ReferenceBasis.BREED -> "Эталон по породе"
            ReferenceBasis.WEIGHT_CATEGORY -> "Эталон по весовой категории"
            ReferenceBasis.POPULATION -> "Справочные данные о весе"
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
            "Источник: ${metadata.source.citation}",
            metadata.source.citation,
            metadata.source.license,
            metadata.constraints.map(::localizedReferenceConstraint),
            metadata.source.authorityClass.localizedLabel(),
            metadata.source.disclosure.localizedDisclosure(),
            metadata.source.accessedDate,
            "$basisLabel. $ageLabel. Источник: ${metadata.source.citation}. Лицензия: ${metadata.source.license}.",
            metadata.source.publicationDoi.takeIf(String::isNotBlank)?.let { "https://doi.org/$it" }
                ?: metadata.source.dataUrl,
            metadata.referenceKind == ReferenceKind.FITTED_BCCG_PERCENTILES,
            if (isExactBreedObservation) ReferenceCenterStatistic.MEAN else metadata.centerStatistic,
            if (isExactBreedObservation) ReferenceBoundsStatistic.ONE_STANDARD_DEVIATION else metadata.boundsStatistic,
        )
    }
}

private fun ReferenceSourceAuthorityClass.localizedLabel(): String = when (this) {
    ReferenceSourceAuthorityClass.OFFICIAL_BREED_ORGANIZATION -> "официальная породная организация"
    ReferenceSourceAuthorityClass.PROFESSIONAL_REFERENCE -> "профессиональный справочник"
    ReferenceSourceAuthorityClass.RESEARCH_PUBLICATION -> "научная публикация"
    ReferenceSourceAuthorityClass.OPEN_REFERENCE -> "открытый справочник"
}

private fun String.localizedDisclosure(): String = when (this) {
    "Official feline or breed organization" -> "Официальная фелинологическая или породная организация"
    "Professional reference; not an official breed organization" ->
        "Профессиональный справочник; не официальная породная организация"
    "Open reference source" -> "Открытый справочный источник"
    else -> this
}

internal fun localizedReferenceConstraint(constraint: String): String = when (constraint) {
    "Domestic Shorthair only" -> "Только домашние короткошёрстные кошки"
    "Sexually intact kittens from the USA" -> "Нестерилизованные котята из США"
    "Age 8 to 78 weeks" -> "Возраст от 8 до 78 недель"
    "Other-breed fallback; source population was Domestic Shorthair" ->
        "Общий диапазон вместо породного; исходная популяция — домашние короткошёрстные кошки"
    "Age 8 to 78 weeks; runtime points are fitted P9/P50/P91" ->
        "Возраст от 8 до 78 недель; показаны расчётные P9, P50 и P91"
    "12–15 фунтов преобразованы точно по коэффициенту 1 lb = 0,45359237 кг" ->
        "12–15 фунтов преобразованы точно по коэффициенту 1 фунт = 0,45359237 кг"
    "18–22 фунта преобразованы точно по коэффициенту 1 lb = 0,45359237 кг" ->
        "18–22 фунта преобразованы точно по коэффициенту 1 фунт = 0,45359237 кг"
    else -> constraint
}

fun weightReferenceProvenanceExplanation(provenance: WeightReferenceProvenance): String? = when (provenance) {
    WeightReferenceProvenance.BREED_CURVE ->
        "Показан модельный возрастной диапазон выбранной породы, а не наблюдаемая породная кривая."
    WeightReferenceProvenance.BREED_EXACT_OBSERVATION ->
        "Для выбранной породы опубликовано только точечное наблюдение веса при рождении."
    WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED ->
        "Для выбранной породы нет полноценного возрастного диапазона; показан общий диапазон для кошек."
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

fun weightReferenceUnavailableExplanation(reason: WeightReferenceUnavailableReason): String = when (reason) {
    WeightReferenceUnavailableReason.MissingSex -> "Эталон недоступен: укажите пол питомца."
    WeightReferenceUnavailableReason.MissingBirthDate -> "Эталон недоступен: укажите дату рождения питомца."
    WeightReferenceUnavailableReason.MissingBreed -> "Эталон недоступен: укажите породу кошки."
    WeightReferenceUnavailableReason.MissingDogAdultWeight -> "Эталон недоступен: укажите ожидаемую весовую категорию взрослой собаки."
    WeightReferenceUnavailableReason.UnsupportedSpecies -> "Эталон недоступен: вид питомца не указан."
    is WeightReferenceUnavailableReason.UnknownBreed -> "Эталон недоступен: порода ${reason.breedId} не найдена."
    is WeightReferenceUnavailableReason.BreedSpeciesMismatch -> "Эталон недоступен: порода ${reason.breedId} не соответствует виду питомца."
    is WeightReferenceUnavailableReason.UnsupportedBreed -> "Для выбранной породы ориентиры сейчас недоступны."
    WeightReferenceUnavailableReason.DshIntactStatusUnknown -> "Эталон недоступен: для домашней короткошёрстной кошки нужны подтверждённые данные о стерилизации."
    WeightReferenceUnavailableReason.DshNotIntact -> "Эталон недоступен: опубликованные данные относятся только к нестерилизованным животным."
    WeightReferenceUnavailableReason.InvalidBirthDate -> "Эталон недоступен: дата рождения позже выбранного периода."
    is WeightReferenceUnavailableReason.ProfileUnavailable -> "Эталон недоступен: профиль ${reason.profileId} не содержит воспроизводимых числовых данных."
    is WeightReferenceUnavailableReason.ReferenceDataGap -> "Эталон недоступен: в опубликованных данных профиля ${reason.profileId} есть пробел для этого возраста."
    is WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum -> "Эталон недоступен: вес ${reason.weightKg} кг выше поддерживаемого источником максимума."
    is WeightReferenceUnavailableReason.AgeOutOfRange -> "Эталон недоступен: возраст вне опубликованного диапазона ${reason.supportedMinimumDays}–${reason.supportedMaximumDays} дней."
}

private fun weightReferenceUnavailableExplanation(
    reason: WeightReferenceUnavailableReason,
    species: com.palixander.scalesync.domain.PetSpecies,
): String = if (species == com.palixander.scalesync.domain.PetSpecies.CAT) {
    when (reason) {
        WeightReferenceUnavailableReason.MissingSex ->
            "Укажите пол питомца, чтобы показать породный ориентир."
        WeightReferenceUnavailableReason.MissingBirthDate ->
            "Укажите дату рождения, чтобы показать ориентир для возраста."
        WeightReferenceUnavailableReason.InvalidBirthDate ->
            "Исправьте дату рождения, чтобы показать ориентир для возраста."
        is WeightReferenceUnavailableReason.UnsupportedBreed,
        is WeightReferenceUnavailableReason.UnknownBreed,
        is WeightReferenceUnavailableReason.BreedSpeciesMismatch,
        -> "Для выбранной породы ориентиры сейчас недоступны."
        is WeightReferenceUnavailableReason.AgeOutOfRange,
        is WeightReferenceUnavailableReason.ReferenceDataGap,
        -> "Для выбранного возраста опубликованные данные отсутствуют."
        is WeightReferenceUnavailableReason.ProfileUnavailable ->
            "Ориентиры породы временно недоступны."
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
    val saveError: String? = null,
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
