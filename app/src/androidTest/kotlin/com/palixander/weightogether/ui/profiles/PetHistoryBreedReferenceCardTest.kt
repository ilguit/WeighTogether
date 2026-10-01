package com.palixander.weightogether.ui.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palixander.weightogether.ui.reference.ReferenceSourceLauncher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PetHistoryBreedReferenceCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun independentSourceActionsOpenTheirOwnUrlAndKeepErrorsSourceSpecific() {
        val opened = mutableListOf<String>()
        val launcher = ReferenceSourceLauncher { url ->
            opened += url
            url != CLUB_URL
        }
        compose.setContent {
            MaterialTheme {
                PetHistoryBreedReferenceCard(reference(), {}, launcher)
            }
        }

        compose.onNodeWithText("Источник и ограничения ▾").performClick()
        compose.onNodeWithTag(PetBreedReferenceTestTags.openSource(0)).performClick()
        compose.onNodeWithTag(PetBreedReferenceTestTags.sourceError(0)).assertDoesNotExist()
        compose.onNodeWithTag(PetBreedReferenceTestTags.openSource(1)).performClick()

        assertEquals(listOf(DOGSLIFE_URL, CLUB_URL), opened)
        compose.onNodeWithTag(PetBreedReferenceTestTags.sourceError(0)).assertDoesNotExist()
        compose.onNodeWithTag(PetBreedReferenceTestTags.sourceError(1)).assertIsDisplayed()
        compose.onNodeWithText("Ограничение: Approximate working-condition weight").assertIsDisplayed()
    }

    private fun reference() = PetHistoryBreedReference.Available(
        breedName = "Лабрадор-ретривер",
        ageLabel = "взрослой собаки",
        valueLabels = listOf("Медиана: 30 кг"),
        sourceKindLabel = "исследование",
        sexLabel = "самец",
        partialDateDisclosure = null,
        accessibilityLabel = "Ориентиры породы",
        chartValues = emptyList(),
        source = source("Dogslife", DOGSLIFE_URL),
        details = emptyList(),
        companionReferences = listOf(
            PetHistoryBreedCompanionReference(
                ageLabel = "взрослой собаки",
                sexLabel = "самец",
                valueLabels = listOf("Диапазон: 29,5–36,3 кг"),
                sourceKindLabel = "официальный национальный стандарт",
                chartValues = emptyList(),
                source = source(
                    "Labrador Retriever Club",
                    CLUB_URL,
                    listOf("Approximate working-condition weight"),
                ),
            ),
        ),
    )

    private fun source(title: String, url: String, limitations: List<String> = emptyList()) =
        PetHistoryBreedSource(title, url, 2024, "тип", null, null, null, null, limitations)

    private companion object {
        const val DOGSLIFE_URL = "https://example.org/dogslife"
        const val CLUB_URL = "https://example.org/labrador-club"
    }
}
