package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedKind
import com.palixander.scalesync.core.breed.BreedSpecies
import com.palixander.scalesync.core.reference.ReferenceAgeAvailability
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferencePoint
import com.palixander.scalesync.core.reference.ReferenceProfile
import com.palixander.scalesync.core.reference.ReferenceSex
import com.palixander.scalesync.core.reference.ReferenceSpecies
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.AgeInterval
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.ageAt
import java.time.LocalDate

enum class DogAdultWeightCategory { I, II, III, IV, V }

enum class DogAdultWeightCategoryEvidence {
    SALT_2017_TABLE_1,
}

data class DogBreedAdultWeightCategoryMapping(
    val breedId: BreedId,
    val category: DogAdultWeightCategory,
    val evidence: DogAdultWeightCategoryEvidence,
)

/**
 * Evidence-backed mappings from named breeds to Salt et al.'s adult-weight categories.
 * Breeds absent from this table deliberately receive no automatic category.
 */
object DogBreedAdultWeightCategoryMappings {
    val entries: List<DogBreedAdultWeightCategoryMapping> = listOf(
        DogBreedAdultWeightCategoryMapping(
            breedId = BreedId("VBO:0200131"),
            category = DogAdultWeightCategory.III,
            evidence = DogAdultWeightCategoryEvidence.SALT_2017_TABLE_1,
        ),
        DogBreedAdultWeightCategoryMapping(
            breedId = BreedId("VBO:0200800"),
            category = DogAdultWeightCategory.V,
            evidence = DogAdultWeightCategoryEvidence.SALT_2017_TABLE_1,
        ),
    )

    private val byBreedId = entries.associateBy(DogBreedAdultWeightCategoryMapping::breedId)

    fun find(breedId: BreedId?): DogBreedAdultWeightCategoryMapping? = breedId?.let(byBreedId::get)
}

sealed interface DogAdultWeight {
    data class Category(val value: DogAdultWeightCategory) : DogAdultWeight

    data class ExpectedWeightKg(val value: Double) : DogAdultWeight {
        init {
            require(value.isFinite() && value > 0.0) { "Expected adult weight must be finite and positive" }
        }
    }
}

enum class IntactStatus { CONFIRMED_INTACT, CONFIRMED_NOT_INTACT, UNKNOWN }

data class ExpectedWeightBounds(
    val lowerKg: Double,
    val medianLowerKg: Double,
    val medianUpperKg: Double,
    val upperKg: Double,
)

enum class WeightReferenceProvenance {
    BREED_CURVE,
    BREED_EXACT_OBSERVATION,
    POPULATION,
    POPULATION_FALLBACK_FOR_SELECTED_BREED,
    WEIGHT_CATEGORY,
}

data class PetWeightReference(
    val profileId: String,
    /** Effective source for this resolved value; empirical points override the profile source. */
    val sourceId: String,
    val basis: ReferenceBasis,
    val provenance: WeightReferenceProvenance,
    /** The breed selected in the pet profile, including when population data is used as fallback. */
    val selectedBreedId: BreedId?,
    val ageDays: LongRange,
    val bounds: ExpectedWeightBounds,
    /** True only when birth-date precision produces an age interval; interpolation alone is exact. */
    val approximate: Boolean,
)

sealed interface WeightReferenceUnavailableReason {
    data object MissingSex : WeightReferenceUnavailableReason
    data object MissingBirthDate : WeightReferenceUnavailableReason
    data object MissingBreed : WeightReferenceUnavailableReason
    data object MissingDogAdultWeight : WeightReferenceUnavailableReason
    data object UnsupportedSpecies : WeightReferenceUnavailableReason
    data class UnknownBreed(val breedId: String) : WeightReferenceUnavailableReason
    data class BreedSpeciesMismatch(val breedId: String) : WeightReferenceUnavailableReason
    data class UnsupportedBreed(val breedId: String) : WeightReferenceUnavailableReason
    data object DshIntactStatusUnknown : WeightReferenceUnavailableReason
    data object DshNotIntact : WeightReferenceUnavailableReason
    data object InvalidBirthDate : WeightReferenceUnavailableReason
    data class ProfileUnavailable(val profileId: String) : WeightReferenceUnavailableReason
    data class ReferenceDataGap(val profileId: String, val ageDays: LongRange) : WeightReferenceUnavailableReason
    data class AdultWeightAboveSupportedMaximum(val weightKg: Double) : WeightReferenceUnavailableReason
    data class AgeOutOfRange(
        val actualMinimumDays: Long,
        val actualMaximumDays: Long,
        val supportedMinimumDays: Int,
        val supportedMaximumDays: Int,
    ) : WeightReferenceUnavailableReason
}

sealed interface PetWeightReferenceResolution {
    data class Available(val reference: PetWeightReference) : PetWeightReferenceResolution
    data class Unavailable(val reason: WeightReferenceUnavailableReason) : PetWeightReferenceResolution
}

class PetWeightReferenceResolver(
    private val snapshot: WeightReferenceSnapshot = WeightReferenceSnapshot.bundled(),
    private val breedCatalog: BreedCatalog = BreedCatalog.bundled(),
) {
    fun resolve(
        species: PetSpecies,
        sex: PetSex?,
        breedId: com.palixander.scalesync.domain.BreedId?,
        birthDate: PartialBirthDate?,
        referenceDate: LocalDate,
        dogAdultWeight: DogAdultWeight? = null,
        intactStatus: IntactStatus = IntactStatus.UNKNOWN,
    ): PetWeightReferenceResolution {
        val canonicalBreedId = breedId?.let { id ->
            if (id.value == LEGACY_CANADIAN_SPHYNX_ID) BreedId(CANONICAL_SPHYNX_ID) else id
        }
        val referenceSpecies = when (species) {
            PetSpecies.CAT -> ReferenceSpecies.CAT
            PetSpecies.DOG -> ReferenceSpecies.DOG
            PetSpecies.UNSPECIFIED -> return unavailable(WeightReferenceUnavailableReason.UnsupportedSpecies)
        }
        val referenceSex = when (sex) {
            PetSex.FEMALE -> ReferenceSex.FEMALE
            PetSex.MALE -> ReferenceSex.MALE
            null -> return unavailable(WeightReferenceUnavailableReason.MissingSex)
        }
        birthDate ?: return unavailable(WeightReferenceUnavailableReason.MissingBirthDate)
        val age = try {
            birthDate.ageAt(referenceDate)
        } catch (_: IllegalArgumentException) {
            return unavailable(WeightReferenceUnavailableReason.InvalidBirthDate)
        }

        val breedRoute = canonicalBreedId?.let { id ->
            val breed = breedCatalog.findById(id.value)
            if (breed == null && referenceSpecies == ReferenceSpecies.DOG) {
                return unavailable(WeightReferenceUnavailableReason.UnknownBreed(id.value))
            }
            val expectedSpecies = if (referenceSpecies == ReferenceSpecies.CAT) BreedSpecies.CAT else BreedSpecies.DOG
            if (breed != null && breed.species != expectedSpecies) {
                return unavailable(WeightReferenceUnavailableReason.BreedSpeciesMismatch(id.value))
            }
            when (breed?.kind) {
                BreedKind.UNKNOWN,
                BreedKind.MIXED,
                -> BreedReferenceRoute.GENERIC
                BreedKind.VBO -> BreedReferenceRoute.NAMED
                null -> return unavailable(WeightReferenceUnavailableReason.UnsupportedBreed(id.value))
            }
        } ?: BreedReferenceRoute.GENERIC

        val breedProfile = canonicalBreedId?.takeIf { breedRoute == BreedReferenceRoute.NAMED }?.let { id ->
            val matchingProfile = snapshot.profiles.singleOrNull {
                it.basis == ReferenceBasis.BREED && it.species == referenceSpecies &&
                    it.sex == referenceSex && it.breedId == id.value
            } ?: return unavailable(WeightReferenceUnavailableReason.UnsupportedBreed(id.value))
            matchingProfile.takeIf { it.supports(age) } ?: run {
                return resolveProfile(
                    matchingProfile,
                    birthDate,
                    age,
                    WeightReferenceProvenance.BREED_CURVE,
                    canonicalBreedId,
                )
            }
        }

        val profile = if (breedProfile != null) {
            breedProfile
        } else {
            if (referenceSpecies == ReferenceSpecies.CAT) {
                if (breedRoute == BreedReferenceRoute.NAMED && canonicalBreedId != null) {
                    return unavailable(WeightReferenceUnavailableReason.UnsupportedBreed(canonicalBreedId.value))
                }
                snapshot.profiles.singleOrNull {
                    it.basis == ReferenceBasis.POPULATION && it.species == ReferenceSpecies.CAT &&
                        it.sex == referenceSex && it.breedId == null
                } ?: return unavailable(
                    WeightReferenceUnavailableReason.ProfileUnavailable(
                        "cat-population-${referenceSex.name.lowercase()}",
                    ),
                )
            } else {
                val category = when (dogAdultWeight) {
                    is DogAdultWeight.Category -> dogAdultWeight.value
                    is DogAdultWeight.ExpectedWeightKg -> categoryFor(dogAdultWeight.value)
                        ?: return unavailable(
                            WeightReferenceUnavailableReason.AdultWeightAboveSupportedMaximum(dogAdultWeight.value),
                        )
                    null -> return unavailable(WeightReferenceUnavailableReason.MissingDogAdultWeight)
                }
                snapshot.profiles.singleOrNull {
                    it.basis == ReferenceBasis.WEIGHT_CATEGORY && it.species == ReferenceSpecies.DOG &&
                        it.sex == referenceSex && it.weightCategory == category.name
                } ?: return unavailable(
                    WeightReferenceUnavailableReason.ProfileUnavailable(
                        "dog-${referenceSex.name.lowercase()}-${category.name}",
                    ),
                )
            }
        }

        val provenance = when {
            profile.basis == ReferenceBasis.BREED && age.minimumDays == age.maximumDays &&
                profile.points.any { it.ageDays.toLong() == age.minimumDays && it.empirical } ->
                WeightReferenceProvenance.BREED_EXACT_OBSERVATION
            profile.basis == ReferenceBasis.BREED &&
                profile.ageAvailability == ReferenceAgeAvailability.EXACT_OBSERVATIONS ->
                WeightReferenceProvenance.BREED_EXACT_OBSERVATION
            profile.basis == ReferenceBasis.BREED -> WeightReferenceProvenance.BREED_CURVE
            profile.basis == ReferenceBasis.POPULATION && canonicalBreedId != null ->
                WeightReferenceProvenance.POPULATION_FALLBACK_FOR_SELECTED_BREED
            profile.basis == ReferenceBasis.POPULATION -> WeightReferenceProvenance.POPULATION
            else -> WeightReferenceProvenance.WEIGHT_CATEGORY
        }
        return resolveProfile(profile, birthDate, age, provenance, canonicalBreedId)
    }

    private companion object {
        const val LEGACY_CANADIAN_SPHYNX_ID = "VBO:0100061"
        const val CANONICAL_SPHYNX_ID = "VBO:0100230"
    }

    private enum class BreedReferenceRoute { GENERIC, NAMED }

    private fun resolveProfile(
        profile: ReferenceProfile,
        birthDate: PartialBirthDate,
        age: AgeInterval,
        provenance: WeightReferenceProvenance,
        selectedBreedId: BreedId?,
    ): PetWeightReferenceResolution {
        val supportedMinimum = profile.points.first().ageDays
        val supportedMaximum = snapshot.metadataFor(profile.id)?.supportedMaximumAgeDays
            ?: return unavailable(WeightReferenceUnavailableReason.ProfileUnavailable(profile.id))
        if (age.minimumDays < supportedMinimum || age.maximumDays > supportedMaximum) {
            return unavailable(
                WeightReferenceUnavailableReason.AgeOutOfRange(
                    age.minimumDays,
                    age.maximumDays,
                    supportedMinimum,
                    supportedMaximum,
                ),
            )
        }
        val endpointPoints = listOfNotNull(
            snapshot.interpolate(profile.id, age.minimumDays.toInt()),
            snapshot.interpolate(profile.id, age.maximumDays.toInt()),
        )
        val hasUnavailableAge = (age.minimumDays..age.maximumDays).any { ageDays ->
            snapshot.interpolate(profile.id, ageDays.toInt()) == null
        }
        if (hasUnavailableAge) {
            return unavailable(WeightReferenceUnavailableReason.ReferenceDataGap(profile.id, age.minimumDays..age.maximumDays))
        }
        val points = buildList {
            addAll(endpointPoints)
            profile.points.filterTo(this) { it.ageDays.toLong() in age.minimumDays..age.maximumDays }
        }
        val effectiveSourceId = if (provenance == WeightReferenceProvenance.BREED_EXACT_OBSERVATION) {
            points.firstOrNull { it.empirical && it.ageDays.toLong() in age.minimumDays..age.maximumDays }?.sourceId
                ?: profile.sourceId
        } else {
            profile.sourceId
        }
        return PetWeightReferenceResolution.Available(
            PetWeightReference(
                profileId = profile.id,
                sourceId = effectiveSourceId,
                basis = profile.basis,
                provenance = provenance,
                selectedBreedId = selectedBreedId,
                ageDays = age.minimumDays..age.maximumDays,
                bounds = aggregate(points),
                approximate = birthDate.precision != BirthDatePrecision.DAY,
            ),
        )
    }

    private fun ReferenceProfile.supports(age: AgeInterval): Boolean =
        age.minimumDays >= points.first().ageDays &&
            age.maximumDays <= (snapshot.metadataFor(id)?.supportedMaximumAgeDays ?: Int.MIN_VALUE)

    private fun categoryFor(weightKg: Double): DogAdultWeightCategory? = when {
        weightKg < 6.5 -> DogAdultWeightCategory.I
        weightKg < 9.0 -> DogAdultWeightCategory.II
        weightKg < 15.0 -> DogAdultWeightCategory.III
        weightKg < 30.0 -> DogAdultWeightCategory.IV
        weightKg <= 40.0 -> DogAdultWeightCategory.V
        else -> null
    }

    private fun aggregate(points: List<ReferencePoint>) = ExpectedWeightBounds(
        lowerKg = points.minOf(ReferencePoint::lowerKg),
        medianLowerKg = points.minOf(ReferencePoint::medianKg),
        medianUpperKg = points.maxOf(ReferencePoint::medianKg),
        upperKg = points.maxOf(ReferencePoint::upperKg),
    )

    private fun unavailable(reason: WeightReferenceUnavailableReason) =
        PetWeightReferenceResolution.Unavailable(reason)
}
