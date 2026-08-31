package com.palixander.scalesync.domain.reference

import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.core.reference.ReferencePoint
import com.palixander.scalesync.core.reference.ReferenceProfile
import com.palixander.scalesync.core.reference.ReferenceSex
import com.palixander.scalesync.core.reference.ReferenceSpecies
import com.palixander.scalesync.core.reference.WeightReferenceSnapshot
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.ageAt
import java.time.LocalDate

enum class DogAdultWeightCategory { I, II, III, IV, V }

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

data class PetWeightReference(
    val profileId: String,
    val basis: ReferenceBasis,
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

        val breedProfile = breedId?.let { id ->
            val breed = breedCatalog.findById(id.value)
                ?: return unavailable(WeightReferenceUnavailableReason.UnknownBreed(id.value))
            val expectedSpecies = if (referenceSpecies == ReferenceSpecies.CAT) BreedSpecies.CAT else BreedSpecies.DOG
            if (breed.species != expectedSpecies) {
                return unavailable(WeightReferenceUnavailableReason.BreedSpeciesMismatch(id.value))
            }
            snapshot.profiles.singleOrNull {
                it.basis == ReferenceBasis.BREED && it.species == referenceSpecies &&
                    it.sex == referenceSex && it.breedId == id.value
            }
        }

        val profile = if (breedProfile != null) {
            if (breedProfile.breedId == DSH_BREED_ID) {
                when (intactStatus) {
                    IntactStatus.UNKNOWN -> return unavailable(WeightReferenceUnavailableReason.DshIntactStatusUnknown)
                    IntactStatus.CONFIRMED_NOT_INTACT -> return unavailable(WeightReferenceUnavailableReason.DshNotIntact)
                    IntactStatus.CONFIRMED_INTACT -> Unit
                }
            }
            breedProfile
        } else {
            if (referenceSpecies == ReferenceSpecies.CAT) {
                val reason = breedId?.let { WeightReferenceUnavailableReason.UnsupportedBreed(it.value) }
                    ?: WeightReferenceUnavailableReason.MissingBreed
                return unavailable(reason)
            }
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

        return resolveProfile(profile, birthDate, referenceDate)
    }

    private fun resolveProfile(
        profile: ReferenceProfile,
        birthDate: PartialBirthDate,
        referenceDate: LocalDate,
    ): PetWeightReferenceResolution {
        val age = try {
            birthDate.ageAt(referenceDate)
        } catch (_: IllegalArgumentException) {
            return unavailable(WeightReferenceUnavailableReason.InvalidBirthDate)
        }
        val scope = snapshot.manifest.scopes.single { it.id == profile.id }
        val supportedMinimum = scope.minimumAgeDays
        val supportedMaximum = scope.maximumAgeDays
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
        val points = buildList {
            pointAtAge(profile, supportedMaximum, age.minimumDays.toInt())?.let(::add)
            profile.points.filterTo(this) { it.ageDays.toLong() in age.minimumDays..age.maximumDays }
            if (age.maximumDays != age.minimumDays) {
                pointAtAge(profile, supportedMaximum, age.maximumDays.toInt())?.let(::add)
            }
        }
        if (points.isEmpty() || !isContinuouslyCovered(profile, age.minimumDays..age.maximumDays)) {
            return unavailable(WeightReferenceUnavailableReason.ReferenceDataGap(profile.id, age.minimumDays..age.maximumDays))
        }
        return PetWeightReferenceResolution.Available(
            PetWeightReference(
                profileId = profile.id,
                basis = profile.basis,
                ageDays = age.minimumDays..age.maximumDays,
                bounds = aggregate(points),
                approximate = birthDate.precision != BirthDatePrecision.DAY,
            ),
        )
    }

    private fun categoryFor(weightKg: Double): DogAdultWeightCategory? = when {
        weightKg < 6.5 -> DogAdultWeightCategory.I
        weightKg < 9.0 -> DogAdultWeightCategory.II
        weightKg < 15.0 -> DogAdultWeightCategory.III
        weightKg < 30.0 -> DogAdultWeightCategory.IV
        weightKg <= 40.0 -> DogAdultWeightCategory.V
        else -> null
    }

    private fun pointAtAge(
        profile: ReferenceProfile,
        supportedMaximum: Int,
        ageDays: Int,
    ): ReferencePoint? = snapshot.interpolate(profile.id, ageDays)
        ?: profile.points.last().takeIf {
            profile.species == ReferenceSpecies.DOG && ageDays in it.ageDays..supportedMaximum
        }?.copy(ageDays = ageDays)

    private fun isContinuouslyCovered(profile: ReferenceProfile, range: LongRange): Boolean {
        val relevant = profile.points.filter { it.ageDays.toLong() in range }
        val ages = buildList {
            add(range.first)
            relevant.forEach { add(it.ageDays.toLong()) }
            add(range.last)
        }.distinct().sorted()
        return ages.zipWithNext().all { (first, second) -> second - first <= snapshot.maxInterpolationGapDays }
    }

    private fun aggregate(points: List<ReferencePoint>) = ExpectedWeightBounds(
        lowerKg = points.minOf(ReferencePoint::lowerKg),
        medianLowerKg = points.minOf(ReferencePoint::medianKg),
        medianUpperKg = points.maxOf(ReferencePoint::medianKg),
        upperKg = points.maxOf(ReferencePoint::upperKg),
    )

    private fun unavailable(reason: WeightReferenceUnavailableReason) =
        PetWeightReferenceResolution.Unavailable(reason)

    private companion object {
        const val DSH_BREED_ID = "VBO:0100119"
    }
}
