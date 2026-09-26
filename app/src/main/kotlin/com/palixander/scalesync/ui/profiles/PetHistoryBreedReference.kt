package com.palixander.scalesync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.R
import com.palixander.scalesync.core.breedreference.BreedReferenceSourceKind
import com.palixander.scalesync.core.breedreference.BreedReferenceStatisticKind
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.reference.BreedWeightAgeScope
import com.palixander.scalesync.domain.reference.BreedWeightReference
import com.palixander.scalesync.domain.reference.BreedWeightReferenceDetail
import com.palixander.scalesync.domain.reference.BreedWeightReferenceGroup
import com.palixander.scalesync.domain.reference.BreedWeightReferenceResolution
import com.palixander.scalesync.domain.reference.BreedWeightReferenceResolver
import com.palixander.scalesync.domain.reference.BreedWeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.BreedWeightSourceMetadata
import com.palixander.scalesync.domain.reference.BreedWeightValue
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.HuaweiDimensions
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

internal object PetBreedReferenceTestTags {
    const val Card = "pet-breed-reference-card"
    const val Edit = "pet-breed-reference-edit"
    const val Details = "pet-breed-reference-details"
    fun sourceGroup(index: Int) = "pet-breed-reference-source-$index"
    fun openSource(index: Int) = "pet-breed-reference-open-source-$index"
    fun sourceError(index: Int) = "pet-breed-reference-source-error-$index"
}

sealed interface PetHistoryBreedReference {
    data object Hidden : PetHistoryBreedReference

    data class Unavailable(
        val reason: BreedWeightReferenceUnavailableReason,
        val message: String,
        val showEditAction: Boolean,
    ) : PetHistoryBreedReference

    data class Available(
        val breedName: String,
        val ageLabel: String,
        val valueLabels: List<String>,
        val sourceKindLabel: String,
        val sexLabel: String,
        val partialDateDisclosure: String?,
        val accessibilityLabel: String,
        val chartValues: List<PetHistoryBreedChartValue>,
        val source: PetHistoryBreedSource,
        val details: List<PetHistoryBreedReferenceDetail>,
        val companionReferences: List<PetHistoryBreedCompanionReference> = emptyList(),
    ) : PetHistoryBreedReference
}

data class PetHistoryBreedCompanionReference(
    val ageLabel: String,
    val sexLabel: String,
    val valueLabels: List<String>,
    val sourceKindLabel: String,
    val chartValues: List<PetHistoryBreedChartValue>,
    val source: PetHistoryBreedSource,
)

sealed interface PetHistoryBreedChartValue {
    /** Stable identity used to keep chart series from different sources separate. */
    val seriesId: String
    val statisticLabel: String
    val accessibilityLabel: String

    data class Interval(
        val lowerKg: Double,
        val upperKg: Double,
        val centerKg: Double?,
        override val statisticLabel: String,
        override val accessibilityLabel: String,
        override val seriesId: String = statisticLabel,
    ) : PetHistoryBreedChartValue

    data class Single(
        val valueKg: Double,
        override val statisticLabel: String,
        override val accessibilityLabel: String,
        override val seriesId: String = statisticLabel,
    ) : PetHistoryBreedChartValue

    data class Boundary(
        val valueKg: Double,
        val direction: BreedWeightValue.Boundary.Direction,
        override val statisticLabel: String,
        override val accessibilityLabel: String,
        override val seriesId: String = statisticLabel,
    ) : PetHistoryBreedChartValue
}

data class PetHistoryBreedSource(
    val title: String,
    val url: String,
    val year: Int?,
    val kindLabel: String,
    val geography: String?,
    val method: String?,
    val pageOrTable: String?,
    val sampleLabel: String?,
    val limitations: List<String>,
)

data class PetHistoryBreedReferenceDetail(
    val ageLabel: String,
    val sexLabel: String,
    val valueLabel: String,
    val sourceTitle: String?,
    val documentedGap: String?,
    val youngerThanSelectedAge: Boolean,
    val sampleLabel: String?,
    val limitations: List<String>,
)

class PetHistoryBreedReferencePresenter(
    private val resolver: BreedWeightReferenceResolver = BreedWeightReferenceResolver(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
) {
    fun present(pet: Pet): PetHistoryBreedReference = when (
        val resolution = resolver.resolve(pet.species, pet.breedId, pet.sex, pet.birthDate, LocalDate.now(clock))
    ) {
        is BreedWeightReferenceResolution.Available -> resolution.reference.toPresentation()
        is BreedWeightReferenceResolution.Unavailable -> resolution.reason.toPresentation()
    }

    fun presentTimeline(
        pet: Pet,
        moments: List<PetHistoryBreedReferenceTimelineMoment>,
    ): List<PetHistoryBreedReferenceTimelinePoint> {
        val valuesByDate = moments.map(PetHistoryBreedReferenceTimelineMoment::date).distinct().associateWith { date ->
            when (val resolution = resolver.resolve(pet.species, pet.breedId, pet.sex, pet.birthDate, date)) {
                is BreedWeightReferenceResolution.Available -> resolution.reference.values.map {
                    it.chartValue(
                        resolution.reference.breedRussianName,
                        resolution.reference.ageScope.label(locale),
                        resolution.reference.source.kind.label(locale),
                        locale,
                        resolution.reference.source.id,
                    )
                }
                is BreedWeightReferenceResolution.Unavailable -> null
            }
        }
        return moments.sortedBy(PetHistoryBreedReferenceTimelineMoment::xEpochMillis).map { moment ->
            PetHistoryBreedReferenceTimelinePoint(moment.xEpochMillis, moment.date, valuesByDate[moment.date])
        }
    }

    private fun BreedWeightReference.toPresentation(): PetHistoryBreedReference.Available {
        val age = ageScope.label(locale)
        val values = values.map { it.label(locale) }
        val kind = source.kind.label(locale)
        val partialDisclosure = ageDisclosure.possibleAgeDays?.takeIf { ageDisclosure.partial }?.let { possible ->
            "Дата рождения указана не полностью. Возможный возраст: ${possible.first.ageLabel()}–${possible.last.ageLabel()}. " +
                "Показан ориентир ${age.prepositionForm()}."
        }
        val sourcePresentation = source.presentation(sampleSize, sampleUnit, limitations, locale)
        val companions = companionGroups.map { it.presentation(breedRussianName) }
        return PetHistoryBreedReference.Available(
            breedName = breedRussianName,
            ageLabel = age,
            valueLabels = values,
            sourceKindLabel = kind,
            sexLabel = sex.label(locale),
            partialDateDisclosure = partialDisclosure,
            accessibilityLabel = buildString {
                append("Ориентиры породы $breedRussianName. Для возраста: $age. ")
                append(values.joinToString(". "))
                append(". Тип источника: $kind.")
                sourcePresentation.method?.let { append(" Метод источника: $it.") }
                sourcePresentation.limitations.forEach { append(" Ограничение: $it.") }
                append(" Не является медицинской нормой.")
                companions.forEach { companion ->
                    append(" Дополнительный ориентир ${companion.ageLabel}: ")
                    append(companion.valueLabels.joinToString(". "))
                    append(". Тип источника: ${companion.sourceKindLabel}.")
                }
            },
            chartValues = this.values.map { it.chartValue(breedRussianName, age, kind, locale, source.id) } +
                companions.flatMap(PetHistoryBreedCompanionReference::chartValues),
            source = sourcePresentation,
            details = details.map { it.presentation() },
            companionReferences = companions,
        )
    }

    private fun BreedWeightReferenceGroup.presentation(breedName: String): PetHistoryBreedCompanionReference {
        val age = ageScope.label(locale)
        val kind = source.kind.label(locale)
        return PetHistoryBreedCompanionReference(
            ageLabel = age,
            sexLabel = sex.label(locale),
            valueLabels = values.map { it.label(locale) },
            sourceKindLabel = kind,
            chartValues = values.map { it.chartValue(breedName, age, kind, locale, source.id) },
            source = source.presentation(sampleSize, sampleUnit, limitations, locale),
        )
    }

    private fun BreedWeightReferenceUnavailableReason.toPresentation(): PetHistoryBreedReference = when (this) {
        BreedWeightReferenceUnavailableReason.UnsupportedSpecies -> PetHistoryBreedReference.Hidden
        BreedWeightReferenceUnavailableReason.OtherBreed -> unavailable("Для другой породы ориентиров пока нет.", false)
        is BreedWeightReferenceUnavailableReason.RemovedOrUnsupportedBreed -> unavailable("Для выбранной породы ориентиры сейчас недоступны.", false)
        BreedWeightReferenceUnavailableReason.MissingSex -> unavailable("Укажите пол питомца, чтобы показать ориентиры породы.", true)
        BreedWeightReferenceUnavailableReason.InvalidBirthDate -> unavailable("Исправьте дату рождения, чтобы показать ориентир для возраста.", true)
        is BreedWeightReferenceUnavailableReason.SnapshotUnavailable -> unavailable("Ориентиры породы временно недоступны.", false)
        BreedWeightReferenceUnavailableReason.NoApplicableValue -> unavailable("Для выбранной породы нет применимого ориентира веса.", false)
        is BreedWeightReferenceUnavailableReason.DocumentedGap -> unavailable("Для выбранного возраста опубликованные данные отсутствуют.", false)
    }

    private fun BreedWeightReferenceUnavailableReason.unavailable(message: String, edit: Boolean) =
        PetHistoryBreedReference.Unavailable(this, message, edit)

    private fun BreedWeightReferenceDetail.presentation() = PetHistoryBreedReferenceDetail(
        ageLabel = ageScope.label(locale),
        sexLabel = sex.label(locale),
        valueLabel = value?.label(locale) ?: "Числовое значение не опубликовано",
        sourceTitle = source?.title,
        documentedGap = documentedGap,
        youngerThanSelectedAge = youngerThanSelectedAge,
        sampleLabel = sampleSize?.let { "$it ${sampleUnit.orEmpty()}".trim() },
        limitations = limitations,
    )
}

data class PetHistoryBreedReferenceTimelinePoint(
    val xEpochMillis: Long,
    val date: LocalDate,
    /** Null means that the resolver has no applicable value and must break chart lines. */
    val values: List<PetHistoryBreedChartValue>?,
) {
    constructor(date: LocalDate, values: List<PetHistoryBreedChartValue>?) : this(
        date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        date,
        values,
    )
}

data class PetHistoryBreedReferenceTimelineMoment(
    val xEpochMillis: Long,
    val date: LocalDate,
)

private fun BreedWeightValue.chartValue(
    breedName: String,
    ageLabel: String,
    sourceKind: String,
    locale: Locale,
    sourceId: String,
): PetHistoryBreedChartValue {
    val valueLabel = label(locale)
    val description = "$breedName. Возраст источника: $ageLabel. $valueLabel. Тип источника: $sourceKind."
    return when (this) {
        is BreedWeightValue.Interval -> PetHistoryBreedChartValue.Interval(
            lowerKg = lower,
            upperKg = upper,
            centerKg = center,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(locale),
            accessibilityLabel = description,
        )
        is BreedWeightValue.Single -> PetHistoryBreedChartValue.Single(
            valueKg = value,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(locale),
            accessibilityLabel = description,
        )
        is BreedWeightValue.Boundary -> PetHistoryBreedChartValue.Boundary(
            valueKg = value,
            direction = direction,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(locale),
            accessibilityLabel = description,
        )
    }
}

@Composable
internal fun PetHistoryBreedReferenceCard(
    reference: PetHistoryBreedReference,
    onEdit: () -> Unit,
    sourceLauncher: ReferenceSourceLauncher,
) {
    if (reference is PetHistoryBreedReference.Hidden) return
    var expanded by remember(reference) { mutableStateOf(false) }
    var sourceErrors by remember(reference) { mutableStateOf<Set<Int>>(emptySet()) }
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    HuaweiSurface(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.Card)) {
        Column(verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing)) {
            Text(stringResource(R.string.pet_breed_reference_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            when (reference) {
                PetHistoryBreedReference.Hidden -> Unit
                is PetHistoryBreedReference.Unavailable -> {
                    Text(
                        reference.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (reference.showEditAction) TextButton(
                        onClick = onEdit,
                        modifier = Modifier.heightIn(min = HuaweiDimensions.TouchTarget).testTag(PetBreedReferenceTestTags.Edit),
                    ) { Text(stringResource(R.string.pet_breed_reference_edit_profile)) }
                }
                is PetHistoryBreedReference.Available -> {
                    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = reference.accessibilityLabel }) {
                        Text(stringResource(R.string.pet_breed_reference_for_age, reference.ageLabel))
                        reference.valueLabels.forEach { value -> Text(value) }
                        Text(stringResource(R.string.pet_breed_reference_type, reference.sourceKindLabel))
                        reference.companionReferences.forEach { companion ->
                            Text(stringResource(R.string.pet_breed_reference_companion, companion.ageLabel))
                            companion.valueLabels.forEach { value -> Text(value) }
                            Text(stringResource(R.string.pet_breed_reference_type, companion.sourceKindLabel))
                        }
                        reference.partialDateDisclosure?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.semantics {
                            stateDescription = if (expanded) expandedState else collapsedState
                        },
                    ) {
                        Text(stringResource(R.string.reference_source_and_limits, if (expanded) "▴" else "▾"))
                    }
                    if (expanded) Column(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.Details)) {
                        ReferenceSourceGroup(
                            index = 0,
                            source = reference.source,
                            ageLabel = reference.ageLabel,
                            sexLabel = reference.sexLabel,
                            valueLabels = reference.valueLabels,
                            sourceLauncher = sourceLauncher,
                            hasError = 0 in sourceErrors,
                            onOpenResult = { success -> sourceErrors = sourceErrors.update(0, !success) },
                        )
                        reference.companionReferences.forEachIndexed { companionIndex, companion ->
                            val sourceIndex = companionIndex + 1
                            ReferenceSourceGroup(
                                index = sourceIndex,
                                source = companion.source,
                                ageLabel = companion.ageLabel,
                                sexLabel = companion.sexLabel,
                                valueLabels = companion.valueLabels,
                                sourceLauncher = sourceLauncher,
                                hasError = sourceIndex in sourceErrors,
                                onOpenResult = { success -> sourceErrors = sourceErrors.update(sourceIndex, !success) },
                            )
                        }
                        reference.details.forEach { detail ->
                            Text(stringResource(if (detail.youngerThanSelectedAge) R.string.pet_breed_reference_younger_data else R.string.pet_breed_reference_additional_data, detail.ageLabel))
                            Text(stringResource(R.string.reference_labeled_value, detail.sexLabel, detail.valueLabel))
                            detail.sourceTitle?.let { Text(stringResource(R.string.reference_source, it)) }
                            detail.documentedGap?.let { Text(stringResource(R.string.reference_documented_gap, it)) }
                            detail.sampleLabel?.let { Text(stringResource(R.string.reference_sample, it)) }
                            detail.limitations.forEach { Text(stringResource(R.string.reference_limitation, it)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceSourceGroup(
    index: Int,
    source: PetHistoryBreedSource,
    ageLabel: String,
    sexLabel: String,
    valueLabels: List<String>,
    sourceLauncher: ReferenceSourceLauncher,
    hasError: Boolean,
    onOpenResult: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.sourceGroup(index))) {
        SourceDetails(source)
        Text(stringResource(R.string.reference_age_scope, ageLabel))
        Text(stringResource(R.string.reference_sex, sexLabel))
        valueLabels.forEach { value -> Text(stringResource(R.string.reference_value, value)) }
        TextButton(
            onClick = { onOpenResult(sourceLauncher.open(source.url)) },
            modifier = Modifier
                .heightIn(min = HuaweiDimensions.TouchTarget)
                .testTag(PetBreedReferenceTestTags.openSource(index)),
        ) { Text(stringResource(R.string.reference_open_source_named, source.title)) }
        if (hasError) Text(
            stringResource(R.string.reference_open_error, source.title),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .testTag(PetBreedReferenceTestTags.sourceError(index))
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

private fun Set<Int>.update(value: Int, present: Boolean): Set<Int> =
    if (present) this + value else this - value

@Composable
private fun SourceDetails(source: PetHistoryBreedSource) {
    Text(stringResource(R.string.reference_organization_or_publication, source.title))
    source.year?.let { Text(stringResource(R.string.reference_year, it)) }
    Text(stringResource(R.string.reference_statement_type, source.kindLabel))
    source.sampleLabel?.let { Text(stringResource(R.string.reference_sample, it)) }
    source.geography?.let { Text(stringResource(R.string.reference_geography, it)) }
    source.method?.let { Text(stringResource(R.string.reference_method, it)) }
    source.pageOrTable?.let { Text(stringResource(R.string.reference_page_or_table, it)) }
    source.limitations.forEach { Text(stringResource(R.string.reference_limitation, it)) }
}

private fun BreedWeightSourceMetadata.presentation(sampleSize: Int?, sampleUnit: String?, limitations: List<String>, locale: Locale) =
    PetHistoryBreedSource(title, url, year, kind.label(locale), geography, method, pageOrTable,
        sampleSize?.let { "$it ${sampleUnit.orEmpty()}".trim() }, limitations)

private fun BreedWeightAgeScope.label(locale: Locale) = when (this) {
    BreedWeightAgeScope.Adult -> if (locale.language == "ru") "взрослой собаки" else "adult dog"
    is BreedWeightAgeScope.Age -> label
}

private fun String.prepositionForm() = if (startsWith("для ")) this else "для $this"

private fun Long.ageLabel(): String {
    val months = this / 30
    return if (months > 0) "$months мес." else "$this дн."
}

private fun BreedWeightValue.label(locale: Locale): String {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    val statisticLabel = statistic.label(locale)
    return when (this) {
        is BreedWeightValue.Interval -> "$statisticLabel: ${number.format(lower)}–${number.format(upper)} кг" +
            (center?.let { "; центр: ${number.format(it)} кг" } ?: "")
        is BreedWeightValue.Single -> "$statisticLabel: ${number.format(value)} кг" +
            (spread?.let { " ± ${number.format(it)} кг" } ?: "")
        is BreedWeightValue.Boundary -> "$statisticLabel: ${number.format(value)} кг"
    }
}

private fun BreedReferenceStatisticKind.label(locale: Locale) = when (this) {
    BreedReferenceStatisticKind.RANGE -> localized(locale, "Диапазон", "Range")
    BreedReferenceStatisticKind.QUANTILES -> localized(locale, "Квантили", "Quantiles")
    BreedReferenceStatisticKind.MEAN -> localized(locale, "Среднее", "Mean")
    BreedReferenceStatisticKind.MEDIAN -> localized(locale, "Медиана", "Median")
    BreedReferenceStatisticKind.APPROXIMATE_AVERAGE -> localized(locale, "Приблизительное среднее", "Approximate mean")
    BreedReferenceStatisticKind.APPROXIMATE_RANGE -> localized(locale, "Приблизительный диапазон", "Approximate range")
    BreedReferenceStatisticKind.MEAN_SD -> localized(locale, "Среднее и стандартное отклонение", "Mean and standard deviation")
    BreedReferenceStatisticKind.IDEAL -> localized(locale, "Идеальный вес", "Ideal weight")
    BreedReferenceStatisticKind.IDEAL_RANGE -> localized(locale, "Идеальный диапазон веса", "Ideal weight range")
    BreedReferenceStatisticKind.STANDARD_POINT -> localized(locale, "Значение стандарта", "Standard value")
    BreedReferenceStatisticKind.MINIMUM -> localized(locale, "Минимальный вес", "Minimum weight")
    BreedReferenceStatisticKind.MAXIMUM -> localized(locale, "Максимальный вес", "Maximum weight")
    BreedReferenceStatisticKind.DOCUMENTED_GAP -> localized(locale, "Документированный пропуск", "Documented gap")
}

private fun BreedReferenceSourceKind.label(locale: Locale) = when (this) {
    BreedReferenceSourceKind.INTERNATIONAL_STANDARD -> localized(locale, "официальный международный стандарт", "official international standard")
    BreedReferenceSourceKind.NATIONAL_STANDARD -> localized(locale, "официальный национальный стандарт", "official national standard")
    BreedReferenceSourceKind.BREED_CLUB -> localized(locale, "породная организация", "breed organization")
    BreedReferenceSourceKind.PROFESSIONAL_REFERENCE -> localized(locale, "профессиональный справочник", "professional reference")
    BreedReferenceSourceKind.OBSERVATIONAL -> localized(locale, "наблюдаемая выборка", "observational sample")
    BreedReferenceSourceKind.MODELLED -> localized(locale, "модельные данные", "modelled data")
}

private fun BreedReferenceSex.label(locale: Locale) = when (this) {
    BreedReferenceSex.MALE -> localized(locale, "Самец", "Male")
    BreedReferenceSex.FEMALE -> localized(locale, "Самка", "Female")
    BreedReferenceSex.COMBINED -> localized(locale, "Для обоих полов", "Both sexes")
}

private fun localized(locale: Locale, russian: String, english: String) =
    if (locale.language == "ru") russian else english
