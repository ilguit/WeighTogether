package com.palixander.scalesync

import com.palixander.scalesync.ui.text.UiText
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.ManualWeightOwner
import com.palixander.scalesync.ui.manualweight.*
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManualWeightScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun draft() = ManualWeightDraft("request", ManualWeightOwner.Human(AccountId("human")),
        "Очень длинное имя выбранного профиля человека", LocalDate.of(2026, 9, 4), LocalTime.of(14, 12))

    @Test fun draftAndPetHistoryOwnerSurviveActivityRecreation() {
        lateinit var measurements: MeasurementsViewModel
        lateinit var petHistory: com.palixander.scalesync.ui.profiles.PetHistoryStateOwner
        val petId = com.palixander.scalesync.domain.PetId("synthetic-retention-pet")
        compose.runOnUiThread {
            measurements = ViewModelProvider(compose.activity)[MeasurementsViewModel::class.java]
            measurements.manualWeight.open(ManualWeightOwner.Pet(petId), "Питомец")
            measurements.manualWeight.changeWeight("4,125")
            petHistory = ViewModelProvider(compose.activity)[MainViewModel::class.java].petHistoryStateOwner(petId)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread {
            val restored = ViewModelProvider(compose.activity)[MeasurementsViewModel::class.java]
            org.junit.Assert.assertSame(measurements, restored)
            assertEquals("4,125", restored.manualWeight.draft.value?.weight)
            assertEquals(ManualWeightOwner.Pet(petId), restored.manualWeight.draft.value?.owner)
            org.junit.Assert.assertSame(petHistory, ViewModelProvider(compose.activity)[MainViewModel::class.java].petHistoryStateOwner(petId))
        }
    }

    @Test fun normalFormShowsOwnerAndCurrentMinuteFields() {
        compose.setContent { ScaleSyncTheme { ManualWeightScreen(draft(), {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag(ManualWeightTags.Date).assertContentDescriptionEquals("Дата измерения: 04.09.2026")
        compose.onNodeWithTag(ManualWeightTags.Time).assertContentDescriptionEquals("Время измерения: 14:12")
        compose.onNodeWithTag(ManualWeightTags.Save).assertIsNotEnabled()
        capture("manual-weight-normal")
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag(ManualWeightTags.Screen).captureToImage().asAndroidBitmap()
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun emptyHumanHistoryOffersManualWeight() {
        var additions = 0
        compose.setContent {
            ScaleSyncTheme {
                com.palixander.scalesync.measurements.MeasurementsScreen(
                    state = com.palixander.scalesync.measurements.MeasurementsUiState(
                        destination = com.palixander.scalesync.measurements.MeasurementsDestination.HISTORY,
                        isLoading = false,
                        accountSelector = com.palixander.scalesync.ui.accounts.AccountSelectorUiState(
                            accounts = emptyList(), selectedAccountId = AccountId("human"), primaryAccountId = null,
                        ),
                    ),
                    callbacks = com.palixander.scalesync.measurements.MeasurementsCallbacks.None.copy(onAddWeightRequested = { additions++ }),
                    showAccountSelector = false,
                )
            }
        }
        compose.onNodeWithTag("measurement-history-add").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, additions) }
    }

    @Test fun narrowLargeFontFormValidatesInputAndReturnsWithoutConfirmation() {
        var backCount = 0
        compose.setContent {
            var value by remember { mutableStateOf(draft()) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ScaleSyncTheme {
                    Box(Modifier.width(320.dp)) {
                        ManualWeightScreen(value, { value = value.copy(weight = it) }, {}, {}, {}, {}, {}, { backCount++ })
                    }
                }
            }
        }
        capture("manual-weight-narrow-large-font")
        compose.onNodeWithTag(ManualWeightTags.Weight).performScrollTo().performTextInput("4,1251")
        compose.onNodeWithTag(ManualWeightTags.Save).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ManualWeightTags.Weight).performScrollTo().performTextReplacement("4,125")
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "manual-weight-narrow-large-font-keyboard.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag(ManualWeightTags.Save).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ManualWeightTags.Back).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
        compose.onNodeWithText("Потерять изменения?").assertDoesNotExist()
    }

    @Test fun duplicateCancellationKeepsFieldsAndConfirmationUsesSeparateAction() {
        var confirmations = 0
        compose.setContent {
            var value by remember { mutableStateOf(draft().copy(weight = "4.125", duplicate = true)) }
            ScaleSyncTheme {
                ManualWeightScreen(value, {}, {}, {}, { value = value.copy(duplicate = true) },
                    { confirmations++ }, { value = value.copy(duplicate = false) }, {})
            }
        }
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag(ManualWeightTags.Duplicate).assertDoesNotExist()
        compose.onNodeWithTag(ManualWeightTags.Weight).assertTextContains("4.125")
        compose.onNodeWithTag(ManualWeightTags.Save).performScrollTo().performClick()
        compose.onNodeWithTag(ManualWeightTags.ConfirmDuplicate).performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun failedSaveKeepsWeightAndDeletedProfileDisablesSave() {
        val value = mutableStateOf(draft().copy(weight = "4,125", error = UiText.Raw("Не удалось сохранить вес. Попробуйте ещё раз")))
        compose.setContent {
            ScaleSyncTheme { ManualWeightScreen(value.value, {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Не удалось сохранить вес. Попробуйте ещё раз").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ManualWeightTags.Save).performScrollTo().assertIsEnabled()
        compose.runOnIdle { value.value = value.value.copy(ownerAvailable = false) }
        compose.onNodeWithTag(ManualWeightTags.Save).assertIsNotEnabled()
        compose.onNodeWithText("Профиль недоступен. Вернитесь к истории").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ManualWeightTags.Weight).assertTextContains("4,125")
    }
}
