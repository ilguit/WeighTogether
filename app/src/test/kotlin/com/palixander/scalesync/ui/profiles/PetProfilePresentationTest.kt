package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.PetBreedCatalog
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetProfilePresentationTest {
    private val catalog = PetBreedCatalog()

    @Test
    fun `empty optional profile has an explicit message`() {
        val summary = petProfileSummary(pet(), catalog)

        assertTrue(summary.isEmpty)
        assertEquals(EmptyPetProfileSummary, summary.contentDescription)
    }

    @Test
    fun `filled profile uses localized breed and applicable dog category range`() {
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
        )

        assertEquals(
            listOf(
                PetProfileSummaryItem("Пол", "Самка"),
                PetProfileSummaryItem("Порода", "Лабрадор-ретривер"),
                PetProfileSummaryItem("Дата рождения", "29.02.2020 (день)"),
                PetProfileSummaryItem("Весовая категория", "III — 9–15 кг"),
            ),
            summary.items,
        )
        assertEquals(
            "Пол: Самка. Порода: Лабрадор-ретривер. Дата рождения: 29.02.2020 (день). " +
                "Весовая категория: III — 9–15 кг",
            summary.contentDescription,
        )
    }

    @Test
    fun `birth date presentation preserves every visible precision`() {
        val cases = listOf(
            PartialBirthDate.Year(Year.of(2020)) to "2020 (год)",
            PartialBirthDate.Month(YearMonth.of(2020, 2)) to "02.2020 (месяц)",
            PartialBirthDate.Day(LocalDate.of(2020, 2, 29)) to "29.02.2020 (день)",
        )

        cases.forEach { (birthDate, expected) ->
            assertEquals(
                expected,
                petProfileSummary(pet(birthDate = birthDate), catalog)
                    .items.single().value,
            )
        }
    }

    @Test
    fun `cat breed row is hidden`() {
        val summary = petProfileSummary(
            pet(breedId = BreedId("retired:cat:very-long-id")),
            catalog,
        )

        assertTrue(summary.items.isEmpty())
    }

    @Test
    fun `saved category is shown for a dog with a specific breed`() {
        val breed = catalog.search("Бигль", PetSpecies.DOG).single()
        val summary = petProfileSummary(
            pet(
                species = PetSpecies.DOG,
                breedId = breed.id,
                category = DogAdultWeightCategory.II,
            ),
            catalog,
        )

        assertEquals(
            listOf(
                PetProfileSummaryItem("Порода", "Бигль"),
                PetProfileSummaryItem("Весовая категория", "II — 6,5–9 кг"),
            ),
            summary.items,
        )
        assertEquals(
            "Порода: Бигль. Весовая категория: II — 6,5–9 кг",
            summary.contentDescription,
        )
    }

    @Test
    fun `saved category and unavailable dog breed id stay visible`() {
        val unavailableBreedId = BreedId("retired:dog:very-long-id")
        val summary = petProfileSummary(
            pet(
                species = PetSpecies.DOG,
                breedId = unavailableBreedId,
                category = DogAdultWeightCategory.IV,
            ),
            catalog,
        )

        assertEquals(
            listOf(
                PetProfileSummaryItem(
                    "Порода",
                    "Недоступна: retired:dog:very-long-id",
                ),
                PetProfileSummaryItem("Весовая категория", "IV — 15–30 кг"),
            ),
            summary.items,
        )
        assertEquals(
            "Порода: Недоступна: retired:dog:very-long-id. Весовая категория: IV — 15–30 кг",
            summary.contentDescription,
        )
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
