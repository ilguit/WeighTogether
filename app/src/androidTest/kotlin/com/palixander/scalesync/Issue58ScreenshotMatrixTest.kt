package com.palixander.scalesync

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.ui.accounts.AccountEditorDraft
import com.palixander.scalesync.ui.accounts.AccountEditorScreen
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementSection
import com.palixander.scalesync.ui.accounts.AccountManagementUiState
import com.palixander.scalesync.ui.accounts.WeightDeltaEditorState
import com.palixander.scalesync.ui.accounts.WeightRecognitionSetting
import com.palixander.scalesync.ui.routing.MeasurementResolverCallbacks
import com.palixander.scalesync.ui.routing.MeasurementResolverDialog
import com.palixander.scalesync.ui.routing.MeasurementResolverUiState
import com.palixander.scalesync.ui.routing.ResolverAccountOption
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewDialog
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewState
import com.palixander.scalesync.ui.routing.UnsavedPreviewCalculationError
import com.palixander.scalesync.ui.routing.UnsavedPreviewCallbacks
import com.palixander.scalesync.ui.routing.UnsavedPreviewProfileDraft
import com.palixander.scalesync.ui.routing.UnsavedPreviewStep
import com.palixander.scalesync.ui.routing.calculateUnsavedPreview
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test

/** Captures issue #58 review evidence from the production Compose implementations. */
class Issue58ScreenshotMatrixTest {
    @get:Rule
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun captureProductionComposeMatrix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("issue58Mode") ?: "normal"
        val currentScenario = mutableStateOf(scenarios(mode).first())
        activity.scenario.onActivity { host ->
            host.setShowWhenLocked(true)
            host.setTurnScreenOn(true)
            host.setContent { ScaleSyncTheme { Scenario(currentScenario.value) } }
        }
        scenarios(mode).forEach { name ->
            activity.scenario.onActivity { currentScenario.value = name }
            instrumentation.waitForIdleSync()
            Thread.sleep(750)
            instrumentation.waitForIdleSync()
            val path = "/sdcard/Download/issue58-final-$mode-$name.png"
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p $path",
            ).use { descriptor ->
                descriptor.fileDescriptor.let { java.io.FileInputStream(it).readBytes() }
            }
        }
    }

    private fun scenarios(mode: String): List<String> = when (mode) {
        "font200" -> listOf("human-editor-font200", "pet-editor-font200")
        else -> listOf(
            "profiles",
            "profiles-recognition",
            "human-editor",
            "pet-editor",
            "resolver",
            "unsaved-preview",
            "pet-first",
            "pet-second",
            "pet-result",
            "profiles-empty",
            "human-editor-long",
            "human-editor-saving",
            "human-editor-error",
            "pet-editor-long",
            "pet-editor-saving",
            "pet-editor-error",
            "resolver-saving",
            "unsaved-step-1",
            "unsaved-step-2",
            "unsaved-step-3",
            "unsaved-calculating",
            "unsaved-error",
            "pet-result-no-history",
            "pet-saving",
            "pet-unavailable",
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scenario(name: String) {
        when (name) {
            "profiles", "profiles-empty" -> Profiles(empty = name.endsWith("empty"))
            "profiles-recognition" -> Recognition()
            "human-editor", "human-editor-font200" -> HumanEditor(HUMAN_NAME)
            "human-editor-long" -> HumanEditor(LONG_HUMAN_NAME)
            "human-editor-saving" -> HumanEditor(HUMAN_NAME, busy = true)
            "human-editor-error" -> HumanEditor(HUMAN_NAME, error = "Не удалось сохранить профиль")
            "pet-editor", "pet-editor-font200" -> PetEditor(PET_NAME)
            "pet-editor-long" -> PetEditor(LONG_PET_NAME)
            "pet-editor-saving" -> PetEditor(PET_NAME, busy = true)
            "pet-editor-error" -> PetEditor(PET_NAME, error = "Не удалось сохранить питомца")
            "resolver" -> Resolver()
            "resolver-saving" -> Resolver(busy = true)
            "unsaved-preview", "unsaved-step-1" -> Unsaved(UnsavedPreviewStep.RAW_SUMMARY)
            "unsaved-step-2" -> Unsaved(UnsavedPreviewStep.PROFILE_EDITOR)
            "unsaved-step-3" -> Unsaved(UnsavedPreviewStep.RESULT)
            "unsaved-calculating" -> Unsaved(UnsavedPreviewStep.PROFILE_EDITOR, calculating = true)
            "unsaved-error" -> Unsaved(UnsavedPreviewStep.PROFILE_EDITOR, error = true)
            "pet-first" -> PetMeasurement(PetMeasurementUiState.AwaitingFirstWeight(PET, 71.8))
            "pet-second" -> PetMeasurement(PetMeasurementUiState.AwaitingSecondWeight(PET, 71.8, 77.2))
            "pet-result" -> PetMeasurement(result(previous = 5.1))
            "pet-result-no-history" -> PetMeasurement(result(previous = null))
            "pet-saving" -> PetMeasurement(PetMeasurementUiState.Saving(PET, 71.8, 77.2))
            "pet-unavailable" -> PetMeasurement(
                PetMeasurementUiState.ConnectionError(PET, 71.8, "Весы недоступны"),
            )
        }
    }

    @Composable
    private fun Recognition() {
        WeightRecognitionSetting(
            state = WeightDeltaEditorState(input = "3,0"),
            onStateChanged = {},
            onSave = {},
            ignoreUnknownMeasurements = false,
            onIgnoreUnknownMeasurementsChanged = {},
            modifier = Modifier.padding(16.dp),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Profiles(empty: Boolean) {
        Scaffold(topBar = { TopAppBar(title = { Text("Профили и питомцы") }) }) { padding ->
            AccountManagementSection(
                state = if (empty) AccountManagementUiState() else AccountManagementUiState(
                    accounts = listOf(HUMAN, SECOND_HUMAN),
                    primaryAccountId = HUMAN.id,
                ),
                callbacks = AccountManagementCallbacks.None,
                pets = if (empty) emptyList() else listOf(PetWithLatestWeight(PET, null)),
                petSpeciesLabel = { "Кошка" },
                petWeightLabel = { "Измерений пока нет" },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    @Composable
    private fun HumanEditor(name: String, busy: Boolean = false, error: String? = null) {
        val account = HUMAN.copy(displayName = name, normalizedName = name.lowercase())
        AccountEditorScreen(
            draft = AccountEditorDraft.edit(account),
            accounts = listOf(account),
            operationInProgress = busy,
            error = error,
            onDraftChanged = {}, onCreate = {}, onUpdate = {}, onDismiss = {},
            today = LocalDate.of(2026, 9, 10),
        )
    }

    @Composable
    private fun PetEditor(name: String, busy: Boolean = false, error: String? = null) {
        val pet = PET.copy(displayName = name, normalizedName = name.lowercase(), sex = PetSex.MALE)
        PetProfileEditorDialog(
            state = PetProfileEditorState.edit(pet),
            fieldErrors = PetProfileFieldErrors(),
            repositoryError = error,
            busy = busy,
            onAction = {}, onSave = {}, onDismiss = {},
        )
    }

    @Composable
    private fun Resolver(busy: Boolean = false) {
        MeasurementResolverDialog(
            state = MeasurementResolverUiState(
                pending = PENDING,
                accountOptions = listOf(
                    ResolverAccountOption(SECOND_HUMAN.id, SECOND_HUMAN.displayName, false),
                    ResolverAccountOption(HUMAN.id, HUMAN.displayName, true, 0.3, 72.0),
                ),
                ignoreUnknownMeasurements = false,
                operationInProgress = busy,
            ),
            callbacks = MeasurementResolverCallbacks.None,
        )
    }

    @Composable
    private fun Unsaved(
        step: UnsavedPreviewStep,
        calculating: Boolean = false,
        error: Boolean = false,
    ) {
        val draft = UnsavedPreviewProfileDraft(
            heightCm = "178",
            birthDate = LocalDate.of(1990, 5, 14),
            sex = Sex.MALE,
        )
        val result = if (step == UnsavedPreviewStep.RESULT) {
            requireNotNull(calculateUnsavedPreview(PENDING, draft, ZoneOffset.UTC))
        } else null
        UnsavedMeasurementPreviewDialog(
            state = UnsavedMeasurementPreviewState(
                pending = PENDING,
                step = step,
                profileDraft = draft,
                result = result,
                isCalculating = calculating,
                calculationRequestId = 1L.takeIf { calculating },
                calculationError = UnsavedPreviewCalculationError.CALCULATION_FAILED.takeIf { error },
            ),
            callbacks = UnsavedPreviewCallbacks.None,
            zoneId = ZoneOffset.UTC,
        )
    }

    @Composable
    private fun PetMeasurement(state: PetMeasurementUiState) {
        PetMeasurementDialog(
            state = state,
            pets = emptyList(),
            callbacks = PetMeasurementCallbacks({}, {}, { _, _ -> }, {}, {}, {}, {}),
        )
    }

    private fun result(previous: Double?) = PetMeasurementUiState.Result(
        pet = PET,
        measuredAt = NOW,
        firstWeightKg = 71.8,
        secondWeightKg = 77.2,
        previousPetWeightKg = previous,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-10T06:14:00Z")
        const val HUMAN_NAME = "Александр"
        const val LONG_HUMAN_NAME = "Александр с очень длинным именем профиля"
        const val PET_NAME = "Барсик"
        const val LONG_PET_NAME = "Барсик с очень длинным именем питомца"
        val HUMAN = Account(
            id = AccountId("alexander"),
            displayName = HUMAN_NAME,
            normalizedName = "александр",
            profile = AccountProfile.Complete(178.0, LocalDate.of(1990, 5, 14), Sex.MALE),
            createdAt = NOW,
            updatedAt = NOW,
        )
        val SECOND_HUMAN = Account(
            id = AccountId("maria"),
            displayName = "Мария с очень длинным именем",
            normalizedName = "мария с очень длинным именем",
            profile = AccountProfile.Complete(165.0, LocalDate.of(1992, 2, 2), Sex.FEMALE),
            createdAt = NOW,
            updatedAt = NOW,
        )
        val PET = Pet(
            id = PetId("barsik"),
            displayName = PET_NAME,
            normalizedName = "барсик",
            species = PetSpecies.CAT,
            createdAt = NOW,
            updatedAt = NOW,
            sex = PetSex.MALE,
        )
        val PENDING = PendingMeasurement(
            PendingMeasurementId("issue58"), "AA:BB:CC:DD:EE:FF", NOW,
            72.3, 500, true, true, byteArrayOf(1, 2, 3), "issue58-hash", NOW,
        )
    }
}
