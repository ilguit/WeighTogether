package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PetMeasurementDialogUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectionShowsLatestValueAndCanStartExistingPet() {
        val pet = pet("cat", "Барсик")
        var selected: PetId? = null
        setDialog(
            state = PetMeasurementUiState.SelectingPet,
            pets = listOf(
                PetWithLatestWeight(
                    pet,
                    measurement(pet.id, first = 70.0, second = 74.25),
                ),
            ),
            callbacks = callbacks(onStart = { selected = it }),
        )

        composeRule.onNodeWithContentDescription("Барсик. Последний вес:", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.pet(pet.id)).performClick()
        composeRule.runOnIdle { assertEquals(pet.id, selected) }
    }

    @Test
    fun creationValidatesEmptyAndDuplicateNameBeforeSubmitting() {
        val state = mutableStateOf<PetMeasurementUiState>(PetMeasurementUiState.SelectingPet)
        val submitted = mutableListOf<String>()
        val callbacks = callbacks(
            onShowCreate = { state.value = PetMeasurementUiState.CreatingPet },
            onCreate = submitted::add,
        )
        val pets = listOf(PetWithLatestWeight(pet("cat", "Барсик"), null))
        composeRule.setContent {
            HuaweiMiSyncTheme { PetMeasurementDialog(state.value, pets, callbacks) }
        }

        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateAction).performClick()
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.onNodeWithText("Введите имя питомца").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextInput(" барсик ")
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.onNodeWithText("Питомец с таким именем уже есть").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextClearance()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextInput("Рыжик")
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.runOnIdle { assertEquals(listOf("Рыжик"), submitted) }
    }

    @Test
    fun twoMeasurementPhasesAndSavingExplainProgress() {
        val pet = pet("dog", "Бим")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.AwaitingFirstWeight(pet),
        )
        composeRule.setContent {
            HuaweiMiSyncTheme { PetMeasurementDialog(state.value, emptyList(), callbacks()) }
        }

        composeRule.onNodeWithText("Первое взвешивание").assertIsDisplayed()
        composeRule.onNodeWithText("Встаньте на весы без Бим. Дождитесь стабильного значения.")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = PetMeasurementUiState.AwaitingSecondWeight(pet, 72.5)
        }
        composeRule.onNodeWithTag(PetMeasurementTestTags.FirstWeight).assertIsDisplayed()
        composeRule.onNodeWithText("Первое значение принято:", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = PetMeasurementUiState.Saving(pet, 72.5, 77.2)
        }
        composeRule.onNodeWithText("Сохраняем результат…").assertIsDisplayed()
    }

    @Test
    fun completedErrorCancelAndSystemBackExposeSafeExits() {
        val pet = pet("cat", "Луна")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.Completed(
                pet,
                measurement(pet.id, first = 60.0, second = 63.75),
            ),
        )
        var cancelled = 0
        var reopened = 0
        val callbacks = callbacks(
            onOpen = { reopened++ },
            onCancel = { cancelled++ },
        )
        composeRule.setContent {
            HuaweiMiSyncTheme { PetMeasurementDialog(state.value, emptyList(), callbacks) }
        }

        composeRule.onNodeWithText("Вес Луна:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.Done).performClick()
        composeRule.runOnIdle { assertEquals(1, cancelled) }
        composeRule.runOnIdle { state.value = PetMeasurementUiState.Error("Весы недоступны") }
        composeRule.onNodeWithText("Весы недоступны").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.BackToSelection).performClick()
        composeRule.runOnIdle { assertEquals(1, reopened) }
        composeRule.runOnIdle { state.value = PetMeasurementUiState.AwaitingFirstWeight(pet) }
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.runOnIdle { assertTrue(cancelled >= 2) }
    }

    @Test
    fun savingIgnoresSystemDismissAndHasNoCancelWhileCancellableStateDismisses() {
        val pet = pet("cat", "Луна")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.Saving(pet, 60.0, 63.75),
        )
        var cancelled = 0
        composeRule.setContent {
            HuaweiMiSyncTheme {
                PetMeasurementDialog(
                    state.value,
                    emptyList(),
                    callbacks(onCancel = { cancelled++ }),
                )
            }
        }

        composeRule.onNodeWithTag(PetMeasurementTestTags.Cancel).assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(0, cancelled) }

        composeRule.runOnIdle { state.value = PetMeasurementUiState.AwaitingFirstWeight(pet) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    private fun setDialog(
        state: PetMeasurementUiState,
        pets: List<PetWithLatestWeight>,
        callbacks: PetMeasurementCallbacks,
    ) {
        composeRule.setContent {
            HuaweiMiSyncTheme { PetMeasurementDialog(state, pets, callbacks) }
        }
    }

    private fun callbacks(
        onOpen: () -> Unit = {},
        onShowCreate: () -> Unit = {},
        onCreate: (String) -> Unit = {},
        onStart: (PetId) -> Unit = {},
        onCancel: () -> Unit = {},
    ) = PetMeasurementCallbacks(onOpen, onShowCreate, onCreate, onStart, onCancel)

    private fun pet(id: String, name: String): Pet {
        val now = Instant.parse("2026-08-26T00:00:00Z")
        return Pet(PetId(id), name, createdAt = now, updatedAt = now)
    }

    private fun measurement(
        petId: PetId,
        first: Double,
        second: Double,
    ) = PetMeasurement(
        id = "measurement-${petId.value}",
        petId = petId,
        measuredAt = Instant.parse("2026-08-26T01:02:03Z"),
        firstWeightKg = first,
        secondWeightKg = second,
    )
}
