package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.breedreference.BreedReferenceBreed
import com.palixander.scalesync.core.breedreference.BreedReferenceSex
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshot
import com.palixander.scalesync.core.breedreference.BreedReferenceSnapshotLoadResult
import com.palixander.scalesync.core.breedreference.BreedReferenceSource
import com.palixander.scalesync.core.breedreference.BreedReferenceSourceKind
import com.palixander.scalesync.core.breedreference.BreedReferenceStatisticKind
import com.palixander.scalesync.core.breedreference.BreedReferenceValue
import com.palixander.scalesync.core.breedreference.BreedReferenceMeasure
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.ageAt
import java.time.LocalDate

sealed interface BreedWeightValue {
    val statistic: BreedReferenceStatisticKind
    val unit: String
    val referenceId: String?

    data class Interval(
        override val statistic: BreedReferenceStatisticKind,
        override val unit: String,
        val lower: Double,
        val upper: Double,
        val center: Double? = null,
        override val referenceId: String? = null,
    ) : BreedWeightValue

    data class Single(
        override val statistic: BreedReferenceStatisticKind,
        override val unit: String,
        val value: Double,
        val spread: Double? = null,
        override val referenceId: String? = null,
    ) : BreedWeightValue

    data class Boundary(
        override val statistic: BreedReferenceStatisticKind,
        override val unit: String,
        val value: Double,
        val direction: Direction,
        override val referenceId: String? = null,
    ) : BreedWeightValue {
        enum class Direction { LOWER, UPPER }
    }
}

sealed interface BreedWeightAgeScope {
    data object Adult : BreedWeightAgeScope
    data class Age(val minimumDays: Int, val maximumDays: Int?, val label: String) : BreedWeightAgeScope
}

data class BreedWeightSourceMetadata(
    val id: String,
    val title: String,
    val url: String,
    val year: Int?,
    val kind: BreedReferenceSourceKind,
    val geography: String?,
    val method: String?,
    val pageOrTable: String?,
)

data class BreedWeightReferenceDetail(
    val id: String,
    val sex: BreedReferenceSex,
    val ageScope: BreedWeightAgeScope,
    val value: BreedWeightValue?,
    val documentedGap: String?,
    val source: BreedWeightSourceMetadata?,
    val sampleSize: Int?,
    val sampleUnit: String?,
    val limitations: List<String>,
    val youngerThanSelectedAge: Boolean,
)

data class BreedWeightAgeDisclosure(
    val selectedAgeDays: Long?,
    val possibleAgeDays: LongRange?,
    val partial: Boolean,
    val usedAdultFallback: Boolean,
)

data class BreedWeightReference(
    val breedId: String,
    val breedRussianName: String,
    val breedEnglishName: String,
    val sex: BreedReferenceSex,
    val ageScope: BreedWeightAgeScope,
    val values: List<BreedWeightValue>,
    val source: BreedWeightSourceMetadata,
    val sampleSize: Int?,
    val sampleUnit: String?,
    val limitations: List<String>,
    val ageDisclosure: BreedWeightAgeDisclosure,
    val details: List<BreedWeightReferenceDetail>,
    val companionGroups: List<BreedWeightReferenceGroup> = emptyList(),
)

data class BreedWeightReferenceGroup(
    val sex: BreedReferenceSex,
    val ageScope: BreedWeightAgeScope,
    val values: List<BreedWeightValue>,
    val source: BreedWeightSourceMetadata,
    val sampleSize: Int?,
    val sampleUnit: String?,
    val limitations: List<String>,
)

sealed interface BreedWeightReferenceUnavailableReason {
    data object UnsupportedSpecies : BreedWeightReferenceUnavailableReason
    data object OtherBreed : BreedWeightReferenceUnavailableReason
    data class RemovedOrUnsupportedBreed(val breedId: String) : BreedWeightReferenceUnavailableReason
    data object MissingSex : BreedWeightReferenceUnavailableReason
    data object InvalidBirthDate : BreedWeightReferenceUnavailableReason
    data class SnapshotUnavailable(val detail: String) : BreedWeightReferenceUnavailableReason
    data object NoApplicableValue : BreedWeightReferenceUnavailableReason
    data class DocumentedGap(val description: String) : BreedWeightReferenceUnavailableReason
}

sealed interface BreedWeightReferenceResolution {
    data class Available(val reference: BreedWeightReference) : BreedWeightReferenceResolution
    data class Unavailable(val reason: BreedWeightReferenceUnavailableReason) : BreedWeightReferenceResolution
}

class BreedWeightReferenceResolver(
    private val snapshotResult: BreedReferenceSnapshotLoadResult = BreedReferenceSnapshot.bundledOrUnavailable(),
) {
    fun resolve(
        species: PetSpecies,
        breedId: BreedId?,
        sex: PetSex?,
        birthDate: PartialBirthDate?,
        referenceDate: LocalDate,
    ): BreedWeightReferenceResolution {
        if (species != PetSpecies.DOG) return unavailable(BreedWeightReferenceUnavailableReason.UnsupportedSpecies)
        if (breedId == null) return unavailable(BreedWeightReferenceUnavailableReason.OtherBreed)
        if (sex == null) return unavailable(BreedWeightReferenceUnavailableReason.MissingSex)
        val snapshot = when (val result = snapshotResult) {
            is BreedReferenceSnapshotLoadResult.Available -> result.snapshot
            is BreedReferenceSnapshotLoadResult.Unavailable -> return unavailable(
                BreedWeightReferenceUnavailableReason.SnapshotUnavailable(result.reason),
            )
        }
        val breed = snapshot.breed(breedId.value)
            ?: return unavailable(BreedWeightReferenceUnavailableReason.RemovedOrUnsupportedBreed(breedId.value))
        val age = if (birthDate == null) {
            BreedWeightAgeDisclosure(null, null, partial = false, usedAdultFallback = false)
        } else {
            val interval = try {
                birthDate.ageAt(referenceDate)
            } catch (_: IllegalArgumentException) {
                return unavailable(BreedWeightReferenceUnavailableReason.InvalidBirthDate)
            }
            BreedWeightAgeDisclosure(
                selectedAgeDays = interval.maximumDays,
                possibleAgeDays = interval.minimumDays..interval.maximumDays,
                partial = birthDate.precision != BirthDatePrecision.DAY,
                usedAdultFallback = false,
            )
        }
        val eligible = breed.values.filter {
            it.measure == BreedReferenceMeasure.WEIGHT && it.activeForProduct
        }
        val selectedSex = sex.toReferenceSex()
        val sexValues = eligible.filter { it.sex == selectedSex || it.sex == BreedReferenceSex.COMBINED }
        if (sexValues.isEmpty()) return unavailable(BreedWeightReferenceUnavailableReason.NoApplicableValue)

        val selectable = sexValues.filter { it.statistic != BreedReferenceStatisticKind.DOCUMENTED_GAP }
        val selectedGroup = selectGroup(selectable, selectedSex, age.selectedAgeDays, snapshot)
            ?: return unavailable(
                selectApplicableGap(sexValues, selectedSex, age.selectedAgeDays, snapshot)?.gap
                    ?.let(BreedWeightReferenceUnavailableReason::DocumentedGap)
                    ?: BreedWeightReferenceUnavailableReason.NoApplicableValue,
            )
        val records = selectedGroup.records
        val numeric = records.mapNotNull(::toWeightValue)
        if (numeric.isEmpty()) {
            val gap = records.firstNotNullOfOrNull(BreedReferenceValue::gap)
            return unavailable(
                gap?.let(BreedWeightReferenceUnavailableReason::DocumentedGap)
                    ?: BreedWeightReferenceUnavailableReason.NoApplicableValue,
            )
        }
        val source = selectedGroup.source ?: return unavailable(BreedWeightReferenceUnavailableReason.NoApplicableValue)
        val actualDisclosure = age.copy(usedAdultFallback = age.selectedAgeDays != null && selectedGroup.adult)
        val companionGroups = selectAdultCompanion(
            values = selectable,
            selectedSex = selectedSex,
            selectedAgeDays = age.selectedAgeDays,
            primary = selectedGroup,
            snapshot = snapshot,
        ).mapNotNull { it.toReferenceGroup() }
        val chosenIds = (records + companionGroups.flatMap { group ->
            selectable.filter { value ->
                value.adult && value.sex == group.sex && value.sourceId == group.source.id
            }
        }).mapTo(mutableSetOf(), BreedReferenceValue::id)
        return BreedWeightReferenceResolution.Available(
            BreedWeightReference(
                breedId = breed.breedId,
                breedRussianName = breed.russianName,
                breedEnglishName = breed.englishName,
                sex = selectedGroup.sex,
                ageScope = selectedGroup.scope,
                values = numeric,
                source = source.toMetadata(),
                sampleSize = records.firstNotNullOfOrNull(BreedReferenceValue::sampleSize),
                sampleUnit = records.firstNotNullOfOrNull(BreedReferenceValue::sampleUnit),
                limitations = records.flatMap(BreedReferenceValue::limitations).distinct(),
                ageDisclosure = actualDisclosure,
                details = breed.values
                    .filter {
                        it.measure == BreedReferenceMeasure.WEIGHT &&
                            it.id !in chosenIds &&
                            (it.activeForProduct || it.isInactiveNumericProvenance() || it.isArchivedShibaNscaAverage(breed.breedId))
                    }
                    .map { it.toDetail(snapshot, age.selectedAgeDays) },
                companionGroups = companionGroups,
            ),
        )
    }

    private fun BreedReferenceValue.isInactiveNumericProvenance(): Boolean =
        !activeForProduct && statistic != BreedReferenceStatisticKind.DOCUMENTED_GAP &&
            (lower != null || center != null || upper != null)

    private fun selectGroup(
        values: List<BreedReferenceValue>,
        selectedSex: BreedReferenceSex,
        selectedAgeDays: Long?,
        snapshot: BreedReferenceSnapshot,
    ): CandidateGroup? {
        val groups = values.groupBy { GroupKey(it.sex, it.adult, it.ageMinimumDays, it.ageMaximumDays, it.ageLabel, it.sourceId) }
            .map { (key, records) -> CandidateGroup(key, records, key.sourceId?.let { id -> snapshot.manifest.sources.firstOrNull { it.id == id } }) }
        val candidates = if (selectedAgeDays == null) {
            groups.filter(CandidateGroup::adult)
        } else {
            val containing = groups.filter { it.contains(selectedAgeDays) }
            // Published age observations apply only to their declared interval. In particular,
            // point observations must not be stretched to the next observation or interpolated.
            // In the absence of per-record maturity metadata, one year is the conservative
            // boundary at which an explicitly adult reference becomes applicable.
            if (containing.isNotEmpty()) containing
            else if (selectedAgeDays >= ADULT_REFERENCE_MINIMUM_DAYS) groups.filter(CandidateGroup::adult)
            else emptyList()
        }
        return candidates.minWithOrNull(
            compareBy<CandidateGroup> { if (it.sex == selectedSex) 0 else 1 }
                .thenBy { sourcePriority(it.source?.kind) }
                .thenBy { it.width }
                .thenBy { it.source?.id.orEmpty() },
        )
    }

    private fun selectApplicableGap(
        values: List<BreedReferenceValue>,
        selectedSex: BreedReferenceSex,
        selectedAgeDays: Long?,
        snapshot: BreedReferenceSnapshot,
    ): BreedReferenceValue? {
        val gaps = values.filter { it.statistic == BreedReferenceStatisticKind.DOCUMENTED_GAP }
        val applicable = if (selectedAgeDays == null) {
            gaps.filter(BreedReferenceValue::adult)
        } else {
            val ageSpecific = gaps.filter { value ->
                !value.adult &&
                    selectedAgeDays >= requireNotNull(value.ageMinimumDays) &&
                    selectedAgeDays <= (value.ageMaximumDays ?: requireNotNull(value.ageMinimumDays)).toLong()
            }
            if (ageSpecific.isNotEmpty()) ageSpecific
            else if (selectedAgeDays >= ADULT_REFERENCE_MINIMUM_DAYS) gaps.filter(BreedReferenceValue::adult)
            else emptyList()
        }
        return applicable
            .minWithOrNull(
                compareBy<BreedReferenceValue> { if (it.sex == selectedSex) 0 else 1 }
                    .thenBy { value ->
                        sourcePriority(snapshot.manifest.sources.firstOrNull { it.id == value.sourceId }?.kind)
                    }
                    .thenBy { value ->
                        if (value.adult) Int.MAX_VALUE
                        else (value.ageMaximumDays ?: value.ageMinimumDays!!) - value.ageMinimumDays!!
                    }
                    .thenBy(BreedReferenceValue::id),
            )
    }

    private fun selectAdultCompanion(
        values: List<BreedReferenceValue>,
        selectedSex: BreedReferenceSex,
        selectedAgeDays: Long?,
        primary: CandidateGroup,
        snapshot: BreedReferenceSnapshot,
    ): List<CandidateGroup> {
        val primaryIsAdultMinimum = primary.adult && primary.records.all {
            it.statistic == BreedReferenceStatisticKind.MINIMUM
        }
        if (!primaryIsAdultMinimum && (selectedAgeDays == null || selectedAgeDays < 365 || primary.adult)) return emptyList()
        return values
            .groupBy { GroupKey(it.sex, it.adult, it.ageMinimumDays, it.ageMaximumDays, it.ageLabel, it.sourceId) }
            .map { (key, records) ->
                CandidateGroup(key, records, key.sourceId?.let { id -> snapshot.manifest.sources.firstOrNull { it.id == id } })
            }
            .filter { candidate ->
                candidate.adult &&
                    candidate.key != primary.key &&
                    (candidate.sex == selectedSex || candidate.sex == BreedReferenceSex.COMBINED) &&
                    candidate.records.any {
                        it.statistic == BreedReferenceStatisticKind.RANGE ||
                            it.statistic == BreedReferenceStatisticKind.QUANTILES ||
                            it.statistic == BreedReferenceStatisticKind.APPROXIMATE_RANGE ||
                            it.statistic == BreedReferenceStatisticKind.IDEAL_RANGE
                    }
            }
            .sortedWith(
                compareBy<CandidateGroup> {
                    if (primaryIsAdultMinimum && it.sex == BreedReferenceSex.COMBINED) 0
                    else if (it.sex == selectedSex) 0 else 1
                }
                    .thenBy { sourcePriority(it.source?.kind) }
                    .thenBy { it.source?.id.orEmpty() },
            )
            .take(1)
    }

    private fun CandidateGroup.toReferenceGroup(): BreedWeightReferenceGroup? {
        val metadata = source?.toMetadata() ?: return null
        val numeric = records.mapNotNull(::toWeightValue)
        if (numeric.isEmpty()) return null
        return BreedWeightReferenceGroup(
            sex = sex,
            ageScope = scope,
            values = numeric,
            source = metadata,
            sampleSize = records.firstNotNullOfOrNull(BreedReferenceValue::sampleSize),
            sampleUnit = records.firstNotNullOfOrNull(BreedReferenceValue::sampleUnit),
            limitations = records.flatMap(BreedReferenceValue::limitations).distinct(),
        )
    }

    private data class GroupKey(
        val sex: BreedReferenceSex,
        val adult: Boolean,
        val minimumDays: Int?,
        val maximumDays: Int?,
        val ageLabel: String,
        val sourceId: String?,
    )

    private data class CandidateGroup(
        val key: GroupKey,
        val records: List<BreedReferenceValue>,
        val source: BreedReferenceSource?,
    ) {
        val sex get() = key.sex
        val adult get() = key.adult
        val minimumDays get() = key.minimumDays
        val width: Long get() = if (adult) Long.MAX_VALUE else (key.maximumDays ?: key.minimumDays!!).toLong() - key.minimumDays!!
        val scope: BreedWeightAgeScope get() = if (adult) BreedWeightAgeScope.Adult else BreedWeightAgeScope.Age(key.minimumDays!!, key.maximumDays, key.ageLabel)
        fun contains(ageDays: Long): Boolean = !adult && ageDays >= key.minimumDays!! && ageDays <= (key.maximumDays ?: key.minimumDays).toLong()
    }

    private fun BreedReferenceValue.toDetail(snapshot: BreedReferenceSnapshot, age: Long?) = BreedWeightReferenceDetail(
        id = id,
        sex = sex,
        ageScope = if (adult) BreedWeightAgeScope.Adult else BreedWeightAgeScope.Age(ageMinimumDays!!, ageMaximumDays, ageLabel),
        value = toWeightValue(this),
        documentedGap = gap,
        source = sourceId?.let { id -> snapshot.manifest.sources.firstOrNull { it.id == id } }?.toMetadata(),
        sampleSize = sampleSize,
        sampleUnit = sampleUnit,
        limitations = limitations,
        youngerThanSelectedAge = age != null && !adult && (ageMaximumDays ?: ageMinimumDays!!) < age,
    )

    private fun BreedReferenceSource.toMetadata() = BreedWeightSourceMetadata(id, title, url, year, kind, geography, method, pageOrTable)

    private fun toWeightValue(value: BreedReferenceValue): BreedWeightValue? = when (value.statistic) {
        BreedReferenceStatisticKind.RANGE,
        BreedReferenceStatisticKind.QUANTILES,
        BreedReferenceStatisticKind.APPROXIMATE_RANGE,
        BreedReferenceStatisticKind.IDEAL_RANGE,
        ->
            BreedWeightValue.Interval(
                value.statistic,
                value.unit,
                value.lower!!,
                value.upper!!,
                value.center,
                referenceId = "${value.sourceId.orEmpty()}:${value.id}",
            )
        BreedReferenceStatisticKind.MEAN,
        BreedReferenceStatisticKind.MEDIAN,
        BreedReferenceStatisticKind.APPROXIMATE_AVERAGE,
        BreedReferenceStatisticKind.IDEAL,
        BreedReferenceStatisticKind.STANDARD_POINT,
        ->
            BreedWeightValue.Single(
                value.statistic,
                value.unit,
                value.center!!,
                referenceId = "${value.sourceId.orEmpty()}:${value.id}",
            )
        BreedReferenceStatisticKind.MINIMUM -> BreedWeightValue.Single(
            value.statistic,
            value.unit,
            value.lower!!,
            referenceId = "${value.sourceId.orEmpty()}:${value.id}",
        )
        BreedReferenceStatisticKind.MAXIMUM -> BreedWeightValue.Boundary(
            value.statistic,
            value.unit,
            value.upper!!,
            BreedWeightValue.Boundary.Direction.UPPER,
            referenceId = "${value.sourceId.orEmpty()}:${value.id}",
        )
        BreedReferenceStatisticKind.MEAN_SD -> BreedWeightValue.Single(
            value.statistic,
            value.unit,
            value.center!!,
            value.spread,
            referenceId = "${value.sourceId.orEmpty()}:${value.id}",
        )
        BreedReferenceStatisticKind.DOCUMENTED_GAP -> null
    }

    private fun PetSex.toReferenceSex() = if (this == PetSex.MALE) BreedReferenceSex.MALE else BreedReferenceSex.FEMALE
    private fun BreedReferenceValue.isArchivedShibaNscaAverage(breedId: String) =
        breedId == "VBO:0201220" &&
            id in ARCHIVED_SHIBA_NSCA_WEIGHT_IDS &&
            sourceId == "shibaclub" &&
            statistic == BreedReferenceStatisticKind.APPROXIMATE_AVERAGE

    private fun unavailable(reason: BreedWeightReferenceUnavailableReason) = BreedWeightReferenceResolution.Unavailable(reason)

    private fun sourcePriority(kind: BreedReferenceSourceKind?): Int = when (kind) {
        BreedReferenceSourceKind.INTERNATIONAL_STANDARD -> 0
        BreedReferenceSourceKind.NATIONAL_STANDARD, BreedReferenceSourceKind.BREED_CLUB -> 1
        BreedReferenceSourceKind.PROFESSIONAL_REFERENCE -> 2
        BreedReferenceSourceKind.OBSERVATIONAL -> 3
        BreedReferenceSourceKind.MODELLED -> 4
        null -> 5
    }

    private companion object {
        const val ADULT_REFERENCE_MINIMUM_DAYS = 365L
        val ARCHIVED_SHIBA_NSCA_WEIGHT_IDS = setOf("shi-mw", "shi-fw")
    }
}
