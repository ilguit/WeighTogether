package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.R
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.ui.text.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetProfilePresentationTest {
    private val catalog = PetBreedCatalog()

    @Test
    fun `empty optional profile uses localized resource`() {
        val summary = petProfileSummary(pet(), catalog)
        assertTrue(summary.isEmpty)
        assertEquals(UiText.Resource(R.string.pet_profile_summary_empty), EmptyPetProfileSummary)
    }

    @Test
    fun `filled profile exposes localized labels and values`() {
        val breed = catalog.search("Лабрадор", PetSpecies.DOG).single()
        val summary = petProfileSummary(
            pet(
                species = PetSpecies.DOG,
                sex = PetSex.FEMALE,
                breedId = breed.id,
                birthDate = PartialBirthDate.Day(LocalDate.of(2020, 2, 29)),
                category = DogAdultWeightCategory.III,
            ),
            catalog,
            LocalDate.of(2026, 2, 28),
        )
        assertEquals(
            listOf(
                R.string.pet_profile_sex,
                R.string.pet_profile_breed,
                R.string.pet_profile_birth_date,
                R.string.pet_profile_age,
                R.string.pet_profile_weight_category,
            ),
            summary.items.map { (it.label as UiText.Resource).id },
        )
        assertEquals(UiText.Resource(R.string.pet_profile_sex_female), summary.items[0].value)
        assertEquals(UiText.Raw("Лабрадор-ретривер"), summary.items[1].value)
        assertEquals(UiText.Raw("29.02.2020"), summary.items[2].value)
        assertEquals(UiText.Resource(R.string.pet_profile_age_exact, listOf(5L, UiText.Plural(R.plurals.pet_profile_age_years, 5))), summary.items[3].value)
        assertEquals(UiText.Raw("III — 9–15 кг"), summary.items[4].value)
    }

    @Test
    fun `uncertain and exact ages preserve ranges with localized plurals`() {
        val uncertain = petAgeLabel(
            PartialBirthDate.Month(YearMonth.of(2024, 5)),
            LocalDate.of(2024, 6, 15),
        )
        val exact = petAgeLabel(
            PartialBirthDate.Day(LocalDate.of(2024, 6, 1)),
            LocalDate.of(2024, 6, 15),
        )
        assertEquals(
            UiText.Resource(
                R.string.pet_profile_age_range,
                listOf(0L, 1L, UiText.Plural(R.plurals.pet_profile_age_months, 1)),
            ),
            uncertain,
        )
        assertEquals(UiText.Resource(R.string.pet_profile_age_exact, listOf(2L, UiText.Plural(R.plurals.pet_profile_age_weeks, 2))), exact)
    }

    @Test
    fun `unavailable breed id stays visible as factual value`() {
        val summary = petProfileSummary(pet(breedId = BreedId("retired:cat:very-long-id")), catalog)
        assertEquals(UiText.Resource(R.string.pet_profile_breed), summary.items.single().label)
        assertEquals(UiText.Raw("Недоступна: retired:cat:very-long-id"), summary.items.single().value)
    }

    private fun pet(
        species: PetSpecies = PetSpecies.CAT,
        sex: PetSex? = null,
        breedId: BreedId? = null,
        birthDate: PartialBirthDate? = null,
        category: DogAdultWeightCategory? = null,
    ) = Pet(
        id = PetId("pet"),
        displayName = "Барсик",
        species = species,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sex = sex,
        breedId = breedId,
        birthDate = birthDate,
        dogAdultWeightCategory = category,
    )
}
