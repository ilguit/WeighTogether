package com.palixander.weightogether

import androidx.test.espresso.Espresso.closeSoftKeyboard
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.domain.BreedId
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import com.palixander.weightogether.profile.PreparedProfilePhoto
import com.palixander.weightogether.profile.ProfilePhotoCropTestTags
import com.palixander.weightogether.profile.ProfilePhotoPicker
import com.palixander.weightogether.profile.ProfilePhotoStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PetProfileEditorDialogUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun heightCanBeEnteredAndClearedAndTriggersUnsavedChanges() {
        val state = mutableStateOf(PetProfileEditorState(PetProfileDraft.create()))
        setEditor(state)
        composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField)
            .performScrollTo().performTextReplacement("25,125")
        composeRule.runOnIdle { assertEquals("25,125", state.value.draft.heightCm) }
        closeSoftKeyboard()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Back).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.DiscardConfirmation).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.KeepEditing).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField)
            .performScrollTo().performTextReplacement("")
        composeRule.runOnIdle { assertEquals("", state.value.draft.heightCm) }
    }

    @Test
    fun invalidHeightReceivesFocusAndShowsAccessibleError() {
        val state = mutableStateOf(PetProfileEditorState(PetProfileDraft.create().copy(heightCm = "0")))
        val errors = mutableStateOf(PetProfileFieldErrors())
        setEditor(state, errors = errors)
        composeRule.runOnIdle { errors.value = PetProfileFieldErrors(heightCm = PetHeightValidationError.INVALID) }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField)
            .assertIsFocused().performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Введите рост больше нуля").assertIsDisplayed()
    }

    private val catalog = PetBreedCatalog()
    private val unmappedDog = PetBreedSelection.Available(
        requireNotNull(catalog.search("Доберман", PetSpecies.DOG).singleOrNull()),
    )

    @Test
    fun preparedPhotoConfirmedThroughRealEditorUpdatesPetDraftAndConsumesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfilePhotoStore(context)
        val prepared = preparedPhoto(store)
        val state = mutableStateOf(PetProfileEditorState(PetProfileDraft.create()))

        setEditor(
            state = state,
            photoPickerFactory = { _, onPrepared, _ ->
                ProfilePhotoPicker(
                    chooseFromGallery = { onPrepared(prepared) },
                    takePhoto = {},
                )
            },
        )

        composeRule.onNodeWithTag(PetProfileEditorTestTags.PhotoGallery)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertDoesNotExist()
        composeRule.runOnIdle {
            val path = state.value.draft.photoPath
            assertTrue(path?.startsWith("profile-photos/pets/") == true)
            assertTrue(requireNotNull(path).let(store::resolve).isFile)
            assertNull(store.restorePrepared(prepared.identifier))
        }
    }

    @Test
    fun newPetCropInFlightHandoffSurvivesActivityRecreation() {
        val enteredHandoff = CompletableDeferred<Unit>()
        val continueHandoff = CompletableDeferred<Unit>()
        val deliveries = AtomicInteger()
        val store = ProfilePhotoStore.createForTest(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            maxDimensionPx = 320,
            afterManagedPhotoCreated = {
                enteredHandoff.complete(Unit)
                continueHandoff.await()
            },
            deleteFile = File::delete,
        )
        val prepared = preparedPhoto(store)
        val state = mutableStateOf(PetProfileEditorState(PetProfileDraft.create()))

        setEditor(
            state = state,
            profilePhotoStore = store,
            actions = object : ArrayList<PetProfileAction>() {
                override fun add(element: PetProfileAction): Boolean {
                    if (element is PetProfileAction.PhotoChanged) deliveries.incrementAndGet()
                    return super.add(element)
                }
            },
            photoPickerFactory = { _, onPrepared, _ ->
                ProfilePhotoPicker({ onPrepared(prepared) }, {})
            },
        )

        composeRule.onNodeWithTag(PetProfileEditorTestTags.PhotoGallery)
            .performScrollTo().performClick()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Done).performClick()
        runBlocking { enteredHandoff.await() }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag(ProfilePhotoCropTestTags.Editor).assertIsDisplayed()
        continueHandoff.complete(Unit)
        composeRule.waitUntil(10_000) { state.value.draft.photoPath != null }
        composeRule.runOnIdle { assertEquals(1, deliveries.get()) }
    }

    @Test
    fun filledEditShowsValuesAndEveryOptionalFieldCanBeCleared() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft(
                    mode = PetProfileEditorMode.Edit(PetId("luna")),
                    displayName = "Луна",
                    species = PetSpecies.DOG,
                    sex = PetSex.FEMALE,
                    breed = unmappedDog,
                    birthDate = PetBirthDateInput.Day("2020", "02", "29"),
                    dogAdultWeightCategory = DogAdultWeightCategory.III,
                ),
            ),
        )
        setEditor(state)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField)
            .assertTextContains("Луна")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesDog)
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    Role.RadioButton,
                ),
            )
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexFemale).assertIsSelected()
        composeRule.onNodeWithText("Доберман").assertExists()

        composeRule.onNodeWithTag(PetProfileEditorTestTags.CategoryClear)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthClear).assertDoesNotExist()
        composeRule.onNodeWithText("Месяц: Февраль").assertExists()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthYear).performScrollTo().performClick()
        composeRule.onNodeWithTag("pet-birth-part-clear").performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexClear)
            .performScrollTo()
            .performClick()
        clearBreedThroughPicker()

        composeRule.runOnIdle {
            assertNull(state.value.draft.sex)
            assertNull(state.value.draft.breed)
            assertEquals(PetBirthDateInput.Empty, state.value.draft.birthDate)
            assertNull(state.value.draft.dogAdultWeightCategory)
        }
    }

    @Test
    fun fullScreenEditorUsesNestedNavigationAndFixedSaveAction() {
        val state = mutableStateOf(
            PetProfileEditorState(PetProfileDraft.create().copy(species = PetSpecies.CAT)),
        )
        var dismisses = 0
        setEditor(state, onDismiss = { dismisses++ })

        composeRule.onNodeWithText("Новый питомец").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Title)
            .assertIsFocused()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Dialog).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Back)
            .assertContentDescriptionEquals("Вернуться к профилям")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Save).assertIsDisplayed()
        captureSyntheticScreenshot("empty-editor")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesGroup)
            .assertContentDescriptionEquals("Вид питомца")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexGroup)
            .assertContentDescriptionEquals("Пол питомца")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesCat).assertIsSelected()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Back).performClick()
        composeRule.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun dirtyBackRequiresConfirmationAndCanKeepOrDiscardDraft() {
        val state = mutableStateOf(PetProfileEditorState(PetProfileDraft.create()))
        var dismisses = 0
        setEditor(state, onDismiss = { dismisses++ })

        composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField).performTextInput("Луна")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Back).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.DiscardConfirmation).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.KeepEditing).performClick()
        composeRule.runOnIdle { assertEquals(0, dismisses) }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField).assertTextContains("Луна")

        closeSoftKeyboard()
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Discard).performClick()
        composeRule.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun validationMovesFocusToFirstInvalidFieldAndKeepsDraft() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Луна",
                    species = PetSpecies.CAT,
                    birthDate = PetBirthDateInput.Month("2020", ""),
                ),
            ),
        )
        val errors = mutableStateOf(PetProfileFieldErrors())
        setEditor(state, errors = errors)

        composeRule.runOnIdle {
            errors.value = PetProfileFieldErrors(
                displayName = PetNameValidationError.DUPLICATE,
                birthDate = PetBirthDateValidationError.INCOMPLETE,
            )
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField).assertIsFocused()
        composeRule.onNodeWithText("Луна", substring = true).assertExists()
        composeRule.onNodeWithText("Заполните все выбранные части даты")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dogBreedSearchIsLocalizedAliasAwareAndShowsNoResults() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Бим",
                    species = PetSpecies.DOG,
                ),
            ),
        )
        val dog = catalog.search("Russian Black Terrier", PetSpecies.DOG)
            .first { "Russian Black Terrier" in it.aliases }
        setEditor(state)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("Russian Black Terrier")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.breedOption(dog.id.value))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(dog.id, state.value.draft.breed?.id)
            assertEquals(PetSpecies.DOG, state.value.draft.breed?.species)
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("несуществующая порода")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedNoResults).assertIsDisplayed()
    }

    @Test
    fun catBreedPickerSelectsCatAndDoesNotExposeDogBreeds() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Мурка",
                    species = PetSpecies.CAT,
                ),
            ),
        )
        val cat = catalog.search("Maine Coon Cat", PetSpecies.CAT).single()
        val dog = catalog.search("Лабрадор", PetSpecies.DOG).single()
        setEditor(state)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("Maine Coon Cat")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.breedOption(cat.id.value))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(cat.id, state.value.draft.breed?.id)
            assertEquals(PetSpecies.CAT, state.value.draft.breed?.species)
            assertNull(state.value.draft.dogAdultWeightCategory)
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("Лабрадор")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.breedOption(dog.id.value))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedNoResults).assertIsDisplayed()
    }

    @Test
    fun yearMonthAndDayInputsDispatchTypedPrecisionWithoutInventingParts() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(displayName = "Луна", species = PetSpecies.CAT),
            ),
        )
        val actions = mutableListOf<PetProfileAction>()
        setEditor(state, actions = actions)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthMonth).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthDay).assertDoesNotExist()
        chooseBirthPart(PetProfileEditorTestTags.BirthYear, 2020)
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Year("2020"), state.value.draft.birthDate)
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthDay).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthMonth).performScrollTo().performClick()
        composeRule.onNodeWithText("Февраль").assertExists()
        composeRule.onNodeWithTag("pet-birth-option-2").performClick()
        composeRule.onNodeWithText("Месяц: Февраль").assertExists()
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Month("2020", "2"), state.value.draft.birthDate)
        }
        chooseBirthPart(PetProfileEditorTestTags.BirthDay, 29)
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Day("2020", "2", "29"), state.value.draft.birthDate)
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthMonth).performScrollTo().performClick()
        composeRule.onNodeWithTag("pet-birth-part-clear").performClick()
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Year("2020"), state.value.draft.birthDate)
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthClear).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthYear).performScrollTo().performClick()
        composeRule.onNodeWithTag("pet-birth-part-clear").performClick()
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Empty, state.value.draft.birthDate)
        }
    }

    @Test
    fun editingLeapDayDropsImpossibleDayWhenYearChanges() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Луна",
                    species = PetSpecies.CAT,
                    birthDate = PetBirthDateInput.Day("2024", "02", "29"),
                ),
            ),
        )
        setEditor(state)
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthDay)
            .performScrollTo().assertTextEquals("День: 29")
        chooseBirthPart(PetProfileEditorTestTags.BirthYear, 2025)
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Month("2025", "2"), state.value.draft.birthDate)
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthDay)
            .performScrollTo().assertTextEquals("День: Выбрать")
        chooseBirthPart(PetProfileEditorTestTags.BirthDay, 28)
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Day("2025", "2", "28"), state.value.draft.birthDate)
        }
    }

    private fun captureSyntheticScreenshot(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Dialog window transitions run outside Compose's idling resources.
        android.os.SystemClock.sleep(350)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "issue115")
        check(directory.mkdirs() || directory.isDirectory)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun clearBreedThroughPicker() {
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performScrollTo().performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextReplacement("Другая порода")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedOther)
            .assertTextEquals("Другая порода").performClick()
    }

    private fun chooseBirthPart(tag: String, value: Int) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.onNodeWithTag("pet-birth-options")
            .performScrollToNode(hasTestTag("pet-birth-option-$value"))
        captureSyntheticScreenshot("picker-$tag-$value")
        composeRule.onNodeWithTag("pet-birth-option-$value").performClick()
    }

    @Test
    fun mappedBreedsSelectSaltCategoryAndAutomaticValueDoesNotLeak() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Бим",
                    species = PetSpecies.DOG,
                ),
            ),
        )
        setEditor(state)

        composeRule.onNodeWithText(
            "Определяет категорийную центильную кривую Salt для возраста от 12 недель до 2 лет.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(
                    PetBreedSelection.Available(
                        catalog.search("Лабрадор", PetSpecies.DOG).single(),
                    ),
                ),
            )
        }
        composeRule.onNodeWithTag(
            PetProfileEditorTestTags.category(DogAdultWeightCategory.V),
        ).performScrollTo()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

        composeRule.runOnIdle {
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(
                    PetBreedSelection.Available(
                        catalog.search("Бигль", PetSpecies.DOG).single(),
                    ),
                ),
            )
        }
        composeRule.onNodeWithTag(
            PetProfileEditorTestTags.category(DogAdultWeightCategory.III),
        ).performScrollTo().assertIsSelected()

        composeRule.runOnIdle {
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(
                    PetBreedSelection.Available(
                        catalog.search("Доберман", PetSpecies.DOG).single(),
                    ),
                ),
            )
            assertNull(state.value.draft.dogAdultWeightCategory)
        }

        composeRule.runOnIdle {
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(
                    PetBreedSelection.Available(
                        catalog.search("Лабрадор", PetSpecies.DOG).single(),
                    ),
                ),
            )
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(null),
            )
            assertNull(state.value.draft.breed)
            assertNull(state.value.draft.dogAdultWeightCategory)
        }
    }

    @Test
    fun manuallySelectedCategorySurvivesUnmappedAndOtherBreedSelections() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Бим",
                    species = PetSpecies.DOG,
                ),
            ),
        )
        setEditor(state)

        composeRule.onNodeWithTag(
            PetProfileEditorTestTags.category(DogAdultWeightCategory.II),
        ).performScrollTo().performClick()

        composeRule.runOnIdle {
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(
                    PetBreedSelection.Available(
                        catalog.search("Доберман", PetSpecies.DOG).single(),
                    ),
                ),
            )
            state.value = PetProfileReducer.reduce(
                state.value,
                PetProfileAction.BreedChanged(null),
            )
            assertEquals(DogAdultWeightCategory.II, state.value.draft.dogAdultWeightCategory)
        }
        composeRule.onNodeWithTag(
            PetProfileEditorTestTags.category(DogAdultWeightCategory.II),
        ).assertIsSelected()
    }

    @Test
    fun pendingSpeciesChangeCanBeCancelledThenConfirmed() {
        val initialDraft = PetProfileDraft.create().copy(
            displayName = "Бим",
            species = PetSpecies.DOG,
            breed = unmappedDog,
            dogAdultWeightCategory = DogAdultWeightCategory.II,
        )
        val state = mutableStateOf(PetProfileEditorState(initialDraft))
        setEditor(state)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesCat).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesConfirmation)
            .assertIsDisplayed()
        composeRule.onNodeWithText("порода и весовая категория", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesCancel).performClick()
        composeRule.runOnIdle {
            assertEquals(initialDraft, state.value.draft)
            assertNull(state.value.pendingSpeciesChange)
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesCat).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesConfirm).performClick()
        composeRule.runOnIdle {
            assertEquals(PetSpecies.CAT, state.value.draft.species)
            assertNull(state.value.draft.breed)
            assertNull(state.value.draft.dogAdultWeightCategory)
            assertNull(state.value.pendingSpeciesChange)
        }
    }

    @Test
    fun busyDisablesAllExitsShowsErrorsAndPreventsDoubleSubmit() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Бим",
                    species = PetSpecies.DOG,
                    breed = unmappedDog,
                    birthDate = PetBirthDateInput.Month("2026", ""),
                ),
            ),
        )
        val busy = mutableStateOf(true)
        val errors = mutableStateOf(
            PetProfileFieldErrors(
                displayName = PetNameValidationError.DUPLICATE,
                birthDate = PetBirthDateValidationError.INCOMPLETE,
            ),
        )
        var saves = 0
        var dismisses = 0
        setEditor(
            state = state,
            busy = busy,
            errors = errors,
            saveError = mutableStateOf("Хранилище временно недоступно"),
            onSave = { saves++ },
            onDismiss = { dismisses++ },
        )

        composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthYear).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthMonth).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Save).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Back).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SaveError)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Сохранение профиля питомца")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Сохранение",
                ),
            )
            .assertIsDisplayed()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.runOnIdle {
            assertEquals(0, saves)
            assertEquals(0, dismisses)
            busy.value = false
            errors.value = PetProfileFieldErrors()
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.Save)
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Save).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun birthDateErrorsRemainDistinctAtTheField() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Луна",
                    species = PetSpecies.CAT,
                    birthDate = PetBirthDateInput.Day("2026", "09", "01"),
                ),
            ),
        )
        val errors = mutableStateOf(
            PetProfileFieldErrors(birthDate = PetBirthDateValidationError.INCOMPLETE),
        )
        setEditor(state, errors = errors)

        composeRule.onNodeWithText("Заполните все выбранные части даты")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle {
            errors.value = PetProfileFieldErrors(birthDate = PetBirthDateValidationError.INVALID)
        }
        composeRule.onNodeWithText("Введите существующую дату").assertIsDisplayed()
        composeRule.runOnIdle {
            errors.value = PetProfileFieldErrors(birthDate = PetBirthDateValidationError.FUTURE)
        }
        composeRule.onNodeWithText("Дата рождения не может быть в будущем").assertIsDisplayed()
    }

    @Test
    fun unavailableBreedShowsIdAndCanBeReplacedThenCleared() {
        val unknownId = BreedId("external:dog:very-rare")
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Редкая",
                    species = PetSpecies.DOG,
                    breed = PetBreedSelection.Unavailable(unknownId, PetSpecies.DOG),
                ),
            ),
        )
        val replacement = catalog.search("Доберман", PetSpecies.DOG).single()
        setEditor(state)

        composeRule.onNodeWithText(unknownId.value, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("Доберман")
        composeRule.onNodeWithTag(PetProfileEditorTestTags.breedOption(replacement.id.value))
            .performClick()
        composeRule.runOnIdle { assertEquals(replacement.id, state.value.draft.breed?.id) }
        clearBreedThroughPicker()
        composeRule.runOnIdle { assertNull(state.value.draft.breed) }
    }

    @Test
    fun narrowDialogKeepsLongNameAndLocalizedBreedInsideBounds() {
        val longBreed = catalog.search("", PetSpecies.DOG).maxBy { it.displayName.length }
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Очень длинное имя питомца",
                    species = PetSpecies.DOG,
                    breed = PetBreedSelection.Available(longBreed),
                ),
            ),
        )
        setEditor(state, modifier = Modifier.width(320.dp))

        val dialogBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.Dialog)
            .getUnclippedBoundsInRoot()
        val nameBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.NameField)
            .getUnclippedBoundsInRoot()
        val heightBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField)
            .performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(heightBounds.left >= dialogBounds.left && heightBounds.right <= dialogBounds.right)
        val breedBounds = composeRule.onNodeWithText(longBreed.displayName)
            .performScrollTo()
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        composeRule.runOnIdle {
            assertTrue(nameBounds.left >= dialogBounds.left)
            assertTrue(nameBounds.right <= dialogBounds.right)
            assertTrue(breedBounds.left >= dialogBounds.left)
            assertTrue(breedBounds.right <= dialogBounds.right)
        }
    }

    @Test
    fun largeFontContentScrollsAndSelectionAndClearSemanticsRemainAvailable() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft.create().copy(
                    displayName = "Бим",
                    species = PetSpecies.DOG,
                    sex = PetSex.MALE,
                    breed = unmappedDog,
                    birthDate = PetBirthDateInput.Year("2020"),
                    dogAdultWeightCategory = DogAdultWeightCategory.IV,
                ),
            ),
        )
        setEditor(state, modifier = Modifier.width(320.dp), fontScale = 2f)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.HeightField)
            .performScrollTo().assertIsDisplayed().performTextReplacement("32.5")
        composeRule.runOnIdle { assertEquals("32.5", state.value.draft.heightCm) }
        closeSoftKeyboard()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesDog).performScrollTo()

        composeRule.onNodeWithTag(PetProfileEditorTestTags.Content)
            .assert(hasScrollAction())
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesDog).assertIsSelected()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexMale).assertIsSelected()
        val catBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesCat)
            .assertTextEquals("🐱 Кошка")
            .assertIsNotSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    Role.RadioButton,
                ),
            )
            .getUnclippedBoundsInRoot()
        val dogBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesDog)
            .assertTextEquals("🐶 Собака")
            .getUnclippedBoundsInRoot()
        val maleBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.SexMale)
            .assertTextEquals("Самец")
            .getUnclippedBoundsInRoot()
        val femaleBounds = composeRule.onNodeWithTag(PetProfileEditorTestTags.SexFemale)
            .assertTextEquals("Самка")
            .assertIsNotSelected()
            .getUnclippedBoundsInRoot()
        composeRule.runOnIdle {
            assertEquals(catBounds.right - catBounds.left, dogBounds.right - dogBounds.left)
            assertEquals(maleBounds.right - maleBounds.left, femaleBounds.right - femaleBounds.left)
            assertTrue(catBounds.right <= dogBounds.left)
            assertTrue(maleBounds.right <= femaleBounds.left)
        }
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthClear).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthYear)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            PetProfileEditorTestTags.category(DogAdultWeightCategory.IV),
        ).performScrollTo()
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    Role.RadioButton,
                ),
            )
            .assertIsDisplayed()
    }

    private fun setEditor(
        state: MutableState<PetProfileEditorState>,
        actions: MutableList<PetProfileAction> = mutableListOf(),
        busy: State<Boolean> = mutableStateOf(false),
        errors: State<PetProfileFieldErrors> = mutableStateOf(PetProfileFieldErrors()),
        saveError: State<String?> = mutableStateOf(null),
        modifier: Modifier = Modifier,
        fontScale: Float? = null,
        onSave: () -> Unit = {},
        onDismiss: () -> Unit = {},
        profilePhotoStore: ProfilePhotoStore? = null,
        photoPickerFactory: @androidx.compose.runtime.Composable (
            ProfilePhotoStore,
            (PreparedProfilePhoto) -> Unit,
            (com.palixander.weightogether.profile.ProfilePhotoError) -> Unit,
        ) -> ProfilePhotoPicker = { store, onPrepared, onError ->
            com.palixander.weightogether.profile.rememberProfilePhotoPicker(
                store,
                onPrepared,
                onError,
            )
        },
    ) {
        composeRule.setContent {
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                ScaleSyncTheme {
                    PetProfileEditorDialog(
                        state = state.value,
                        fieldErrors = errors.value,
                        repositoryError = saveError.value,
                        busy = busy.value,
                        breedCatalog = catalog,
                        onAction = { action ->
                            actions += action
                            state.value = PetProfileReducer.reduce(state.value, action)
                        },
                        onSave = onSave,
                        onDismiss = onDismiss,
                        modifier = modifier,
                        profilePhotoStore = profilePhotoStore
                            ?: com.palixander.weightogether.ui.components.currentProfilePhotoStore(),
                        photoPickerFactory = photoPickerFactory,
                    )
                }
            }
            if (fontScale == null) {
                content()
            } else {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) { content() }
            }
        }
    }

    private fun preparedPhoto(store: ProfilePhotoStore): PreparedProfilePhoto {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            bitmap.recycle()
        }.toByteArray()
        return bytes.inputStream().use(store::prepare)
    }
}
