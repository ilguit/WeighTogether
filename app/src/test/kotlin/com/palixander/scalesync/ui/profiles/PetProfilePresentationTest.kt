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
            LocalDate.of(2026, 2, 28),
        )

        assertEquals(
            listOf(
                PetProfileSummaryItem("Пол", "Самка"),
                PetProfileSummaryItem("Порода", "Лабрадор-ретривер"),
                PetProfileSummaryItem("Дата рождения", "29.02.2020"),
                PetProfileSummaryItem("Возраст", "5 лет"),
                PetProfileSummaryItem("Весовая категория", "III — 9–15 кг"),
            ),
            summary.items,
        )
        assertEquals(
            "Пол: Самка. Порода: Лабрадор-ретривер. Дата рождения: 29.02.2020. Возраст: 5 лет. " +
                "Весовая категория: III — 9–15 кг",
            summary.contentDescription,
        )
    }

    @Test
    fun `birth date presentation omits precision suffix and adds truthful age`() {
        val cases = listOf(
            PartialBirthDate.Year(Year.of(2020)) to listOf(
                PetProfileSummaryItem("Дата рождения", "2020"),
                PetProfileSummaryItem("Возраст", "3–4 года"),
            ),
            PartialBirthDate.Month(YearMonth.of(2020, 2)) to listOf(
                PetProfileSummaryItem("Дата рождения", "02.2020"),
                PetProfileSummaryItem("Возраст", "51–52 месяца"),
            ),
            PartialBirthDate.Day(LocalDate.of(2020, 2, 29)) to listOf(
                PetProfileSummaryItem("Дата рождения", "29.02.2020"),
                PetProfileSummaryItem("Возраст", "4 года"),
            ),
        )

        cases.forEach { (birthDate, expected) ->
            assertEquals(
                expected,
                petProfileSummary(
                    pet(birthDate = birthDate),
                    catalog,
                    LocalDate.of(2024, 6, 15),
                ).items,
            )
        }
    }

    @Test
    fun `exact young ages use localized days weeks and months around boundaries`() {
        val today = LocalDate.of(2024, 6, 15)
        val cases = listOf(
            today.minusDays(1) to "1 день",
            today.minusDays(14) to "2 недели",
            today.minusMonths(3) to "3 месяца",
            today.minusYears(2).plusDays(1) to "23 месяца",
            today.minusYears(2) to "2 года",
        )

        cases.forEach { (birthDate, expected) ->
            assertEquals(
                expected,
                petProfileSummary(
                    pet(birthDate = PartialBirthDate.Day(birthDate)),
                    catalog,
                    today,
                ).items.single { it.label == "Возраст" }.value,
            )
        }
    }

    @Test
    fun `age row is included in accessible profile description`() {
        val summary = petProfileSummary(
            pet(birthDate = PartialBirthDate.Month(YearMonth.of(2024, 5))),
            catalog,
            LocalDate.of(2024, 6, 15),
        )

        assertEquals(
            "Дата рождения: 05.2024. Возраст: 0–1 месяц",
            summary.contentDescription,
        )
    }

    @Test
    fun `cat breed row shows localized supported breed`() {
        val breed = catalog.search("Maine Coon Cat", PetSpecies.CAT).single()
        val summary = petProfileSummary(
            pet(breedId = breed.id),
            catalog,
        )

        assertEquals(
            listOf(PetProfileSummaryItem("Порода", breed.displayName)),
            summary.items,
        )
        assertEquals("Порода: ${breed.displayName}", summary.contentDescription)
    }

    @Test
    fun `unavailable cat breed id stays visible`() {
        val summary = petProfileSummary(
            pet(breedId = BreedId("retired:cat:very-long-id")),
            catalog,
        )

        assertEquals(
            listOf(
                PetProfileSummaryItem(
                    "Порода",
                    "Недоступна: retired:cat:very-long-id",
                ),
            ),
            summary.items,
        )
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
