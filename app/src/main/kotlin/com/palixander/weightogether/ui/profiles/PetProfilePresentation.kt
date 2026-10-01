package com.palixander.weightogether.ui.profiles

import com.palixander.weightogether.PetBreedCatalog
import com.palixander.weightogether.dogAdultWeightCategoryLabel
import com.palixander.weightogether.isDogAdultWeightCategoryApplicable
import com.palixander.weightogether.formatPartialBirthDate
import com.palixander.weightogether.petBreedLabel
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.ageAt
import com.palixander.weightogether.R
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.pluralUiText
import com.palixander.weightogether.ui.text.uiText
import java.time.LocalDate

val EmptyPetProfileSummary: UiText = uiText(R.string.pet_profile_summary_empty)

data class PetProfileSummaryItem(
    val label: UiText,
    val value: UiText,
)

data class PetProfileSummary(
    val items: List<PetProfileSummaryItem>,
) {
    val isEmpty: Boolean
        get() = items.isEmpty()

}

fun petProfileSummary(
    pet: Pet,
    breedCatalog: PetBreedCatalog,
    referenceDate: LocalDate = LocalDate.now(),
): PetProfileSummary {
    val resolvedBreed = pet.breedId?.let { breedCatalog.resolve(it, pet.species) }
    return PetProfileSummary(
        buildList {
            pet.sex?.let {
                add(PetProfileSummaryItem(uiText(R.string.pet_profile_sex), uiText(if (it == com.palixander.weightogether.domain.PetSex.MALE) R.string.pet_profile_sex_male else R.string.pet_profile_sex_female)))
            }
            resolvedBreed?.let { add(PetProfileSummaryItem(uiText(R.string.pet_profile_breed), petBreedLabel(it))) }
            pet.birthDate?.let {
                add(PetProfileSummaryItem(uiText(R.string.pet_profile_birth_date), UiText.Raw(formatPartialBirthDate(it))))
                add(PetProfileSummaryItem(uiText(R.string.pet_profile_age), petAgeLabel(it, referenceDate)))
            }
            pet.dogAdultWeightCategory
                ?.takeIf { isDogAdultWeightCategoryApplicable(pet.species, resolvedBreed) }
                ?.let {
                    add(
                        PetProfileSummaryItem(
                            uiText(R.string.pet_profile_weight_category),
                            dogAdultWeightCategoryLabel(it),
                        ),
                    )
                }
        },
    )
}

internal fun petAgeLabel(
    birthDate: PartialBirthDate,
    referenceDate: LocalDate,
): UiText {
    val age = birthDate.ageAt(referenceDate)
    val (minimum, maximum, unit) = when (birthDate) {
        is PartialBirthDate.Year -> Triple(age.minimumYears, age.maximumYears, AgeUnit.YEAR)
        is PartialBirthDate.Month -> Triple(age.minimumMonths, age.maximumMonths, AgeUnit.MONTH)
        is PartialBirthDate.Day -> when {
            age.maximumDays < 14 -> Triple(age.minimumDays, age.maximumDays, AgeUnit.DAY)
            age.maximumMonths < 3 -> Triple(age.minimumWeeks, age.maximumWeeks, AgeUnit.WEEK)
            age.maximumMonths < 24 -> Triple(age.minimumMonths, age.maximumMonths, AgeUnit.MONTH)
            else -> Triple(age.minimumYears, age.maximumYears, AgeUnit.YEAR)
        }
    }
    val plural = unit.plural
    val unitText = pluralUiText(plural, maximum.toInt())
    return if (minimum == maximum) uiText(R.string.pet_profile_age_exact, minimum, unitText)
    else uiText(R.string.pet_profile_age_range, minimum, maximum, unitText)
}

private enum class AgeUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    val plural: Int
        get() = when (this) {
            DAY -> R.plurals.pet_profile_age_days
            WEEK -> R.plurals.pet_profile_age_weeks
            MONTH -> R.plurals.pet_profile_age_months
            YEAR -> R.plurals.pet_profile_age_years
        }
}
