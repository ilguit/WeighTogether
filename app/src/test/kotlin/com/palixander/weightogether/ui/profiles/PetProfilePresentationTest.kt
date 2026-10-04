package com.palixander.weightogether.ui.profiles

import com.palixander.weightogether.PetBreedCatalog
import com.palixander.weightogether.R
import com.palixander.weightogether.domain.BreedId
import com.palixander.weightogether.domain.PartialBirthDate
import com.palixander.weightogether.domain.Pet
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.ui.text.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetProfilePresentationTest {
    @Test
    fun heightSummaryPreservesPrecisionAndUsesTheRequestedLocale() {
        val original = pet().copy(heightCm = 25.125)
        for ((locale, expected) in listOf(java.util.Locale.US to "25.125", java.util.Locale.GERMANY to "25,125")) {
            val item = petProfileSummary(original, catalog, locale = locale).items.single()
            assertEquals(UiText.Resource(R.string.pet_editor_height), item.label)
            assertEquals(UiText.Raw(expected), item.value)
        }
        assertTrue(petProfileSummary(original.copy(heightCm = null), catalog).isEmpty)
    }

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
            ),
            summary.items.map { (it.label as UiText.Resource).id },
        )
        assertEquals(UiText.Resource(R.string.pet_profile_sex_female), summary.items[0].value)
        assertEquals(UiText.Raw("Лабрадор-ретривер"), summary.items[1].value)
        assertEquals(UiText.Raw("29.02.2020"), summary.items[2].value)
        assertEquals(UiText.Resource(R.string.pet_profile_age_exact, listOf(5L, UiText.Plural(R.plurals.pet_profile_age_years, 5))), summary.items[3].value)
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
        assertEquals(
            UiText.Resource(R.string.pet_breed_unavailable, listOf("retired:cat:very-long-id")),
            summary.items.single().value,
        )
    }

    @Test
    fun categorySummaryAppearsOnlyForDogsWithoutNamedBreeds() {
        for (id in listOf(null, "scalesync:dog:breed-unknown", "scalesync:dog:mixed-breed", "VBO:0200800", "external:dog:rare")) {
            for (species in listOf(PetSpecies.DOG, PetSpecies.CAT)) {
                val summary = petProfileSummary(pet(species = species, breedId = id?.let(::BreedId), category = DogAdultWeightCategory.III.takeIf { species == PetSpecies.DOG }), catalog)
                val shown = summary.items.any { it.label == UiText.Resource(R.string.pet_profile_weight_category) }
                assertEquals(species == PetSpecies.DOG && (id == null || id.startsWith("scalesync:dog:")), shown)
            }
        }
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
