package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import org.junit.Assert.assertEquals
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
        val submitted = mutableListOf<Pair<String, PetSpecies>>()
        val callbacks = callbacks(
            onShowCreate = { state.value = PetMeasurementUiState.CreatingPet },
            onCreate = { name, species -> submitted += name to species },
        )
        val pets = listOf(PetWithLatestWeight(pet("cat", "Барсик"), null))
        composeRule.setContent {
            ScaleSyncTheme { PetMeasurementDialog(state.value, pets, callbacks) }
        }

        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateAction).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexMale).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthPrecisionYear).assertDoesNotExist()
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.onNodeWithText("Введите имя питомца").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextInput(" барсик ")
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.onNodeWithText("Питомец с таким именем уже есть").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextClearance()
        composeRule.onNodeWithTag(PetMeasurementTestTags.NameField).performTextInput("Рыжик")
        composeRule.onNodeWithTag(PetMeasurementTestTags.SpeciesCat).performClick()
        composeRule.onNodeWithTag(PetMeasurementTestTags.CreateConfirm).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("Рыжик" to PetSpecies.CAT), submitted)
        }
    }

    @Test
    fun twoMeasurementPhasesAndSavingExplainProgress() {
        val pet = pet("dog", "Бим")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.AwaitingFirstWeight(pet),
        )
        composeRule.setContent {
            ScaleSyncTheme { PetMeasurementDialog(state.value, emptyList(), callbacks()) }
        }

        composeRule.onNodeWithText("Первое взвешивание").assertIsDisplayed()
        composeRule.onNodeWithText("с питомцем или без него", substring = true)
            .assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = PetMeasurementUiState.AwaitingSecondWeight(pet, 72.5)
        }
        composeRule.onNodeWithTag(PetMeasurementTestTags.FirstWeight).assertIsDisplayed()
        composeRule.onNodeWithText("Первое показание:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("другом варианте", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = PetMeasurementUiState.Saving(pet, 72.5, 77.2)
        }
        composeRule.onNodeWithText("Сохраняем результат…").assertIsDisplayed()
    }

    @Test
    fun completedErrorCancelAndSystemBackExposeSafeExits() {
        val pet = pet("cat", "Луна")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.Result(
                pet,
                measuredAt = Instant.parse("2026-08-26T01:02:03Z"),
                firstWeightKg = 60.0,
                secondWeightKg = 63.75,
                previousPetWeightKg = 3.5,
            ),
        )
        var cancelled = 0
        var reopened = 0
        val callbacks = callbacks(
            onOpen = {
                reopened++
                state.value = PetMeasurementUiState.SelectingPet
            },
            onCancel = { cancelled++ },
            onDone = { cancelled++ },
        )
        composeRule.setContent {
            ScaleSyncTheme { PetMeasurementDialog(state.value, emptyList(), callbacks) }
        }

        composeRule.onNodeWithText("Вес питомца —", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("+0,25 кг с прошлого измерения").assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.Done).performClick()
        composeRule.runOnIdle { assertEquals(1, cancelled) }
        composeRule.runOnIdle {
            state.value = PetMeasurementUiState.Error(PET_MEASUREMENT_TIMEOUT_MESSAGE)
        }
        composeRule.onNodeWithText(PET_MEASUREMENT_TIMEOUT_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(PetMeasurementTestTags.BackToSelection).performClick()
        composeRule.runOnIdle { assertEquals(1, reopened) }
        composeRule.onNodeWithText("Выберите питомца").assertIsDisplayed()

        composeRule.runOnIdle { state.value = PetMeasurementUiState.Error("Весы недоступны") }
        composeRule.onNodeWithTag(PetMeasurementTestTags.Cancel).performClick()
        composeRule.runOnIdle { assertEquals(2, cancelled) }

        composeRule.runOnIdle { state.value = PetMeasurementUiState.AwaitingFirstWeight(pet) }
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.runOnIdle { assertEquals(3, cancelled) }
    }

    @Test
    fun firstResultHasNoPreviousDelta() {
        val pet = pet("cat", "Луна")
        setDialog(
            state = PetMeasurementUiState.Result(
                pet = pet,
                measuredAt = Instant.parse("2026-08-26T01:02:03Z"),
                firstWeightKg = 60.0,
                secondWeightKg = 63.75,
                previousPetWeightKg = null,
            ),
            pets = emptyList(),
            callbacks = callbacks(),
        )

        composeRule.onNodeWithText("Вес питомца — 3,75 кг").assertIsDisplayed()
        composeRule.onNodeWithText("с прошлого измерения", substring = true).assertDoesNotExist()
    }

    @Test
    fun savingIgnoresSystemDismissAndHasNoCancelWhileCaptureCanBeDismissed() {
        val pet = pet("cat", "Луна")
        val state = mutableStateOf<PetMeasurementUiState>(
            PetMeasurementUiState.Saving(pet, 60.0, 63.75),
        )
        var cancelled = 0
        composeRule.setContent {
            ScaleSyncTheme {
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

    @Test
    fun outsideTapDoesNotDismissWhileSystemBackStillCancels() {
        val pet = pet("cat", "Луна")
        var cancelled = 0
        setDialog(
            state = PetMeasurementUiState.AwaitingFirstWeight(pet),
            pets = emptyList(),
            callbacks = callbacks(onCancel = { cancelled++ }),
        )

        composeRule.onNode(isDialog()).performTouchInput {
            click(Offset(-1f, -1f))
        }
        composeRule.runOnIdle { assertEquals(0, cancelled) }
        composeRule.onNodeWithTag(PetMeasurementTestTags.Dialog).assertIsDisplayed()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }

    private fun setDialog(
        state: PetMeasurementUiState,
        pets: List<PetWithLatestWeight>,
        callbacks: PetMeasurementCallbacks,
    ) {
        composeRule.setContent {
            ScaleSyncTheme { PetMeasurementDialog(state, pets, callbacks) }
        }
    }

    private fun callbacks(
        onOpen: () -> Unit = {},
        onShowCreate: () -> Unit = {},
        onCreate: (String, PetSpecies) -> Unit = { _, _ -> },
        onStart: (PetId) -> Unit = {},
        onCancel: () -> Unit = {},
        onDone: () -> Unit = {},
    ) = PetMeasurementCallbacks(
        onOpen,
        onShowCreate,
        onCreate,
        onStart,
        onCancel,
        onDone,
        {},
    )

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
