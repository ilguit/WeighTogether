package com.palixander.weightogether.ui.reference

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.palixander.weightogether.R
import com.palixander.weightogether.core.BodyMetric
import com.palixander.weightogether.core.ReferenceClassifier
import com.palixander.weightogether.core.ReferenceContext
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.measurements.MeasurementUiValues
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReferencePresentationComponentsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resourcesResolveAllApprovedNamesUnitsHelpAndSingleSourceMappings() {
        val resources = composeRule.activity.resources
        val expectedNames = listOf(
            "Вес", "Импеданс", "BMI", "Жир", "Масса жира", "Вода", "Масса воды",
            "Белок", "Масса белка", "Безжировая масса", "Мышечная масса",
            "Скелетная мышечная масса", "Костная масса", "Висцеральный жир",
            "Основной обмен", "Возраст тела",
        )
        val expectedUnits = listOf(
            "кг", "Ом", "кг/м²", "%", "кг", "%", "кг", "%", "кг", "кг", "кг",
            "кг", "кг", "уровень", "ккал/сут", "лет",
        )

        assertEquals(expectedNames, ReferenceMetricCatalog.metrics.map { resources.getString(it.nameRes) })
        assertEquals(expectedUnits, ReferenceMetricCatalog.metrics.map { resources.getString(it.visibleUnitRes) })
        ReferenceMetricCatalog.metrics.forEach { definition ->
            val help = definition.help
            listOf(help.meaningRes, help.calculationRes, help.dependenciesRes, help.limitationsRes, help.sourceNameRes)
                .forEach { assertTrue(resources.getString(it).isNotBlank()) }
            assertTrue(resources.getString(help.sourceKind.actionLabelRes).isNotBlank())
            assertTrue(help.sourceUrl.startsWith("https://"))
        }
        assertEquals("Набор норм: ScaleSync 1", resources.getString(R.string.reference_set_label))
        assertEquals(
            "Поле не совпадает строго с клинической fat-free mass. " +
                "Внешние FFMI-пороги к нему не применяются; технический clamp не является нормой.",
            resources.getString(R.string.reference_help_lean_body_mass_limitations),
        )
    }

    @Test
    fun formatterSpeaksTheSameRoundedNumberAndTheFullZoneList() {
        val factory = ReferencePresentationFactory(composeRule.activity.resources, Locale.forLanguageTag("ru-RU"))
        val values = values().copy(weightKg = 75.0, bmi = 25.04)
        val readings = values.toReferenceReadings()
        val context = ReferenceContext(
            measurementDate = LocalDate.of(2026, 8, 30),
            birthDate = LocalDate.of(1996, 8, 30),
            sex = Sex.MALE,
            heightCm = 173.2,
            weightKg = values.weightKg,
            impedanceOhm = values.impedanceOhm,
        )
        val interpretation = ReferenceClassifier().classifyAll(readings, context)

        val bmi = factory.createAll(readings, interpretation).single { it.definition.metric == BodyMetric.BMI }

        assertEquals("25,0", bmi.visualNumber)
        assertTrue(requireNotNull(bmi.spokenValue).startsWith("25,0 "))
        assertTrue(bmi.accessibilityDescription.contains("25,0 "))
        assertEquals(5, bmi.zones.size)
        bmi.zones.forEach { zone ->
            assertTrue(bmi.accessibilityDescription.contains(zone.label))
            assertTrue(bmi.accessibilityDescription.contains(zone.spokenRange))
        }

        val impedance = factory.createAll(readings, interpretation)
            .single { it.definition.metric == BodyMetric.IMPEDANCE }
        assertTrue(impedance.zones.isEmpty())
        assertFalse(impedance.accessibilityDescription.contains("Зоны:"))
    }

    @Test
    fun spokenUnitGrammarUsesTheDisplayedRoundedNumber() {
        val factory = ReferencePresentationFactory(composeRule.activity.resources, Locale.forLanguageTag("ru-RU"))
        val definition = ReferenceMetricCatalog.byMetric.getValue(BodyMetric.WEIGHT)
        val context = ReferenceContext(
            measurementDate = LocalDate.of(2026, 8, 30),
            birthDate = LocalDate.of(1996, 8, 30),
            sex = Sex.MALE,
            heightCm = 175.0,
            weightKg = 1.004,
            impedanceOhm = null,
        )
        val interpretation = ReferenceClassifier().classify(
            metric = BodyMetric.WEIGHT,
            value = 1.004,
            context = context,
        )

        val weight = factory.create(definition, 1.004, interpretation)

        assertEquals("1", weight.visualNumber)
        assertEquals("1 килограмм", weight.spokenValue)
    }

    @Test
    fun expandedCardExposesOneInformationNodeAndSeparateFortyEightDpButton() {
        val presentation = presentation()
        composeRule.setContent {
            ScaleSyncTheme {
                ExpandedMetricReference(presentation, onInfoClick = {})
            }
        }

        composeRule.onNodeWithContentDescription(presentation.accessibilityDescription).assertExists()
        composeRule.onNodeWithText(presentation.status, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Подробнее о показателе: BMI")
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(ReferenceRangeScaleTestTags.Scale).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.reference_zone_visual,
                presentation.zones.first().label,
                presentation.zones.first().range,
            ),
        ).assertDoesNotExist()
    }

    @Test
    fun expandedCardKeepsScaleFullWidthAndInfoButtonClearOfHeader() {
        val presentation = presentation()
        composeRule.setContent {
            ScaleSyncTheme {
                Box(Modifier.width(240.dp)) {
                    ExpandedMetricReference(presentation, onInfoClick = {})
                }
            }
        }

        val innerContainerBounds = composeRule.onNodeWithTag(
            ReferenceComponentTestTags.InnerContainer,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val scaleBounds = composeRule.onNodeWithTag(
            ReferenceRangeScaleTestTags.Scale,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val headerBounds = composeRule.onNodeWithTag(
            ReferenceComponentTestTags.Header,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val infoBounds = composeRule.onNodeWithTag(
            ReferenceComponentTestTags.InfoButton,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot

        assertEquals(innerContainerBounds.left, scaleBounds.left, 1f)
        assertEquals(innerContainerBounds.right, scaleBounds.right, 1f)
        assertEquals(innerContainerBounds.right, infoBounds.right, 1f)
        assertEquals(innerContainerBounds.top, infoBounds.top, 1f)
        assertTrue(infoBounds.width >= with(composeRule.density) { 48.dp.toPx() })
        assertTrue(infoBounds.height >= with(composeRule.density) { 48.dp.toPx() })
        assertTrue(headerBounds.right <= infoBounds.left)
    }

    @Test
    fun rangeScaleIsDecorativeAndAbsentWithoutRatedZones() {
        val presentation = presentation()
        composeRule.setContent {
            ScaleSyncTheme {
                Box(Modifier.width(320.dp)) {
                    ReferenceRangeScale(presentation)
                }
            }
        }

        composeRule.onNodeWithTag(ReferenceRangeScaleTestTags.Scale).assertExists()
        composeRule.onNodeWithText(presentation.zones.first().label).assertDoesNotExist()
        composeRule.onNodeWithText(requireNotNull(presentation.zones.first().upperBoundaryLabel))
            .assertDoesNotExist()

        composeRule.setContent {
            ScaleSyncTheme {
                ReferenceRangeScale(
                    presentation.copy(zones = emptyList(), scaleValue = null),
                )
            }
        }
        composeRule.onNodeWithTag(ReferenceRangeScaleTestTags.Scale).assertDoesNotExist()
    }

    @Test
    fun fiveZoneScaleRendersEverySegmentBoundaryLabelMarkerAndCurrentCategoryOnce() {
        val presentation = presentation()
        assertEquals(5, presentation.zones.size)
        val current = presentation.zones.single { it.isCurrent }
        composeRule.setContent {
            ScaleSyncTheme {
                Box(Modifier.width(320.dp)) {
                    ReferenceRangeScale(presentation)
                }
            }
        }

        presentation.zones.indices.forEach { index ->
            composeRule.onAllNodesWithTag(
                ReferenceRangeScaleTestTags.segment(index),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }
        presentation.zones.dropLast(1).indices.forEach { index ->
            composeRule.onAllNodesWithTag(
                ReferenceRangeScaleTestTags.boundary(index),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }
        presentation.zones.forEachIndexed { index, zone ->
            val tag = if (zone.isCurrent) {
                ReferenceRangeScaleTestTags.currentZone(zone.category.name)
            } else {
                ReferenceRangeScaleTestTags.zone(index)
            }
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
        }
        composeRule.onAllNodesWithTag(
            ReferenceRangeScaleTestTags.Marker,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onAllNodesWithTag(
            ReferenceRangeScaleTestTags.currentZone(current.category.name),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun boundariesStayOnOneRowAndCenteredAtSegmentJunctionsOnNarrowLargeTextScale() {
        val presentation = presentation()
        listOf(319 to 1.0f, 320 to 1.3f).forEach { (width, fontScale) ->
            setScale(presentation, width = width, fontScale = fontScale)

            composeRule.onAllNodesWithTag(
                ReferenceRangeScaleTestTags.Boundaries,
                useUnmergedTree = true,
            ).assertCountEquals(1)

            val scaleBounds = composeRule.onNodeWithTag(
                ReferenceRangeScaleTestTags.Scale,
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot
            val boundaryBounds = presentation.zones.dropLast(1).indices.map { index ->
                val nodes = composeRule.onAllNodesWithTag(
                    ReferenceRangeScaleTestTags.boundary(index),
                    useUnmergedTree = true,
                )
                nodes.assertCountEquals(1)
                nodes[0].fetchSemanticsNode().boundsInRoot
            }

            boundaryBounds.forEachIndexed { index, bounds ->
                val expectedCenterX = scaleBounds.left + scaleBounds.width * (index + 1) / presentation.zones.size
                assertEquals(expectedCenterX, bounds.center.x, 1f)
                assertEquals(boundaryBounds.first().center.y, bounds.center.y, 1f)
            }
        }
    }

    @Test
    fun compactCardExcludesBoundariesAndInfoActionFromItsSemantics() {
        val presentation = presentation()
        composeRule.setContent {
            ScaleSyncTheme {
                CompactMetricStatus(presentation)
            }
        }

        assertFalse(presentation.compactAccessibilityDescription.contains("Зоны:"))
        composeRule.onNodeWithContentDescription(presentation.compactAccessibilityDescription).assertExists()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.InfoButton).assertDoesNotExist()
    }

    @Test
    fun gridUsesTwoColumnsOnlyAtApprovedWidthAndFontScale() {
        val presentation = presentation().copy(zones = emptyList(), scaleValue = null)
        val groups = listOf(ReferenceGroupPresentation(ReferenceMetricGroup.MAIN, "Основное", listOf(presentation, presentation)))

        setGrid(groups, width = 360, fontScale = 1.29f)
        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridTwoColumns).assertExists()

        setGrid(groups, width = 359, fontScale = 1.0f)
        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridOneColumn).assertExists()

        setGrid(groups, width = 400, fontScale = 1.3f)
        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridOneColumn).assertExists()
    }

    @Test
    fun groupWithFourOrMoreZonesAlwaysUsesOneColumn() {
        val ratedPresentation = presentation()
        val groups = listOf(
            ReferenceGroupPresentation(
                ReferenceMetricGroup.MAIN,
                "Основное",
                listOf(ratedPresentation, ratedPresentation),
            ),
        )

        setGrid(groups, width = 400, fontScale = 1.0f)

        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridOneColumn).assertExists()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.GridTwoColumns).assertDoesNotExist()
        assertEquals(1, referenceGroupColumnCount(groups.single().metrics, 400.dp, 1.0f))
    }

    @Test
    fun groupedReferencesPassTheMetricSpecificModifierToTheInfoButton() {
        val presentation = presentation()
        composeRule.setContent {
            ScaleSyncTheme {
                GroupedMetricReferences(
                    groups = listOf(
                        ReferenceGroupPresentation(
                            ReferenceMetricGroup.MAIN,
                            "Основное",
                            listOf(presentation),
                        ),
                    ),
                    onInfoClick = {},
                    infoButtonModifier = { Modifier.testTag("metric-specific-info-button") },
                )
            }
        }

        composeRule.onNodeWithTag("metric-specific-info-button").assertExists()
    }

    @Test
    fun failedSourceLaunchKeepsDialogOpenAndShowsExactSnackbar() {
        val presentation = presentation()
        lateinit var snackbarHostState: SnackbarHostState
        composeRule.setContent {
            snackbarHostState = remember { SnackbarHostState() }
            Box {
                SnackbarHost(snackbarHostState)
                ScaleSyncTheme {
                    MetricHelpDialog(
                        presentation = presentation,
                        snackbarHostState = snackbarHostState,
                        onDismissRequest = {},
                        sourceLauncher = ReferenceSourceLauncher { false },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ReferenceComponentTestTags.SourceAction).performClick()
        composeRule.waitUntil { snackbarHostState.currentSnackbarData != null }
        composeRule.onNodeWithText("Не удалось открыть источник.").assertIsDisplayed()
        composeRule.onNodeWithTag(ReferenceComponentTestTags.HelpDialog).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.reference_help_value_summary,
                presentation.visualValue,
                presentation.status,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Набор норм: ScaleSync 1").assertIsDisplayed()
        composeRule.onNodeWithText("Xiaomi Legacy — реконструкция шкал Mi Fit").assertIsDisplayed()
    }

    private fun setGrid(groups: List<ReferenceGroupPresentation>, width: Int, fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ScaleSyncTheme {
                    Box(Modifier.width(width.dp)) {
                        GroupedMetricReferences(groups, onInfoClick = {})
                    }
                }
            }
        }
    }

    private fun setScale(presentation: ReferenceMetricPresentation, width: Int, fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ScaleSyncTheme {
                    Box(Modifier.width(width.dp)) {
                        ReferenceRangeScale(presentation)
                    }
                }
            }
        }
    }

    private fun presentation(): ReferenceMetricPresentation {
        val factory = ReferencePresentationFactory(composeRule.activity.resources, Locale.forLanguageTag("ru-RU"))
        val values = values()
        val readings = values.toReferenceReadings()
        val context = ReferenceContext(
            measurementDate = LocalDate.of(2026, 8, 30),
            birthDate = LocalDate.of(1996, 8, 30),
            sex = Sex.MALE,
            heightCm = 175.0,
            weightKg = values.weightKg,
            impedanceOhm = values.impedanceOhm,
        )
        return factory.createAll(readings, ReferenceClassifier().classifyAll(readings, context))
            .single { it.definition.metric == BodyMetric.BMI }
    }

    private fun values() = MeasurementUiValues(
        weightKg = 70.0,
        impedanceOhm = 523,
        bmi = 22.86,
        bodyFatPercent = 21.5,
        bodyFatMassKg = 15.05,
        waterPercent = 55.2,
        waterMassKg = 38.64,
        muscleMassKg = 52.0,
        skeletalMuscleMassKg = 28.0,
        boneMassKg = 2.8,
        proteinPercent = 18.0,
        proteinMassKg = 12.6,
        visceralFatLevel = 9.0,
        basalMetabolicRateKcal = 1_500.0,
        metabolicAge = 29,
        leanBodyMassKg = 54.95,
    )
}
