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
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferenceProfileMetadata
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
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
        /** Separate segments must be drawn separately; gaps must never be connected. */
        val segments: List<List<PetHistoryReferencePoint>>,
        val approximate: Boolean,
        val ageLabel: String,
        val basisLabel: String,
        val sourceLabel: String,
        val citation: String,
        val license: String,
        val constraints: List<String>,
        val accessibilityLabel: String,
    ) : PetHistoryWeightReference

    data class Unavailable(
        val reason: WeightReferenceUnavailableReason,
        val explanation: String,
    ) : PetHistoryWeightReference
}

data class PetHistoryReferencePoint(
    val date: LocalDate,
    val lowerKg: Double,
    val medianLowerKg: Double,
    val medianUpperKg: Double,
    val upperKg: Double,
)

class PetHistoryReferencePresenter(
    private val resolver: PetWeightReferenceResolver = PetWeightReferenceResolver(),
    private val snapshot: WeightReferenceSnapshot = WeightReferenceSnapshot.bundled(),
) {
    fun present(pet: Pet, range: ChartDateRange): PetHistoryWeightReference {
        val dated = generateSequence(range.startDate) { previous ->
            previous.plusDays(1).takeUnless { it.isAfter(range.endDateInclusive) }
        }.map { date -> date to resolve(pet, date) }.toList()

        val available = dated.mapNotNull { (date, result) ->
            (result as? PetWeightReferenceResolution.Available)?.reference?.let { date to it }
        }
        if (available.isEmpty()) {
            val reason = (dated.firstOrNull()?.second as? PetWeightReferenceResolution.Unavailable)?.reason
                ?: WeightReferenceUnavailableReason.ReferenceDataGap("unknown", LongRange.EMPTY)
            return PetHistoryWeightReference.Unavailable(reason, weightReferenceUnavailableExplanation(reason))
        }
        val metadata = snapshot.metadataFor(available.first().second.profileId)
            ?: return PetHistoryWeightReference.Unavailable(
                WeightReferenceUnavailableReason.ProfileUnavailable(available.first().second.profileId),
                weightReferenceUnavailableExplanation(WeightReferenceUnavailableReason.ProfileUnavailable(available.first().second.profileId)),
            )
        val segments = mutableListOf<MutableList<PetHistoryReferencePoint>>()
        var previousDate: LocalDate? = null
        var previousProfileId: String? = null
        available.forEach { (date, reference) ->
            if (previousDate?.plusDays(1) != date || previousProfileId != reference.profileId) {
                segments.add(mutableListOf())
            }
            segments.last() += PetHistoryReferencePoint(
                date,
                reference.bounds.lowerKg,
                reference.bounds.medianLowerKg,
                reference.bounds.medianUpperKg,
                reference.bounds.upperKg,
            )
            previousDate = date
            previousProfileId = reference.profileId
        }
        val approximate = available.any { it.second.approximate }
        val minAge = available.minOf { it.second.ageDays.first }
        val maxAge = available.maxOf { it.second.ageDays.last }
        return availablePresentation(metadata, segments.map(List<PetHistoryReferencePoint>::toList), approximate, minAge, maxAge)
    }

    private fun resolve(pet: Pet, date: LocalDate) = resolver.resolve(
        species = pet.species,
        sex = pet.sex,
        breedId = pet.breedId,
        birthDate = pet.birthDate,
        referenceDate = date,
        dogAdultWeight = pet.dogAdultWeightCategory?.let(DogAdultWeight::Category),
        intactStatus = IntactStatus.UNKNOWN,
    )

    private fun availablePresentation(
        metadata: ReferenceProfileMetadata,
        segments: List<List<PetHistoryReferencePoint>>,
        approximate: Boolean,
        minAge: Long,
        maxAge: Long,
    ): PetHistoryWeightReference.Available {
        val age = if (minAge == maxAge) "$minAge дн." else "$minAge–$maxAge дн."
        val ageLabel = "Возраст: ${if (approximate) "примерно " else ""}$age"
        val basisLabel = when (metadata.basis) {
            ReferenceBasis.BREED -> "Эталон по породе"
            ReferenceBasis.WEIGHT_CATEGORY -> "Эталон по весовой категории"
            ReferenceBasis.POPULATION -> "Эталон по популяции"
        }
        return PetHistoryWeightReference.Available(
            metadata.basis,
            segments,
            approximate,
            ageLabel,
            basisLabel,
            "Источник: ${metadata.source.citation}",
            metadata.source.citation,
            metadata.source.license,
            metadata.constraints,
            "$basisLabel. $ageLabel. Источник: ${metadata.source.citation}. Лицензия: ${metadata.source.license}.",
        )
    }
}

fun weightReferenceUnavailableExplanation(reason: WeightReferenceUnavailableReason): String = when (reason) {
    WeightReferenceUnavailableReason.MissingSex -> "Эталон недоступен: укажите пол питомца."
    WeightReferenceUnavailableReason.MissingBirthDate -> "Эталон недоступен: укажите дату рождения питомца."
    WeightReferenceUnavailableReason.MissingBreed -> "Эталон недоступен: укажите породу кошки."
    WeightReferenceUnavailableReason.MissingDogAdultWeight -> "Эталон недоступен: укажите ожидаемую весовую категорию взрослой собаки."
    WeightReferenceUnavailableReason.UnsupportedSpecies -> "Эталон недоступен: вид питомца не указан."
    is WeightReferenceUnavailableReason.UnknownBreed -> "Эталон недоступен: порода ${reason.breedId} не найдена."
    is WeightReferenceUnavailableReason.BreedSpeciesMismatch -> "Эталон недоступен: порода ${reason.breedId} не соответствует виду питомца."
    is WeightReferenceUnavailableReason.UnsupportedBreed -> "Эталон недоступен: для породы ${reason.breedId} нет опубликованных данных."
    WeightReferenceUnavailableReason.DshIntactStatusUnknown -> "Эталон недоступен: для домашней короткошёрстной кошки нужны подтверждённые данные о стерилизации."
    WeightReferenceUnavailableReason.DshNotIntact -> "Эталон недоступен: опубликованные данные относятся только к нестерилизованным животным."
    WeightReferenceUnavailableReason.InvalidBirthDate -> "Эталон недоступен: дата рождения позже выбранного периода."
    is WeightReferenceUnavailableReason.ProfileUnavailable -> "Эталон недоступен: профиль ${reason.profileId} не содержит воспроизводимых числовых данных."
    is WeightReferenceUnavailableReason.ReferenceDataGap -> "Эталон недоступен: в опубликованных данных профиля ${reason.profileId} есть пробел для этого возраста."
    is WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum -> "Эталон недоступен: вес ${reason.weightKg} кг выше поддерживаемого источником максимума."
    is WeightReferenceUnavailableReason.AgeOutOfRange -> "Эталон недоступен: возраст вне опубликованного диапазона ${reason.supportedMinimumDays}–${reason.supportedMaximumDays} дней."
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
)

internal fun petHistoryPresentation(
    measurements: List<PetMeasurement>,
    range: ChartDateRange,
    zoneId: ZoneId,
    locale: Locale,
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
        .filter { !it.measuredAt.isBefore(start) && it.measuredAt.isBefore(endExclusive) }
        .sortedWith(compareByDescending<PetMeasurement> { it.measuredAt }.thenByDescending { it.id })
        .toList()
    val rows = ordered.map { measurement ->
        PetHistoryMeasurementUi(
            id = measurement.id,
            measuredAtEpochSecond = measurement.measuredAt.epochSecond,
            measuredAtText = formatMeasurementDateTime(measurement.measuredAt, zoneId, locale),
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
