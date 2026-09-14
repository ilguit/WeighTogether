package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.dogAdultWeightCategoryLabel
import com.palixander.scalesync.isDogAdultWeightCategoryApplicable
import com.palixander.scalesync.formatPartialBirthDate
import com.palixander.scalesync.petBreedLabel
import com.palixander.scalesync.petSexLabel
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.ageAt
import java.time.LocalDate

const val EmptyPetProfileSummary = "Дополнительные данные не заполнены"

data class PetProfileSummaryItem(
    val label: String,
    val value: String,
)

data class PetProfileSummary(
    val items: List<PetProfileSummaryItem>,
) {
    val isEmpty: Boolean
        get() = items.isEmpty()

    val contentDescription: String
        get() = if (isEmpty) {
            EmptyPetProfileSummary
        } else {
            items.joinToString(separator = ". ") { item -> "${item.label}: ${item.value}" }
        }
}

fun petProfileSummary(
    pet: Pet,
    breedCatalog: PetBreedCatalog,
    referenceDate: LocalDate = LocalDate.now(),
): PetProfileSummary {
    val resolvedBreed = pet.breedId?.let { breedCatalog.resolve(it, pet.species) }
    return PetProfileSummary(
        buildList {
            pet.sex?.let { add(PetProfileSummaryItem("Пол", petSexLabel(it))) }
            resolvedBreed?.let { add(PetProfileSummaryItem("Порода", petBreedLabel(it))) }
            pet.birthDate?.let {
                add(PetProfileSummaryItem("Дата рождения", formatPartialBirthDate(it)))
                add(PetProfileSummaryItem("Возраст", petAgeLabel(it, referenceDate)))
            }
            pet.dogAdultWeightCategory
                ?.takeIf { isDogAdultWeightCategoryApplicable(pet.species, resolvedBreed) }
                ?.let {
                    add(
                        PetProfileSummaryItem(
                            "Весовая категория",
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
): String {
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
    return if (minimum == maximum) {
        "$minimum ${unit.label(minimum)}"
    } else {
        "$minimum–$maximum ${unit.label(maximum)}"
    }
}

private enum class AgeUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    fun label(value: Long): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when (this) {
            DAY -> russianUnit(mod100, mod10, "день", "дня", "дней")
            WEEK -> russianUnit(mod100, mod10, "неделя", "недели", "недель")
            MONTH -> russianUnit(mod100, mod10, "месяц", "месяца", "месяцев")
            YEAR -> russianUnit(mod100, mod10, "год", "года", "лет")
        }
    }
}

private fun russianUnit(
    mod100: Long,
    mod10: Long,
    one: String,
    few: String,
    many: String,
): String = when {
    mod100 in 11..14 -> many
    mod10 == 1L -> one
    mod10 in 2..4 -> few
    else -> many
}
