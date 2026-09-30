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
import com.palixander.scalesync.ui.components.ScaleSyncSurface
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import com.palixander.scalesync.ui.theme.ScaleSyncDimensions
import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.resolve
import com.palixander.scalesync.ui.text.uiText
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
        val message: UiText,
        val showEditAction: Boolean,
    ) : PetHistoryBreedReference

    data class Available(
        val breedName: String,
        val ageLabel: UiText,
        val valueLabels: List<UiText>,
        val sourceKindLabel: UiText,
        val sexLabel: UiText,
        val partialDateDisclosure: UiText?,
        val accessibilityLabel: UiText,
        val chartValues: List<PetHistoryBreedChartValue>,
        val source: PetHistoryBreedSource,
        val details: List<PetHistoryBreedReferenceDetail>,
        val companionReferences: List<PetHistoryBreedCompanionReference> = emptyList(),
    ) : PetHistoryBreedReference {
        constructor(
            breedName: String, ageLabel: String, valueLabels: List<String>, sourceKindLabel: String,
            sexLabel: String, partialDateDisclosure: String?, accessibilityLabel: String,
            chartValues: List<PetHistoryBreedChartValue>, source: PetHistoryBreedSource,
            details: List<PetHistoryBreedReferenceDetail>, companionReferences: List<PetHistoryBreedCompanionReference> = emptyList(),
        ) : this(
            breedName, UiText.Raw(ageLabel), valueLabels.map(UiText::Raw), UiText.Raw(sourceKindLabel),
            UiText.Raw(sexLabel), partialDateDisclosure?.let(UiText::Raw), UiText.Raw(accessibilityLabel),
            chartValues, source, details, companionReferences,
        )
    }
}

data class PetHistoryBreedCompanionReference(
    val ageLabel: UiText,
    val sexLabel: UiText,
    val valueLabels: List<UiText>,
    val sourceKindLabel: UiText,
    val chartValues: List<PetHistoryBreedChartValue>,
    val source: PetHistoryBreedSource,
) {
    constructor(ageLabel: String, sexLabel: String, valueLabels: List<String>, sourceKindLabel: String, chartValues: List<PetHistoryBreedChartValue>, source: PetHistoryBreedSource) :
        this(UiText.Raw(ageLabel), UiText.Raw(sexLabel), valueLabels.map(UiText::Raw), UiText.Raw(sourceKindLabel), chartValues, source)
}

sealed interface PetHistoryBreedChartValue {
    /** Stable identity used to keep chart series from different sources separate. */
    val seriesId: String
    val statisticLabel: UiText
    val accessibilityLabel: UiText

    data class Interval(
        val lowerKg: Double,
        val upperKg: Double,
        val centerKg: Double?,
        override val statisticLabel: UiText,
        override val accessibilityLabel: UiText,
        override val seriesId: String,
    ) : PetHistoryBreedChartValue {
        constructor(lowerKg: Double, upperKg: Double, centerKg: Double?, statisticLabel: String, accessibilityLabel: String, seriesId: String = statisticLabel) :
            this(lowerKg, upperKg, centerKg, UiText.Raw(statisticLabel), UiText.Raw(accessibilityLabel), seriesId)
    }

    data class Single(
        val valueKg: Double,
        override val statisticLabel: UiText,
        override val accessibilityLabel: UiText,
        override val seriesId: String,
    ) : PetHistoryBreedChartValue {
        constructor(valueKg: Double, statisticLabel: String, accessibilityLabel: String, seriesId: String = statisticLabel) :
            this(valueKg, UiText.Raw(statisticLabel), UiText.Raw(accessibilityLabel), seriesId)
    }

    data class Boundary(
        val valueKg: Double,
        val direction: BreedWeightValue.Boundary.Direction,
        override val statisticLabel: UiText,
        override val accessibilityLabel: UiText,
        override val seriesId: String,
    ) : PetHistoryBreedChartValue {
        constructor(valueKg: Double, direction: BreedWeightValue.Boundary.Direction, statisticLabel: String, accessibilityLabel: String, seriesId: String = statisticLabel) :
            this(valueKg, direction, UiText.Raw(statisticLabel), UiText.Raw(accessibilityLabel), seriesId)
    }
}

data class PetHistoryBreedSource(
    val title: String,
    val url: String,
    val year: Int?,
    val kindLabel: UiText,
    val geography: String?,
    val method: String?,
    val pageOrTable: String?,
    val sampleLabel: UiText?,
    val limitations: List<String>,
) {
    constructor(title: String, url: String, year: Int?, kindLabel: String, geography: String?, method: String?, pageOrTable: String?, sampleLabel: String?, limitations: List<String>) :
        this(title, url, year, UiText.Raw(kindLabel), geography, method, pageOrTable, sampleLabel?.let(UiText::Raw), limitations)
}

data class PetHistoryBreedReferenceDetail(
    val ageLabel: UiText,
    val sexLabel: UiText,
    val valueLabel: UiText,
    val sourceTitle: String?,
    val documentedGap: String?,
    val youngerThanSelectedAge: Boolean,
    val sampleLabel: UiText?,
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
                        resolution.reference.ageScope.label(),
                        resolution.reference.source.kind.label(),
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
        val age = ageScope.label()
        val values = values.map { it.label(locale) }
        val kind = source.kind.label()
        val partialDisclosure = ageDisclosure.possibleAgeDays?.takeIf { ageDisclosure.partial }?.let { possible ->
            uiText(R.string.pet_breed_reference_partial_date, possible.first.ageLabel(), possible.last.ageLabel(), age)
        }
        val sourcePresentation = source.presentation(sampleSize, sampleUnit, limitations, locale)
        val companions = companionGroups.map { it.presentation(breedRussianName) }
        return PetHistoryBreedReference.Available(
            breedName = breedRussianName,
            ageLabel = age,
            valueLabels = values,
            sourceKindLabel = kind,
            sexLabel = sex.label(),
            partialDateDisclosure = partialDisclosure,
            accessibilityLabel = uiText(
                R.string.pet_breed_reference_accessibility,
                breedRussianName,
                age,
                UiText.Joined(values, ". "),
                kind,
                UiText.Joined(companions.map { companion ->
                    uiText(R.string.pet_breed_reference_accessibility_companion, companion.ageLabel, UiText.Joined(companion.valueLabels, ". "), companion.sourceKindLabel)
                }, " "),
            ),
            chartValues = this.values.map { it.chartValue(breedRussianName, age, kind, locale, source.id) } +
                companions.flatMap(PetHistoryBreedCompanionReference::chartValues),
            source = sourcePresentation,
            details = details.map { it.presentation() },
            companionReferences = companions,
        )
    }

    private fun BreedWeightReferenceGroup.presentation(breedName: String): PetHistoryBreedCompanionReference {
        val age = ageScope.label()
        val kind = source.kind.label()
        return PetHistoryBreedCompanionReference(
            ageLabel = age,
            sexLabel = sex.label(),
            valueLabels = values.map { it.label(locale) },
            sourceKindLabel = kind,
            chartValues = values.map { it.chartValue(breedName, age, kind, locale, source.id) },
            source = source.presentation(sampleSize, sampleUnit, limitations, locale),
        )
    }

    private fun BreedWeightReferenceUnavailableReason.toPresentation(): PetHistoryBreedReference = when (this) {
        BreedWeightReferenceUnavailableReason.UnsupportedSpecies -> PetHistoryBreedReference.Hidden
        BreedWeightReferenceUnavailableReason.OtherBreed -> unavailable(R.string.pet_breed_reference_other_breed, false)
        is BreedWeightReferenceUnavailableReason.RemovedOrUnsupportedBreed -> unavailable(R.string.pet_breed_reference_unavailable, false)
        BreedWeightReferenceUnavailableReason.MissingSex -> unavailable(R.string.pet_breed_reference_prompt_sex, true)
        BreedWeightReferenceUnavailableReason.InvalidBirthDate -> unavailable(R.string.pet_breed_reference_fix_birth, true)
        is BreedWeightReferenceUnavailableReason.SnapshotUnavailable -> unavailable(R.string.pet_breed_reference_temporary, false)
        BreedWeightReferenceUnavailableReason.NoApplicableValue -> unavailable(R.string.pet_breed_reference_no_applicable_value, false)
        is BreedWeightReferenceUnavailableReason.DocumentedGap -> unavailable(R.string.pet_breed_reference_age_gap, false)
    }

    private fun BreedWeightReferenceUnavailableReason.unavailable(messageId: Int, edit: Boolean) =
        PetHistoryBreedReference.Unavailable(this, uiText(messageId), edit)

    private fun BreedWeightReferenceDetail.presentation() = PetHistoryBreedReferenceDetail(
        ageLabel = ageScope.label(),
        sexLabel = sex.label(),
        valueLabel = value?.label(locale) ?: uiText(R.string.pet_breed_reference_no_numeric_value),
        sourceTitle = source?.title,
        documentedGap = documentedGap,
        youngerThanSelectedAge = youngerThanSelectedAge,
        sampleLabel = sampleSize?.let { uiText(R.string.pet_breed_reference_sample_value, it, sampleUnit.orEmpty()) },
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
    ageLabel: UiText,
    sourceKind: UiText,
    locale: Locale,
    sourceId: String,
): PetHistoryBreedChartValue {
    val valueLabel = label(locale)
    val description = uiText(R.string.pet_breed_reference_chart_accessibility, breedName, ageLabel, valueLabel, sourceKind)
    return when (this) {
        is BreedWeightValue.Interval -> PetHistoryBreedChartValue.Interval(
            lowerKg = lower,
            upperKg = upper,
            centerKg = center,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(),
            accessibilityLabel = description,
        )
        is BreedWeightValue.Single -> PetHistoryBreedChartValue.Single(
            valueKg = value,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(),
            accessibilityLabel = description,
        )
        is BreedWeightValue.Boundary -> PetHistoryBreedChartValue.Boundary(
            valueKg = value,
            direction = direction,
            seriesId = referenceId ?: "$sourceId:${statistic.name.lowercase()}",
            statisticLabel = statistic.label(),
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
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    if (reference is PetHistoryBreedReference.Hidden) return
    var expanded by remember(reference) { mutableStateOf(false) }
    var sourceErrors by remember(reference) { mutableStateOf<Set<Int>>(emptySet()) }
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    ScaleSyncSurface(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.Card)) {
        Column(verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.CompactItemSpacing)) {
            Text(stringResource(R.string.pet_breed_reference_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            when (reference) {
                PetHistoryBreedReference.Hidden -> Unit
                is PetHistoryBreedReference.Unavailable -> {
                    Text(
                        reference.message.resolve(resources),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (reference.showEditAction) TextButton(
                        onClick = onEdit,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget).testTag(PetBreedReferenceTestTags.Edit),
                    ) { Text(stringResource(R.string.pet_breed_reference_edit_profile)) }
                }
                is PetHistoryBreedReference.Available -> {
                    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = reference.accessibilityLabel.resolve(resources) }) {
                        Text(stringResource(R.string.pet_breed_reference_for_age, reference.ageLabel.resolve(resources)))
                        reference.valueLabels.forEach { value -> Text(value.resolve(resources)) }
                        Text(stringResource(R.string.pet_breed_reference_type, reference.sourceKindLabel.resolve(resources)))
                        reference.companionReferences.forEach { companion ->
                            Text(stringResource(R.string.pet_breed_reference_companion, companion.ageLabel.resolve(resources)))
                            companion.valueLabels.forEach { value -> Text(value.resolve(resources)) }
                            Text(stringResource(R.string.pet_breed_reference_type, companion.sourceKindLabel.resolve(resources)))
                        }
                        reference.partialDateDisclosure?.let { Text(it.resolve(resources), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                            Text(stringResource(if (detail.youngerThanSelectedAge) R.string.pet_breed_reference_younger_data else R.string.pet_breed_reference_additional_data, detail.ageLabel.resolve(resources)))
                            Text(stringResource(R.string.reference_labeled_value, detail.sexLabel.resolve(resources), detail.valueLabel.resolve(resources)))
                            detail.sourceTitle?.let { Text(stringResource(R.string.reference_source, it)) }
                            detail.documentedGap?.let { Text(stringResource(R.string.reference_documented_gap, it)) }
                            detail.sampleLabel?.let { Text(stringResource(R.string.reference_sample, it.resolve(resources))) }
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
    ageLabel: UiText,
    sexLabel: UiText,
    valueLabels: List<UiText>,
    sourceLauncher: ReferenceSourceLauncher,
    hasError: Boolean,
    onOpenResult: (Boolean) -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    Column(Modifier.fillMaxWidth().testTag(PetBreedReferenceTestTags.sourceGroup(index))) {
        SourceDetails(source)
        Text(stringResource(R.string.reference_age_scope, ageLabel.resolve(resources)))
        Text(stringResource(R.string.reference_sex, sexLabel.resolve(resources)))
        valueLabels.forEach { value -> Text(stringResource(R.string.reference_value, value.resolve(resources))) }
        TextButton(
            onClick = { onOpenResult(sourceLauncher.open(source.url)) },
            modifier = Modifier
                .heightIn(min = ScaleSyncDimensions.TouchTarget)
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
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    Text(stringResource(R.string.reference_organization_or_publication, source.title))
    source.year?.let { Text(stringResource(R.string.reference_year, it)) }
    Text(stringResource(R.string.reference_statement_type, source.kindLabel.resolve(resources)))
    source.sampleLabel?.let { Text(stringResource(R.string.reference_sample, it.resolve(resources))) }
    source.geography?.let { Text(stringResource(R.string.reference_geography, it)) }
    source.method?.let { Text(stringResource(R.string.reference_method, it)) }
    source.pageOrTable?.let { Text(stringResource(R.string.reference_page_or_table, it)) }
    source.limitations.forEach { Text(stringResource(R.string.reference_limitation, it)) }
}

private fun BreedWeightSourceMetadata.presentation(sampleSize: Int?, sampleUnit: String?, limitations: List<String>, locale: Locale) =
    PetHistoryBreedSource(title, url, year, kind.label(), geography, method, pageOrTable,
        sampleSize?.let { uiText(R.string.pet_breed_reference_sample_value, it, sampleUnit.orEmpty()) }, limitations)

private fun BreedWeightAgeScope.label(): UiText = when (this) {
    BreedWeightAgeScope.Adult -> uiText(R.string.pet_breed_reference_adult_dog)
    is BreedWeightAgeScope.Age -> UiText.Raw(label)
}

private fun Long.ageLabel(): UiText {
    val months = this / 30
    return if (months > 0) uiText(R.string.pet_breed_reference_age_months, months)
    else uiText(R.string.pet_breed_reference_age_days, this)
}

private fun BreedWeightValue.label(locale: Locale): UiText {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    val statisticLabel = statistic.label()
    return when (this) {
        is BreedWeightValue.Interval -> uiText(R.string.pet_breed_reference_value_interval, statisticLabel, number.format(lower), number.format(upper), center?.let(number::format).orEmpty())
        is BreedWeightValue.Single -> uiText(R.string.pet_breed_reference_value_single, statisticLabel, number.format(value), spread?.let(number::format).orEmpty())
        is BreedWeightValue.Boundary -> uiText(R.string.pet_breed_reference_value_boundary, statisticLabel, number.format(value))
    }
}

private fun BreedReferenceStatisticKind.label(): UiText = uiText(when (this) {
    BreedReferenceStatisticKind.RANGE -> R.string.pet_breed_stat_range
    BreedReferenceStatisticKind.QUANTILES -> R.string.pet_breed_stat_quantiles
    BreedReferenceStatisticKind.MEAN -> R.string.pet_breed_stat_mean
    BreedReferenceStatisticKind.MEDIAN -> R.string.pet_breed_stat_median
    BreedReferenceStatisticKind.APPROXIMATE_AVERAGE -> R.string.pet_breed_stat_approximate_mean
    BreedReferenceStatisticKind.APPROXIMATE_RANGE -> R.string.pet_breed_stat_approximate_range
    BreedReferenceStatisticKind.MEAN_SD -> R.string.pet_breed_stat_mean_sd
    BreedReferenceStatisticKind.IDEAL -> R.string.pet_breed_stat_ideal
    BreedReferenceStatisticKind.IDEAL_RANGE -> R.string.pet_breed_stat_ideal_range
    BreedReferenceStatisticKind.STANDARD_POINT -> R.string.pet_breed_stat_standard
    BreedReferenceStatisticKind.MINIMUM -> R.string.pet_breed_stat_minimum
    BreedReferenceStatisticKind.MAXIMUM -> R.string.pet_breed_stat_maximum
    BreedReferenceStatisticKind.DOCUMENTED_GAP -> R.string.pet_breed_stat_gap
})

private fun BreedReferenceSourceKind.label(): UiText = uiText(when (this) {
    BreedReferenceSourceKind.INTERNATIONAL_STANDARD -> R.string.pet_breed_source_international
    BreedReferenceSourceKind.NATIONAL_STANDARD -> R.string.pet_breed_source_national
    BreedReferenceSourceKind.BREED_CLUB -> R.string.pet_breed_source_club
    BreedReferenceSourceKind.PROFESSIONAL_REFERENCE -> R.string.pet_breed_source_professional
    BreedReferenceSourceKind.OBSERVATIONAL -> R.string.pet_breed_source_observational
    BreedReferenceSourceKind.MODELLED -> R.string.pet_breed_source_modelled
})

private fun BreedReferenceSex.label(): UiText = uiText(when (this) {
    BreedReferenceSex.MALE -> R.string.pet_breed_sex_male
    BreedReferenceSex.FEMALE -> R.string.pet_breed_sex_female
    BreedReferenceSex.COMBINED -> R.string.pet_breed_sex_combined
})
