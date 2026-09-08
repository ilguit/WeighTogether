package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.dogAdultWeightCategoryLabel
import com.palixander.scalesync.isDogAdultWeightCategoryApplicable
import com.palixander.scalesync.partialBirthDateLabel
import com.palixander.scalesync.petBreedLabel
import com.palixander.scalesync.petSexLabel
import com.palixander.scalesync.domain.Pet

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
): PetProfileSummary {
    val resolvedBreed = pet.breedId?.let { breedCatalog.resolve(it, pet.species) }
    return PetProfileSummary(
        buildList {
            pet.sex?.let { add(PetProfileSummaryItem("Пол", petSexLabel(it))) }
            resolvedBreed?.let { add(PetProfileSummaryItem("Порода", petBreedLabel(it))) }
            pet.birthDate?.let {
                add(PetProfileSummaryItem("Дата рождения", partialBirthDateLabel(it)))
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
