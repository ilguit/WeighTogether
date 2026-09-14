package com.palixander.scalesync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.profiles.HomePetShortcuts
import com.palixander.scalesync.ui.profiles.HomePetShortcutsTestTags as Tags
import com.palixander.scalesync.ui.profiles.ProfileKey
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

abstract class HomePetShortcutsTestCases {
    @get:Rule val composeRule = createComposeRule()

    @Test fun zeroPetsHasOnly24DpControlAndAddAppearsAfterTap() {
        var addCalls = 0
        composeRule.setContent {
            ScaleSyncTheme { HomePetShortcuts(emptyList(), {}, { addCalls++ }) }
        }
        val block = composeRule.onNodeWithTag(Tags.Block).getUnclippedBoundsInRoot()
        assertEquals(24.dp, block.bottom - block.top)
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        val toggle = composeRule.onNodeWithTag(Tags.Toggle).getUnclippedBoundsInRoot()
        assertEquals(24.dp, toggle.bottom - toggle.top)
        val add = composeRule.onNodeWithTag(Tags.Add).getUnclippedBoundsInRoot()
        assertTrue(add.bottom <= toggle.top)
        composeRule.onNodeWithTag(Tags.Add).performClick()
        composeRule.runOnIdle { assertEquals(1, addCalls) }
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
    }

    @Test fun collapsedShowsWholeFirstRowAndDispatchesExactProfile() {
        var selected: ProfileKey? = null
        composeRule.setContent {
            ScaleSyncTheme {
                HomePetShortcuts(listOf(pet("1", "Кот"), pet("2", "Пёс")), { selected = it }, {}, Modifier.width(320.dp))
            }
        }
        composeRule.onNodeWithTag(Tags.Toggle).assertExists()
        assertRowFillsWidth("1", "2")
        composeRule.onNodeWithTag(Tags.pet("2")).assertIsDisplayed()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        assertRowFillsWidth("1", "2")
        composeRule.onNodeWithTag(Tags.pet("2")).performClick()
        composeRule.runOnIdle { assertEquals(ProfileKey.Pet(PetId("2")), selected) }
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        composeRule.onNodeWithTag(Tags.pet("2")).assertIsDisplayed()
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
    }

    @Test fun expandedFillsLastIncompleteRowAndKeepsEightDpGaps() {
        val pets = listOf(pet("1", "Кот"), pet("2", "Пёс"), pet("3", "Лис"))
        composeRule.setContent {
            ScaleSyncTheme { HomePetShortcuts(pets, {}, {}, Modifier.width(150.dp)) }
        }
        assertRowFillsWidth("1", "2")
        composeRule.onNodeWithTag(Tags.pet("3")).assertDoesNotExist()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        assertRowFillsWidth("1", "2")
        assertFullWidth("3")
        val first = composeRule.onNodeWithTag(Tags.pet("1")).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag(Tags.pet("2")).getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithTag(Tags.pet("3")).getUnclippedBoundsInRoot()
        assertEquals(8.dp, second.left - first.right)
        assertEquals(8.dp, third.top - first.bottom)
        assertTrue(kotlin.math.abs(((first.right - first.left) - (second.right - second.left)).value) <= 1f)
    }

    @Test fun collapsedHidesSemanticsAndRestoresExpansionWithStableRows() {
        val restoration = StateRestorationTester(composeRule)
        val pets = List(5) { pet("$it", "Длинное имя питомца $it") }
        restoration.setContent {
            ScaleSyncTheme { HomePetShortcuts(pets, {}, {}, Modifier.width(100.dp)) }
        }
        composeRule.onNodeWithTag(Tags.pet("0")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Длинное имя питомца 1, питомец").assertDoesNotExist()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        val first = composeRule.onNodeWithTag(Tags.pet("0")).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag(Tags.pet("1")).getUnclippedBoundsInRoot()
        assertTrue(second.top >= first.bottom)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(Tags.pet("4")).assertExists()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        composeRule.onNodeWithTag(Tags.pet("4")).assertDoesNotExist()
    }

    @Test fun widthAndFontChangesKeepFullWidthAndAccessibleNames() {
        val width = mutableStateOf(100.dp)
        val fontScale = mutableStateOf(1f)
        val pets = listOf(pet("1", "Барсик-кот"), pet("2", "Мурка-кошка"))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                ScaleSyncTheme {
                    Box(Modifier.width(width.value)) { HomePetShortcuts(pets, {}, {}) }
                }
            }
        }
        assertFullWidth("1")
        composeRule.onNodeWithTag(Tags.pet("2")).assertDoesNotExist()
        composeRule.runOnIdle { width.value = 320.dp }
        assertRowFillsWidth("1", "2")
        composeRule.runOnIdle {
            width.value = 120.dp
            fontScale.value = 2f
        }
        composeRule.onNodeWithTag(Tags.Toggle).assertExists()
        composeRule.onNodeWithContentDescription("Барсик-кот, питомец").assertIsDisplayed()
        assertFullWidth("1")
        composeRule.onNodeWithTag(Tags.pet("2")).assertDoesNotExist()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        assertFullWidth("2")
        composeRule.onNodeWithContentDescription("Мурка-кошка, питомец").assertIsDisplayed()
    }

    @Test fun verticalDragOpensAndClosesWithoutTap() {
        composeRule.setContent {
            ScaleSyncTheme { HomePetShortcuts(emptyList(), {}, {}) }
        }
        composeRule.onNodeWithTag(Tags.Toggle).performTouchInput {
            swipe(center, center + Offset(0f, 180f), 500)
        }
        composeRule.onNodeWithTag(Tags.Add).assertIsDisplayed()
        composeRule.onNodeWithTag(Tags.Toggle).performTouchInput {
            swipe(center, center - Offset(0f, 180f), 500)
        }
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
    }

    @Test fun changingPetsUpdatesAddVisibilityWithoutLosingExpansion() {
        val pets = mutableStateOf(emptyList<ProfilePresentation.Pet>())
        composeRule.setContent {
            ScaleSyncTheme { HomePetShortcuts(pets.value, {}, {}, Modifier.width(240.dp)) }
        }
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        composeRule.onNodeWithTag(Tags.Add).assertIsDisplayed()
        composeRule.runOnIdle { pets.value = listOf(pet("1", "Кот")) }
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
        assertFullWidth("1")
        composeRule.runOnIdle { pets.value = emptyList() }
        composeRule.onNodeWithTag(Tags.Add).assertIsDisplayed()
        composeRule.onNodeWithTag(Tags.Toggle).performClick()
        composeRule.runOnIdle { pets.value = listOf(pet("1", "Кот")) }
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
        assertFullWidth("1")
        composeRule.runOnIdle { pets.value = emptyList() }
        composeRule.onNodeWithTag(Tags.Add).assertDoesNotExist()
    }

    private fun assertFullWidth(id: String) {
        val block = composeRule.onNodeWithTag(Tags.Block).getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag(Tags.pet(id)).getUnclippedBoundsInRoot()
        assertEquals(block.left, button.left)
        assertEquals(block.right, button.right)
    }

    private fun assertRowFillsWidth(firstId: String, lastId: String) {
        val block = composeRule.onNodeWithTag(Tags.Block).getUnclippedBoundsInRoot()
        val first = composeRule.onNodeWithTag(Tags.pet(firstId)).getUnclippedBoundsInRoot()
        val last = composeRule.onNodeWithTag(Tags.pet(lastId)).getUnclippedBoundsInRoot()
        assertEquals(block.left, first.left)
        assertEquals(block.right, last.right)
        assertEquals(first.top, last.top)
    }

    private fun pet(id: String, name: String) = ProfilePresentation.Pet(
        PetWithLatestWeight(
            Pet(PetId(id), name, species = PetSpecies.CAT, createdAt = NOW, updatedAt = NOW),
            latestMeasurement = null,
        ),
    )
    private companion object { val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z") }
}
