package com.palixander.scalesync

import com.palixander.scalesync.ui.text.resolve
import com.palixander.scalesync.ui.text.UiText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartScrollOffset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason
import com.palixander.scalesync.domain.reference.WeightReferenceProvenance
import com.palixander.scalesync.ui.profiles.PetHistoryCallbacks
import com.palixander.scalesync.ui.profiles.PetHistoryContent
import com.palixander.scalesync.ui.profiles.PetHistoryDeleteConfirmation
import com.palixander.scalesync.ui.profiles.PetHistoryMeasurementUi
import com.palixander.scalesync.ui.profiles.PetHistoryUiState
import com.palixander.scalesync.ui.profiles.PetProfileScreen
import com.palixander.scalesync.ui.profiles.PetProfileScreenTestTags
import com.palixander.scalesync.ui.profiles.PetWeightChartTestTags
import com.palixander.scalesync.ui.profiles.PetProfileSummary
import com.palixander.scalesync.ui.profiles.PetProfileSummaryItem
import com.palixander.scalesync.ui.profiles.PetWeightChartMetric
import com.palixander.scalesync.ui.profiles.PetHistoryReferencePoint
import com.palixander.scalesync.ui.profiles.PetHistoryReferenceSegment
import com.palixander.scalesync.ui.profiles.PetHistoryWeightReference
import com.palixander.scalesync.ui.profiles.PetHistoryBreedReference
import com.palixander.scalesync.ui.profiles.PetHistoryBreedChartValue
import com.palixander.scalesync.ui.profiles.PetHistoryBreedSource
import com.palixander.scalesync.ui.profiles.PetHistoryBreedReferenceTimelinePoint
import com.palixander.scalesync.ui.reference.ReferenceSourceLauncher
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class PetHistoryScreenUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun measurementCardAddsOnlyAccessibleEditIconBesideExistingDelete() {
        val measurement = PetHistoryMeasurementUi("one", 0, "5 сентября 2026, 18:24", 4.125, "4,125 кг")
        var edited: String? = null
        setScreen(
            state(PetHistoryContent.Single(measurement)),
            callbacks().copy(editMeasurement = { edited = it }),
        )

        composeRule.onNodeWithTag(PetProfileScreenTestTags.editMeasurement("one"))
            .assertContentDescriptionEquals("Изменить измерение 5 сентября 2026, 18:24, 4,125 кг")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.deleteMeasurement("one"))
            .assertHeightIsAtLeast(48.dp)
        composeRule.runOnIdle { assertEquals("one", edited) }
    }

    @Test fun successfulSaveTargetIsScrolledFocusedAndConsumedOnce() {
        val measurements = (1..12).map { index ->
            PetHistoryMeasurementUi(
                id = "measurement-$index",
                measuredAtEpochSecond = index.toLong(),
                measuredAtText = "5 сентября 2026, 18:$index",
                weightKg = 4.0 + index / 100.0,
                weightText = "4,$index кг",
            )
        }
        var handled = 0
        var screenState by mutableStateOf(
            state(PetHistoryContent.Multiple(measurements)).copy(
                scrollToMeasurementId = "measurement-12",
            ),
        )
        composeRule.setContent {
            PetProfileScreen(
                state = screenState,
                callbacks = callbacks().copy(onScrollToMeasurementHandled = {
                    handled++
                    screenState = screenState.copy(scrollToMeasurementId = null)
                }),
                contentPadding = PaddingValues(),
                onStartMeasurement = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-12"))
            .assertIsDisplayed()
            .assertIsFocused()
        composeRule.runOnIdle { assertEquals(1, handled) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, handled) }
    }

    @Test fun upToTenMeasurementsAreShownWithoutExpansionAction() {
        val measurements = (1..10).map { row("measurement-$it") }

        setScreen(state(PetHistoryContent.Multiple(measurements)))

        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-10"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining).assertDoesNotExist()
    }

    @Test fun moreThanTenMeasurementsShowNewestTenUntilAccessibleExpansion() {
        val measurements = (1..12).map { row("measurement-$it") }

        setScreen(state(PetHistoryContent.Multiple(measurements)))

        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-10"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-11"))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining)
            .assertContentDescriptionEquals("Показать остальные измерения")
            .assertHeightIsAtLeast(48.dp)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-12"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining).assertDoesNotExist()
    }

    @Test fun changingPetCollapsesPreviouslyExpandedMeasurements() {
        val measurements = (1..11).map { row("measurement-$it") }
        var screenState by mutableStateOf(state(PetHistoryContent.Multiple(measurements)))
        composeRule.setContent {
            PetProfileScreen(screenState, callbacks(), PaddingValues(), {})
        }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-11"))
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle {
            val nextId = PetId("another-pet")
            screenState = screenState.copy(
                petId = nextId,
                pet = screenState.pet?.copy(id = nextId, displayName = "Луна"),
            )
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("measurement-11"))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test fun loadingAndEmptyStatesDoNotOfferMeasurementExpansion() {
        setScreen(state(PetHistoryContent.Multiple((1..11).map { row("measurement-$it") })).copy(isLoading = true))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Loading).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining).assertDoesNotExist()

        setScreen(state(PetHistoryContent.Empty))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Empty).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ShowRemaining).assertDoesNotExist()
    }

    @Test fun weightEditorIsScrollableValidatesInputAndExposesReadOnlyTime() {
        var input by mutableStateOf("4.12")
        val editor = com.palixander.scalesync.ui.profiles.PetWeightEditorState(
            measurementId = "one",
            petName = "Очень длинное имя питомца",
            measuredAtText = "5 сентября 2026, 18:24",
            originalWeightKg = 4.12,
            weightInput = input,
        )
        composeRule.setContent {
            Box(Modifier.width(320.dp)) {
                PetProfileScreen(
                    state(PetHistoryContent.Empty).copy(weightEditor = editor.copy(weightInput = input)),
                    callbacks().copy(changeEditedWeight = { input = it }),
                    PaddingValues(),
                    {},
                )
            }
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.WeightEditor).assert(hasScrollAction())
        composeRule.onNodeWithText("5 сентября 2026, 18:24").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.WeightSave).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.WeightInput).performTextClearance()
        composeRule.onNodeWithText(
            "Введите положительный вес от 0,001 кг, максимум 3 знака после запятой",
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.WeightInput).performTextInput("4,125")
        composeRule.onNodeWithTag(PetProfileScreenTestTags.WeightSave).assertIsEnabled()
    }

    @Test fun emptyHistoryOffersManualWeightForItsPet() {
        var additions = 0
        setScreen(state(PetHistoryContent.Empty), PetHistoryCallbacks({}, { _, _ -> }, onAddWeightRequested = { additions++ }))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.AddWeight).performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, additions) }
    }

    @Test fun populationReferenceUsesApprovedLegendSemanticsAndPrimaryPublicationAtNarrowLargeText() {
        val opened = mutableListOf<String>()
        val reference = availableReference("Справочные данные по популяции").copy(
            basis = ReferenceBasis.POPULATION,
            publicationUrl = "https://doi.org/10.1371/journal.pone.0182064",
            isFittedPopulationPercentiles = true,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(
                        state(PetHistoryContent.Empty).copy(weightReference = reference),
                        callbacks(),
                        PaddingValues(),
                        {},
                        sourceLauncher = ReferenceSourceLauncher { url -> opened += url; true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("▰ Типичный диапазон веса").assertIsDisplayed()
        composeRule.onNodeWithText("— P50").assertIsDisplayed()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assert(hasContentDescription("Сведения справочные и не оценивают здоровье питомца", substring = true))
        composeRule.onNodeWithTag(PetWeightChartTestTags.Disclosure).performScrollTo().performClick()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Publication).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("https://doi.org/10.1371/journal.pone.0182064"), opened)
        }
        listOf("нормальный", "идеальный", "медицинский", "целевой").forEach { forbidden ->
            composeRule.onNodeWithText(forbidden, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }

    @Test fun emptyHistoryStillOffersExactPetMeasurementAndPeriodFilter() {
        var starts = 0
        var selected: ChartRangePreset? = null
        setScreen(state(PetHistoryContent.Empty), { selected = it }) { starts++ }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.StartMeasurement).performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.preset(ChartRangePreset.LAST_30_DAYS)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(ChartRangePreset.LAST_30_DAYS, selected)
        }
    }

    @Test fun petPeriodsRemainSelectableAndSingleLineAtNarrowWidthWithLargeText() {
        val selected = mutableListOf<ChartRangePreset>()
        var screenState by mutableStateOf(state(PetHistoryContent.Empty))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(
                        screenState,
                        callbacks().copy(selectRangePreset = { preset ->
                            selected += preset
                            screenState = screenState.copy(rangePreset = preset)
                        }),
                        PaddingValues(),
                        {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.preset(ChartRangePreset.LAST_7_DAYS))
            .assertDoesNotExist()
        composeRule.onNodeWithText("7 дней").assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.PeriodFilter).assert(hasScrollAction())
        val periods = listOf(
            ChartRangePreset.ALL to "Всё",
            ChartRangePreset.LAST_30_DAYS to "30 дней",
            ChartRangePreset.LAST_3_MONTHS to "3 месяца",
        )
        periods.forEach { (preset, title) ->
            composeRule.onNodeWithTag(PetProfileScreenTestTags.preset(preset))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
                .assertIsSelected()
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(title, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            composeRule.runOnIdle {
                assertEquals(1, layouts.size)
                assertEquals(1, layouts.single().lineCount)
                assertFalse(layouts.single().hasVisualOverflow)
            }
        }
        composeRule.runOnIdle { assertEquals(periods.map { it.first }, selected) }
    }

    @Test fun emptyProfileSummaryIsExplicitAndEditTargetsExactPetAccessibly() {
        var edited: Pet? = null
        val screenState = state(PetHistoryContent.Empty)
        composeRule.setContent {
            PetProfileScreen(
                state = screenState,
                callbacks = callbacks(),
                contentPadding = PaddingValues(),
                onStartMeasurement = {},
                onEditPet = { edited = it },
            )
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.Summary).assertIsDisplayed()
        composeRule.onNodeWithText("Дополнительные данные не заполнены").assertIsDisplayed()
        composeRule.onNode(
            hasContentDescription("Дополнительные данные не заполнены", substring = true),
        ).assertExists()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Edit)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Изменить данные питомца Барсик")
            .performClick()

        composeRule.runOnIdle { assertEquals(screenState.pet, edited) }
    }

    @Test fun filledSummaryListsLabeledValuesAndActualSemantics() {
        val summary = PetProfileSummary(
            listOf(
                PetProfileSummaryItem(UiText.Raw("Пол"), UiText.Raw("Самка")),
                PetProfileSummaryItem(UiText.Raw("Порода"), UiText.Raw("Метис")),
                PetProfileSummaryItem(UiText.Raw("Дата рождения"), UiText.Raw("02.2020 (месяц)")),
                PetProfileSummaryItem(UiText.Raw("Весовая категория"), UiText.Raw("IV — 15–30 кг")),
            ),
        )
        setScreen(
            state(PetHistoryContent.Empty).copy(
                pet = state(PetHistoryContent.Empty).pet?.copy(sex = PetSex.FEMALE),
                profileSummary = summary,
            ),
        )

        summary.items.forEach { item ->
            composeRule.onNodeWithText(item.label.resolve(composeRule.activity.resources)).assertExists()
            composeRule.onNodeWithText(item.value.resolve(composeRule.activity.resources)).assertExists()
        }
        composeRule.onNode(
            hasContentDescription("Дата рождения: 02.2020 (месяц)", substring = true),
        ).assertExists()
    }

    @Test fun unknownLongBreedWrapsAtNarrowWidthLargeFontAndRemainsScrollable() {
        val unavailable = "Недоступна: retired:cat:" + "очень-длинный-идентификатор-".repeat(5)
        val screenState = state(PetHistoryContent.Empty).copy(
            profileSummary = PetProfileSummary(
                listOf(PetProfileSummaryItem(UiText.Raw("Порода"), UiText.Raw(unavailable))),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(screenState, callbacks(), PaddingValues(), {})
                }
            }
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.shell(screenState.petId.value))
            .assert(hasScrollAction())
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Summary).assertIsDisplayed()
        composeRule.onNode(
            hasContentDescription(unavailable, substring = true),
        ).assertExists()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Edit)
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
    }

    @Test fun oneAndMultipleMeasurementsRenderStableRows() {
        val first = row("one")
        val second = row("two")
        setScreen(state(PetHistoryContent.Single(first)))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.runOnIdle { }
        setScreen(state(PetHistoryContent.Multiple(listOf(first, second))))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("two")).assertIsDisplayed()
    }

    @Test fun dedicatedPetChartShowsSingletonAsPointAndRemainsForMultipleMeasurements() {
        val first = row("one")
        val second = row("two")
        var screenState by mutableStateOf(
            state(PetHistoryContent.Single(first)).copy(
                series = ChartSeries(PetWeightChartMetric, listOf(ChartPoint(1_000L, 4.2))),
            ),
        )
        composeRule.setContent {
            PetProfileScreen(
                screenState,
                callbacks(),
                PaddingValues(),
                onStartMeasurement = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart).assertIsDisplayed()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Unavailable).assertIsDisplayed()

        composeRule.runOnIdle {
            screenState = state(PetHistoryContent.Multiple(listOf(first, second))).copy(
                series = ChartSeries(
                    PetWeightChartMetric,
                    listOf(ChartPoint(1_000L, 4.2), ChartPoint(2_000L, 4.3)),
                ),
            )
        }

        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart).assertIsDisplayed()
    }

    @Test fun availableReferenceRendersWithoutMeasurementsAndExplainsBreedSourceAndLimits() {
        val reference = availableReference("Эталон по породе")
        setScreen(state(PetHistoryContent.Empty).copy(weightReference = reference))

        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assertIsDisplayed()
            .assert(hasContentDescription("Измерений нет", substring = true))
        composeRule.onNodeWithText("Эталон по породе · Возраст: 100–102 дн.").assertIsDisplayed()
        composeRule.onNodeWithText("Источник: Test veterinary source").assertDoesNotExist()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Disclosure)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свернуто"))
            .performClick()
        composeRule.onNodeWithText("Источник: Test veterinary source").assertIsDisplayed()
        composeRule.onNodeWithText("Ограничение: Только здоровые животные").assertIsDisplayed()
        composeRule.onNodeWithTag(PetWeightChartTestTags.ReferenceDetails)
            .assert(hasContentDescription("Источник: Test veterinary source", substring = true))
            .assert(hasContentDescription("Ограничение: Только здоровые животные", substring = true))
            .assert(hasContentDescription("не ставит диагноз", substring = true))
    }

    @Test fun failedReferenceSourceKeepsDisclosureOpenNamesSourceAndAnnouncesError() {
        val reference = availableReference("Эталон по породе").copy(
            publicationUrl = "https://example.com/reference",
        )
        composeRule.setContent {
            PetProfileScreen(
                state(PetHistoryContent.Empty).copy(weightReference = reference),
                callbacks(),
                PaddingValues(),
                {},
                sourceLauncher = ReferenceSourceLauncher { false },
            )
        }

        composeRule.onNodeWithTag(PetWeightChartTestTags.Disclosure).performClick()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Publication).performScrollTo().performClick()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Disclosure)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развернуто"))
        composeRule.onNodeWithTag(PetWeightChartTestTags.PublicationError)
            .assertTextEquals("Не удалось открыть источник «Test veterinary source».")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test fun unavailableWeightReferenceIsSinglePoliteLiveRegion() {
        setScreen(state(PetHistoryContent.Empty))

        composeRule.onAllNodesWithTag(PetWeightChartTestTags.Unavailable).assertCountEquals(1)
        composeRule.onNodeWithTag(PetWeightChartTestTags.Unavailable)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun modelledCatBreedUsesRussianHonestLegendAndExplanation() {
        val reference = availableReference("Эталон по породе").copy(
            provenance = WeightReferenceProvenance.BREED_CURVE,
            provenanceExplanation = UiText.Raw("Показан модельный возрастной диапазон выбранной породы, а не наблюдаемая породная кривая."),
            constraints = listOf("Модель основана на популяционной кривой кошек того же пола"),
        )

        setScreen(state(PetHistoryContent.Empty).copy(weightReference = reference))

        composeRule.onNodeWithText("▰ Светло-зелёная зона — модельный породный диапазон").assertIsDisplayed()
        composeRule.onNodeWithText("— Центр породной модели").assertIsDisplayed()
        composeRule.onNodeWithText("— Нижняя граница эталона").assertDoesNotExist()
        composeRule.onNodeWithText("— Нижняя медианная граница").assertDoesNotExist()
        composeRule.onNodeWithText("— Верхняя медианная граница").assertDoesNotExist()
        composeRule.onNodeWithText("— Верхняя граница эталона").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Светло-зелёная зона показывает модельный породный диапазон, тонкая линия — центр модели. Это расчётная модель, а не наблюдаемая кривая роста породы.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assert(hasContentDescription("модельный породный диапазон", substring = true))
            .assert(hasContentDescription("четырьмя линиями", substring = true).not())
    }

    @Test fun exactBirthObservationUsesWhiskerLegendWithoutAFalseBand() {
        val date = LocalDate.of(2026, 8, 1)
        val reference = availableReference("Наблюдение по породе: среднее ± одно стандартное отклонение").copy(
            provenance = WeightReferenceProvenance.BREED_EXACT_OBSERVATION,
            segments = referenceSegments(
                listOf(PetHistoryReferencePoint(date, 0.1, 0.12, 0.12, 0.14)),
                provenance = WeightReferenceProvenance.BREED_EXACT_OBSERVATION,
            ),
        )

        setScreen(state(PetHistoryContent.Empty).copy(weightReference = reference))

        composeRule.onNodeWithText("↕ Диапазон наблюдения породы в дату рождения").assertIsDisplayed()
        composeRule.onNodeWithText("● Средний вес породы в дату рождения").assertIsDisplayed()
        composeRule.onNodeWithText("▰ Светло-зелёная зона — модельный породный диапазон").assertDoesNotExist()
    }

    @Test fun breedLayerRendersWithoutMeasurementsAndExposesShapeIndependentSemanticsAtNarrowLargeText() {
        val breedReference = PetHistoryBreedReference.Available(
            breedName = "Американский стаффордширский терьер",
            ageLabel = "6 месяцев",
            valueLabels = listOf("Диапазон: 8,2–10,4 кг", "Среднее: 9,3 кг"),
            sourceKindLabel = "наблюдаемая выборка",
            sexLabel = "Самец",
            partialDateDisclosure = null,
            accessibilityLabel = "Ориентиры породы Американский стаффордширский терьер",
            chartValues = listOf(
                PetHistoryBreedChartValue.Interval(
                    8.2,
                    10.4,
                    9.3,
                    "Диапазон",
                    "Американский стаффордширский терьер. Возраст источника: 6 месяцев. Диапазон: 8,2–10,4 кг. Тип источника: наблюдаемая выборка.",
                ),
            ),
            source = PetHistoryBreedSource(
                "Исследование",
                "https://example.com",
                2024,
                "наблюдаемая выборка",
                null,
                null,
                null,
                null,
                emptyList(),
            ),
            details = emptyList(),
        )
        val firstDate = LocalDate.of(2026, 8, 1)
        val secondDate = LocalDate.of(2026, 8, 27)
        val screenState = state(PetHistoryContent.Empty).copy(
            breedReference = breedReference,
            breedReferenceTimeline = listOf(firstDate, secondDate).map { date ->
                PetHistoryBreedReferenceTimelinePoint(
                    date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                    date,
                    breedReference.chartValues,
                )
            },
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(screenState, callbacks(), PaddingValues(), {})
                }
            }
        }

        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assertIsDisplayed()
            .assert(hasContentDescription("Светло-зелёная зона — породный диапазон", substring = true))
        composeRule.onNodeWithText("▰ Светло-зелёная зона — породный диапазон; тонкие линии — его границы").assertIsDisplayed()
        composeRule.onNodeWithText("│ Породный диапазон · 6 месяцев").assertDoesNotExist()
        composeRule.onNodeWithText("◆ Породное среднее или медиана · 6 месяцев").assertDoesNotExist()
    }

    @Test fun availableBreedSuppressesUnavailableCategoryExplanationFromUiAndAccessibility() {
        val explanation = "Эталон недоступен: укажите ожидаемую весовую категорию взрослой собаки."
        val screenState = state(PetHistoryContent.Empty).copy(
            weightReference = PetHistoryWeightReference.Unavailable(
                WeightReferenceUnavailableReason.MissingDogAdultWeight,
                com.palixander.scalesync.ui.text.UiText.Raw(explanation),
            ),
            breedReference = availableBreedReference(),
        )

        setScreen(screenState)

        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assertIsDisplayed()
            .assert(hasContentDescription("Ориентиры породы", substring = true))
        composeRule.onNodeWithTag(PetWeightChartTestTags.Unavailable).assertDoesNotExist()
        composeRule.onNodeWithText(explanation).assertDoesNotExist()
        composeRule.onNode(hasContentDescription(explanation, substring = true)).assertDoesNotExist()
    }

    @Test fun singletonReferenceExposesAllBoundsAsAccessibleSelectedState() {
        val reference = availableReference("Эталон по породе").copy(
            segments = referenceSegments(
                listOf(PetHistoryReferencePoint(LocalDate.of(2026, 8, 29), 2.0, 3.0, 4.0, 5.0)),
            ),
        )
        val screenState = state(PetHistoryContent.Empty).copy(weightReference = reference)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(screenState, callbacks(), PaddingValues(), {})
                }
            }
        }

        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assertIsDisplayed()
            .assert(hasStateDescriptionContaining("29.08.2026"))
            .assert(hasStateDescriptionContaining("Нижняя граница: 2,00 кг"))
            .assert(hasStateDescriptionContaining("Медиана: 3,00 кг–4,00 кг"))
            .assert(hasStateDescriptionContaining("Верхняя граница: 5,00 кг"))
    }

    @Test fun accessibleChartActionSelectsNextReferenceDateAndUpdatesReadableState() {
        setScreen(state(PetHistoryContent.Empty).copy(weightReference = availableReference("Эталон по породе")))

        val chart = composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
        chart.assert(hasStateDescriptionContaining("01.08.2026"))
        chart.performClick()
        chart
            .assert(hasStateDescriptionContaining("27.08.2026"))
            .assert(hasStateDescriptionContaining("Нижняя граница: 3,20 кг"))
            .assert(hasStateDescriptionContaining("Верхняя граница: 4,70 кг"))
    }

    @Test fun petWeightChartDragScrollsViewportWithoutMovingListAndKeepsMarkerSelection() {
        val reference = availableReference("Эталон по породе").copy(
            segments = referenceSegments(
                (0..90 step 5).map { day ->
                    PetHistoryReferencePoint(
                        LocalDate.of(2026, 7, 1).plusDays(day.toLong()),
                        3.0 + day / 100.0,
                        3.5 + day / 100.0,
                        4.0 + day / 100.0,
                        4.5 + day / 100.0,
                    )
                },
            ),
        )
        setScreen(state(PetHistoryContent.Empty).copy(weightReference = reference))

        val chart = composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assertIsDisplayed()
            .assert(hasStateDescriptionContaining("01.07.2026"))
        val initialMarker = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val initialOffset = chart.fetchSemanticsNode().config[ChartScrollOffset]
        val initialTop = chart.getUnclippedBoundsInRoot().top

        chart.performTouchInput { swipeLeft(durationMillis = 500) }
        composeRule.waitForIdle()

        assertNotEquals(initialOffset, chart.fetchSemanticsNode().config[ChartScrollOffset])
        assertEquals(initialTop.value, chart.getUnclippedBoundsInRoot().top.value, 1f)
        assertEquals(initialMarker, chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun categoryReferenceAndMeasurementCountsHaveExplicitSemantics() {
        val first = row("one")
        val second = row("two")
        val reference = availableReference("Эталон по весовой категории")

        setScreen(state(PetHistoryContent.Single(first)).copy(
            series = ChartSeries(PetWeightChartMetric, listOf(ChartPoint(1_000L, 4.2))),
            weightReference = reference,
        ))
        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assert(hasContentDescription("Измерений: 1", substring = true))
            .assert(hasContentDescription("Эталон по весовой категории", substring = true))

        setScreen(state(PetHistoryContent.Multiple(listOf(first, second))).copy(
            series = ChartSeries(
                PetWeightChartMetric,
                listOf(ChartPoint(1_000L, 4.2), ChartPoint(2_000L, 4.3)),
            ),
            weightReference = reference,
        ))
        composeRule.onNodeWithTag(PetWeightChartTestTags.Chart)
            .assert(hasContentDescription("Измерений: 2", substring = true))
    }

    @Test fun unavailableReferenceShowsConcreteReasonAndRemainsReadableAtNarrowLargeText() {
        val explanation = "Эталон недоступен: укажите ожидаемую весовую категорию взрослой собаки."
        val screenState = state(PetHistoryContent.Empty).copy(
            weightReference = PetHistoryWeightReference.Unavailable(
                WeightReferenceUnavailableReason.MissingDogAdultWeight,
                com.palixander.scalesync.ui.text.UiText.Raw(explanation),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    PetProfileScreen(screenState, callbacks(), PaddingValues(), {})
                }
            }
        }

        composeRule.onNodeWithTag(PetProfileScreenTestTags.shell(screenState.petId.value))
            .assert(hasScrollAction())
        composeRule.onNodeWithTag(PetWeightChartTestTags.Unavailable).assertIsDisplayed()
        composeRule.onNodeWithText(explanation).assertIsDisplayed()
    }

    @Test fun deleteActionOpensDialogForExactRowAndCancelDoesNotConfirm() {
        val first = row("one")
        val second = row("two")
        var requested: String? = null
        var confirmations = 0
        var dismissals = 0
        val callbacks = callbacks(
            requestDelete = { requested = it },
            confirmDelete = { confirmations++ },
            dismissDelete = { dismissals++ },
        )
        setScreen(state(PetHistoryContent.Multiple(listOf(first, second))), callbacks)

        composeRule.onNodeWithTag(PetProfileScreenTestTags.deleteMeasurement("two"))
            .assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("two", requested) }

        setScreen(
            state(PetHistoryContent.Multiple(listOf(first, second))).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), second),
            ),
            callbacks,
        )
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteDialog).assertIsDisplayed()
        composeRule.onNodeWithText("27.08.2026 15:00 · 4,20 кг").assertIsDisplayed()
        composeRule.onNodeWithText("Измерение будет удалено без возможности восстановления.").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteCancel).performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, dismissals)
        }
    }

    @Test fun confirmIsSubmittedOnceAndLoadingDisablesDialogActionsAccessibly() {
        val selected = row("one")
        var confirmations = 0
        var screenState by mutableStateOf(
            state(PetHistoryContent.Single(selected)).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), selected),
            ),
        )
        val callbacks = callbacks(confirmDelete = {
            confirmations++
            screenState = screenState.copy(
                deleteConfirmation = screenState.deleteConfirmation?.copy(isDeleting = true),
            )
        })
        composeRule.setContent {
            PetProfileScreen(
                screenState,
                callbacks,
                PaddingValues(),
                onStartMeasurement = {},
            )
        }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).performClick()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).assertIsNotEnabled()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteCancel).assertIsNotEnabled()
        composeRule.onNodeWithText("Удаление…").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun deleteErrorKeepsRowsOffersRetryAndCanBeDismissed() {
        val selected = row("one")
        var confirmations = 0
        val callbacks = callbacks(confirmDelete = { confirmations++ })
        setScreen(
            state(PetHistoryContent.Single(selected)).copy(
                deleteConfirmation = PetHistoryDeleteConfirmation(PetId("pet-exact"), selected),
                actionErrorMessage = com.palixander.scalesync.ui.text.UiText.Raw("Не удалось сохранить изменения"),
            ),
            callbacks,
        )

        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithText("Не удалось сохранить изменения").assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.DeleteConfirm).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }

        var errorDismissals = 0
        setScreen(
            state(PetHistoryContent.Single(selected)).copy(actionErrorMessage = com.palixander.scalesync.ui.text.UiText.Raw("Не удалось сохранить изменения")),
            callbacks(dismissActionError = { errorDismissals++ }),
        )
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ActionError).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.ActionErrorDismiss).performClick()
        composeRule.runOnIdle { assertEquals(1, errorDismissals) }
    }

    @Test fun updatedStateRemovesDeletedRowAndShowsEmptyState() {
        val selected = row("one")
        var screenState by mutableStateOf(state(PetHistoryContent.Single(selected)))
        composeRule.setContent {
            PetProfileScreen(
                screenState,
                callbacks(),
                PaddingValues(),
                onStartMeasurement = {},
            )
        }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertIsDisplayed()

        composeRule.runOnIdle { screenState = state(PetHistoryContent.Empty) }
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertDoesNotExist()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.Empty).assertIsDisplayed()
    }

    @Test fun missingPetIsSafeAndDoesNotExposeHistoryRows() {
        setScreen(state(PetHistoryContent.Empty).copy(pet = null, isNotFound = true))
        composeRule.onNodeWithTag(PetProfileScreenTestTags.NotFound).assertIsDisplayed()
        composeRule.onNodeWithTag(PetProfileScreenTestTags.measurement("one")).assertDoesNotExist()
    }

    @Test fun loadingNotFoundAndErrorWithoutPetNeverExposeSummaryOrEdit() {
        val unavailableStates = listOf(
            state(PetHistoryContent.Empty).copy(pet = null, isLoading = true),
            state(PetHistoryContent.Empty).copy(pet = null, isNotFound = true),
            state(PetHistoryContent.Empty).copy(pet = null, errorMessage = com.palixander.scalesync.ui.text.UiText.Raw("Ошибка загрузки")),
        )

        unavailableStates.forEach { unavailable ->
            setScreen(unavailable)
            composeRule.onNodeWithTag(PetProfileScreenTestTags.Summary).assertDoesNotExist()
            composeRule.onNodeWithTag(PetProfileScreenTestTags.Edit).assertDoesNotExist()
        }
    }

    private fun setScreen(
        state: PetHistoryUiState,
        onPreset: (ChartRangePreset) -> Unit = {},
        onStart: () -> Unit = {},
    ) = composeRule.setContent {
        PetProfileScreen(state, PetHistoryCallbacks(onPreset, { _, _ -> }), PaddingValues(), onStart)
    }

    private fun setScreen(
        state: PetHistoryUiState,
        callbacks: PetHistoryCallbacks,
    ) = composeRule.setContent {
        PetProfileScreen(
            state,
            callbacks,
            PaddingValues(),
            onStartMeasurement = {},
        )
    }

    private fun callbacks(
        requestDelete: (String) -> Unit = {},
        confirmDelete: () -> Unit = {},
        dismissDelete: () -> Unit = {},
        dismissActionError: () -> Unit = {},
    ) = PetHistoryCallbacks(
        selectRangePreset = {},
        setDateRange = { _, _ -> },
        requestDelete = requestDelete,
        confirmDelete = confirmDelete,
        dismissDelete = dismissDelete,
        dismissActionError = dismissActionError,
    )

    private fun state(content: PetHistoryContent): PetHistoryUiState {
        val id = PetId("pet-exact")
        val now = Instant.parse("2026-08-27T10:00:00Z")
        return PetHistoryUiState(
            petId = id,
            pet = Pet(id, "Барсик", createdAt = now, updatedAt = now),
            startDate = LocalDate.of(2026, 8, 1),
            endDateInclusive = LocalDate.of(2026, 8, 27),
            rangePreset = ChartRangePreset.LAST_30_DAYS,
            content = content,
            series = ChartSeries(PetWeightChartMetric, emptyList()),
            isLoading = false,
        )
    }

    private fun row(id: String) = PetHistoryMeasurementUi(id, 1, "27.08.2026 15:00", 4.2, "4,20 кг")

    private fun availableReference(basisLabel: String) = PetHistoryWeightReference.Available(
        basis = if (basisLabel.contains("породе")) ReferenceBasis.BREED else ReferenceBasis.WEIGHT_CATEGORY,
        segments = referenceSegments(
            listOf(
                PetHistoryReferencePoint(LocalDate.of(2026, 8, 1), 3.0, 3.5, 4.0, 4.5),
                PetHistoryReferencePoint(LocalDate.of(2026, 8, 27), 3.2, 3.7, 4.2, 4.7),
            ),
        ),
        approximate = false,
        ageLabel = com.palixander.scalesync.ui.text.UiText.Raw("Возраст: 100–102 дн."),
        basisLabel = com.palixander.scalesync.ui.text.UiText.Raw(basisLabel),
        sourceLabel = com.palixander.scalesync.ui.text.UiText.Raw("Источник: Test veterinary source"),
        citation = "Test veterinary source",
        license = "CC BY 4.0",
        constraints = listOf("Только здоровые животные"),
        accessibilityLabel = com.palixander.scalesync.ui.text.UiText.Raw("$basisLabel. Возраст: 100–102 дн. Источник: Test veterinary source. Лицензия: CC BY 4.0."),
    )

    private fun referenceSegments(
        vararg points: List<PetHistoryReferencePoint>,
        provenance: WeightReferenceProvenance = WeightReferenceProvenance.POPULATION,
    ) = points.mapIndexed { index, segmentPoints ->
        PetHistoryReferenceSegment(
            profileId = "test-$index", sourceId = "test", provenance = provenance,
            citation = "Test veterinary source", license = "CC BY 4.0", publicationUrl = null,
            points = segmentPoints,
        )
    }

    private fun availableBreedReference() = PetHistoryBreedReference.Available(
        breedName = "Американский стаффордширский терьер",
        ageLabel = "6 месяцев",
        valueLabels = listOf("Диапазон: 8,2–10,4 кг"),
        sourceKindLabel = "наблюдаемая выборка",
        sexLabel = "Самец",
        partialDateDisclosure = null,
        accessibilityLabel = "Ориентиры породы Американский стаффордширский терьер",
        chartValues = listOf(
            PetHistoryBreedChartValue.Interval(
                8.2,
                10.4,
                null,
                "Диапазон",
                "Американский стаффордширский терьер. Возраст источника: 6 месяцев. Диапазон: 8,2–10,4 кг.",
            ),
        ),
        source = PetHistoryBreedSource(
            "Исследование",
            "https://example.com",
            2024,
            "наблюдаемая выборка",
            null,
            null,
            null,
            null,
            emptyList(),
        ),
        details = emptyList(),
    )
}

private fun hasStateDescriptionContaining(text: String) = SemanticsMatcher(
    "State description contains '$text'",
) { node ->
    SemanticsProperties.StateDescription in node.config &&
        node.config[SemanticsProperties.StateDescription].contains(text)
}
