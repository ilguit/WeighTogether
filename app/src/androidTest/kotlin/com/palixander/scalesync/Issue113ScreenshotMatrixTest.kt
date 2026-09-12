package com.palixander.scalesync

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.measurements.DefaultHomeKgChartSeriesKeys
import com.palixander.scalesync.measurements.HomeKgChartPeriod
import com.palixander.scalesync.measurements.HomeKgChartPoint
import com.palixander.scalesync.measurements.HomeKgChartSeries
import com.palixander.scalesync.measurements.HomeKgChartSeriesCatalog
import com.palixander.scalesync.measurements.HomeKgChartUiState
import com.palixander.scalesync.measurements.MeasurementSyncPresentation
import com.palixander.scalesync.measurements.MeasurementSyncPresentationState
import com.palixander.scalesync.measurements.MeasurementUiItem
import com.palixander.scalesync.measurements.MeasurementUiType
import com.palixander.scalesync.measurements.MeasurementUiValues
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.measurements.MeasurementsScreen
import com.palixander.scalesync.measurements.MeasurementsUiState
import com.palixander.scalesync.measurements.buildMeasurementSummary
import com.palixander.scalesync.ui.profiles.HomePetShortcutsTestTags
import com.palixander.scalesync.ui.profiles.ProfilePresentation
import com.palixander.scalesync.ui.profiles.ProfileSelectionUiState
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import com.palixander.scalesync.ui.routing.ResolverQueueState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** State-only production UI: no repository, database, BLE or personal measurement access. */
@OptIn(ExperimentalTestApi::class)
class Issue113ScreenshotMatrixTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private data class Scenario(val width: Int, val font: Float, val longNames: Boolean, val petCount: Int) {
        val name get() = "w${width}-f${font.toInt()}-${if (longNames) "long" else "normal"}-pets$petCount"
    }

    @Test
    fun captureProductionMatrixAndCheckTargets() {
        val scenarios = listOf(320, 412).flatMap { width ->
            listOf(1f, 2f).flatMap { font ->
                listOf(false, true).flatMap { long -> listOf(0, 6).map { Scenario(width, font, long, it) } }
            }
        } + Scenario(840, 1f, true, 6)
        val current = mutableStateOf(scenarios.first())
        composeRule.setContent {
            key(current.value) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(current.value.width.dp, 900.dp))) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(current.value.font)) {
                        ScaleSyncTheme { ScenarioContent(current.value) }
                    }
                }
            }
        }
        scenarios.forEach { scenario ->
            composeRule.runOnIdle { current.value = scenario }
            composeRule.waitForIdle()
            assertTargets(listOf(SummaryTopBarTestTags.Profile, MainScreenTestTags.ExternalSyncAction,
                MainScreenTestTags.PetMeasurementAction, MainScreenTestTags.PendingQueueAction))
            composeRule.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
            val list = composeRule.onNodeWithTag("measurement-summary-list").getUnclippedBoundsInRoot()
            assertTrue("680 dp content cap", list.right - list.left <= 680.dp)
            capture("${scenario.name}-collapsed")
            composeRule.onNodeWithTag(HomePetShortcutsTestTags.Toggle).performScrollTo().performClick()
            capture("${scenario.name}-pets-expanded")
            composeRule.onNodeWithTag(HomePetShortcutsTestTags.Toggle).performScrollTo().performClick()
            composeRule.onNodeWithTag("summary-history").performScrollTo()
            assertTargets(listOf("summary-history", "summary-sync-status", "summary-more-actions"))
            composeRule.onNodeWithTag("summary-expand-metrics").performScrollTo().performClick()
            capture("${scenario.name}-card-expanded")
            composeRule.onNodeWithTag("summary-expand-metrics").performScrollTo().performClick()
            val seriesToggle = composeRule.onNodeWithTag("home-kg-series-toggle").performScrollTo()
            // Lazy content may restore expansion when scenarios reuse the same list item key.
            if (seriesToggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Развёрнуто") {
                seriesToggle.performClick()
            }
            seriesToggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто"))
            capture("${scenario.name}-chart-collapsed")
            seriesToggle.performScrollTo().performClick()
            seriesToggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
            HomeKgChartSeriesCatalog.forEach { metric ->
                val node = composeRule.onNodeWithTag("home-kg-legend-${metric.key}").performScrollTo()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("Series target ${metric.key}", bounds.right - bounds.left >= 47.5.dp && bounds.bottom - bounds.top >= 47.5.dp)
            }
            capture("${scenario.name}-series-expanded")
        }
    }

    // ForcedSize rounds 48 dp to integral pixels; allow at most half a dp for that conversion.
    private fun assertTargets(tags: List<String>) {
        val bounds = tags.map { tag -> composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().also {
            assertTrue("48 dp target $tag: $it", it.right - it.left >= 47.5.dp && it.bottom - it.top >= 47.5.dp)
        } }
        bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b ->
            assertTrue("Targets overlap: $a / $b", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        } }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "issue114").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Composable
    private fun ScenarioContent(scenario: Scenario) {
        val now = Instant.parse("2026-09-10T10:00:00Z")
        val name = if (scenario.longNames) "Екатерина Александровна с очень длинным именем" else "Екатерина"
        val human = Account(AccountId("synthetic"), name, profile = AccountProfile.IncompleteRecovery(), createdAt = now, updatedAt = now)
        val pets = List(scenario.petCount) { index -> PetWithLatestWeight(
            Pet(PetId("synthetic-$index"), if (scenario.longNames) "Питомец с очень длинным именем $index" else "Барсик $index",
                species = PetSpecies.CAT, createdAt = now, updatedAt = now), null,
        ) }
        val profiles = listOf(ProfilePresentation.Human(human)) + pets.map { ProfilePresentation.Pet(it) }
        val item = MeasurementUiItem(
            id = "synthetic", measuredAtEpochSecond = now.epochSecond,
            values = MeasurementUiValues(72.4, 512, 22.9, 18.7, 13.5, 57.3, 41.5, 54.1, 29.8, 3.2, 18.2, 13.2, 7.0, 1568.0, 31, 58.9),
            sync = MeasurementSyncPresentation(MeasurementSyncPresentationState.ERROR, emptyList(), false),
            type = MeasurementUiType.FULL,
        )
        val chart = HomeKgChartUiState(
            period = HomeKgChartPeriod(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 10), now.minusSeconds(13 * 86400).epochSecond, now.plusSeconds(86400).epochSecond),
            series = HomeKgChartSeriesCatalog.mapIndexed { index, metric -> HomeKgChartSeries(
                metric.key, metric.label, metric.unit, metric.decimalPlaces, metric.color,
                listOf(HomeKgChartPoint("synthetic", now.epochSecond, 72.4 - index * 8)),
            ) }, activeSeriesKeys = DefaultHomeKgChartSeriesKeys,
        )
        val measurementState = MeasurementsUiState(isLoading = false, measurements = listOf(item), summary = buildMeasurementSummary(listOf(item)), homeKgChart = chart)
        ScaleSyncScaffold(
            state = MainUiState(profilesLoaded = true, accounts = listOf(human), pets = pets,
                resolverQueue = ResolverQueueState(pending = List(123) { index -> PendingMeasurement(
                    PendingMeasurementId("synthetic-$index"), "00:00:00:00:00:00", now, 72.4, 512, true, true, byteArrayOf(), "synthetic-$index", now.plusSeconds(index.toLong()),
                ) })),
            profileSelection = ProfileSelectionUiState(profiles, profiles.first().key, human.id),
            currentSection = AppSection.MEASUREMENTS, measurementsDestination = MeasurementsDestination.SUMMARY,
            measurementsCallbacks = MeasurementsCallbacks.None, snackbarHostState = remember { SnackbarHostState() },
            onSectionSelected = {}, onCloseProfile = {}, onSaveProfile = {}, onProfileHeightChanged = {}, onProfileBirthDateChanged = {}, onProfileSexChanged = {},
            settingsCallbacks = SettingsCallbacks(onOpenProfile = {}, onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {}, onManualScan = {}, onReliabilityMode = {}, openBatterySettings = {}, openApplicationSettings = {}),
            measurementsContent = { padding, header -> MeasurementsScreen(measurementState, MeasurementsCallbacks.None,
                modifier = Modifier.fillMaxSize().padding(padding), showAccountSelector = false, summaryHeader = header) }, chartsContent = {},
        )
    }
}
