package com.palixander.scalesync

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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PetProfileEditorDialogUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val catalog = PetBreedCatalog()
    private val mixedDog = PetBreedSelection.Available(
        requireNotNull(catalog.search("Метис", PetSpecies.DOG).singleOrNull()),
    )

    @Test
    fun filledEditShowsValuesAndEveryOptionalFieldCanBeCleared() {
        val state = mutableStateOf(
            PetProfileEditorState(
                PetProfileDraft(
                    mode = PetProfileEditorMode.Edit(PetId("luna")),
                    displayName = "Луна",
                    species = PetSpecies.DOG,
                    sex = PetSex.FEMALE,
                    breed = mixedDog,
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
        composeRule.onNodeWithText("Метис").assertExists()

        composeRule.onNodeWithTag(PetProfileEditorTestTags.CategoryClear)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthClear)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexClear)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedClear)
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertNull(state.value.draft.sex)
            assertNull(state.value.draft.breed)
            assertEquals(PetBirthDateInput.Empty, state.value.draft.birthDate)
            assertNull(state.value.draft.dogAdultWeightCategory)
        }
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
        val dog = catalog.search("Danish Mastiff", PetSpecies.DOG)
            .first { "Danish Mastiff" in it.aliases }
        setEditor(state)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedQuery)
            .performTextInput("Danish Mastiff")
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

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthPrecisionYear)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthYear).performTextInput("2020")
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Year("2020"), state.value.draft.birthDate)
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthPrecisionMonth).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthMonth).performTextInput("02")
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Month("2020", "02"), state.value.draft.birthDate)
        }

        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthPrecisionDay).performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthDay).performTextInput("29")
        composeRule.runOnIdle {
            assertEquals(PetBirthDateInput.Day("2020", "02", "29"), state.value.draft.birthDate)
            assertTrue(actions.any { it == PetProfileAction.BirthDateChanged(PetBirthDateInput.Year("")) })
            assertTrue(
                actions.any {
                    it == PetProfileAction.BirthDateChanged(PetBirthDateInput.Month("2020", ""))
                },
            )
            assertTrue(
                actions.any {
                    it == PetProfileAction.BirthDateChanged(PetBirthDateInput.Day("2020", "02", ""))
                },
            )
        }
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
            breed = mixedDog,
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
                    breed = mixedDog,
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
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Save).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.Cancel).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SaveError)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Сохранение профиля питомца")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Сохранение",
                ),
            )
            .assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
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
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedClear)
            .performScrollTo()
            .assertContentDescriptionEquals("Очистить породу питомца")
            .performClick()
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
                    breed = mixedDog,
                    birthDate = PetBirthDateInput.Year("2020"),
                    dogAdultWeightCategory = DogAdultWeightCategory.IV,
                ),
            ),
        )
        setEditor(state, modifier = Modifier.width(320.dp), fontScale = 2f)

        composeRule.onNodeWithTag(PetProfileEditorTestTags.Content)
            .assert(hasScrollAction())
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SpeciesDog)
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            )
        composeRule.onNodeWithTag(PetProfileEditorTestTags.SexMale)
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            )
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedField)
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BreedPicker).assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").performClick()
        composeRule.onNodeWithTag(PetProfileEditorTestTags.BirthClear)
            .performScrollTo()
            .assertContentDescriptionEquals("Очистить дату рождения питомца")
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
}
