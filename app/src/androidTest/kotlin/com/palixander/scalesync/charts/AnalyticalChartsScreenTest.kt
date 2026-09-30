package com.palixander.scalesync.charts

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.R
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state only. Never reads a measurement repository, database, or device data. */
@OptIn(ExperimentalTestApi::class)
class AnalyticalChartsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = object : AnalyticalChartSettingsStore {
        val values = mutableMapOf<AccountId, List<AnalyticalChartSettings>>()
        override fun read(account: AccountId) = values[account].orEmpty()
        override fun write(account: AccountId, settings: List<AnalyticalChartSettings>) { values[account] = settings }
    }
    private val controller = AnalyticalChartController(store, scope)
    private val account = AccountId("synthetic-ui")
    private val rows = syntheticRows()
    private val callbacks = AnalyticalChartCallbacks(controller, { controller.autoSelect(account, rows, ZoneOffset.UTC) }, {}, {})
    @After fun cleanup() { scope.cancel() }

    @Test fun addCancelInvalidTimeAutoPreviewSaveAndUndo() {
        rule.runOnIdle { controller.selectAccount(account) }
        rule.setContent { ScaleSyncTheme { Screen() } }
        rule.onNodeWithTag("analytical-add").performClick()
        rule.onNodeWithTag("analytical-add-MORNING").performClick()
        rule.onNodeWithTag("analytical-start").performTextReplacement("13:00")
        rule.onNodeWithTag("analytical-save").assertIsNotEnabled()
        rule.onNodeWithTag("analytical-cancel").performClick()
        rule.runOnIdle { assertTrue(controller.state.value.settings.isEmpty()) }
        rule.onNodeWithTag("analytical-add").performClick()
        rule.onNodeWithTag("analytical-add-MORNING").performClick()
        rule.onNodeWithTag("analytical-auto").performScrollTo().performClick()
        rule.waitUntil(10_000) { controller.state.value.draft?.status == MorningCalculationStatus.SUCCESS }
        rule.onNodeWithTag("analytical-preview").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertTrue(controller.state.value.settings.isEmpty()) }
        rule.onNodeWithTag("analytical-save").performClick()
        rule.runOnIdle { assertEquals(MorningFilterMode.AUTOMATIC, controller.state.value.settings.single().morningMode) }
        scroll("analytical-remove-MORNING").performClick()
        val undo = rule.activity.getString(R.string.action_undo)
        rule.onNodeWithText(undo).performClick()
        rule.runOnIdle { assertEquals(MorningFilterMode.AUTOMATIC, controller.state.value.settings.single().morningMode) }
    }

    @Test fun hourlyLegendIncludesZeroGroupsAndSelectionWorksWithoutColor() {
        rule.setContent { ScaleSyncTheme { Column(Modifier.verticalScrollForTest()) { HourlyChart(List(24) { if (it == 8) 1 else 0 }) } } }
        rule.onNodeWithTag("analytical-hour-8").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("analytical-hour-23").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithTag("analytical-hour-0").performScrollTo().assertIsDisplayed()
    }

    @Test fun accountSwitchClosesUnsavedEditor() {
        rule.runOnIdle { controller.selectAccount(account); controller.edit(AnalyticalChartType.MORNING) }
        rule.setContent { ScaleSyncTheme { Screen() } }
        rule.onNodeWithTag("analytical-editor").assertIsDisplayed()
        rule.runOnIdle { controller.selectAccount(AccountId("other-synthetic")) }
        rule.onNodeWithTag("analytical-editor").assertDoesNotExist()
        rule.runOnIdle { assertTrue(store.read(account).isEmpty()) }
    }

    @Test fun loadingAndErrorKeepRetryAndConfigureReachable() {
        val loading = mutableStateOf(true)
        var retries = 0
        val settings = AnalyticalChartSettings(AnalyticalChartType.HOURLY)
        rule.setContent { ScaleSyncTheme {
            Column { AnalyticalChartCard(AnalyticalCardUiState(settings), loading.value, !loading.value,
                callbacks.copy(retry = { retries++ }), ZoneOffset.UTC) }
        } }
        rule.onNodeWithTag("analytical-loading-HOURLY").assertIsDisplayed()
        rule.runOnIdle { loading.value = false }
        rule.onNodeWithTag("analytical-retry-HOURLY").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithTag("analytical-edit-HOURLY").assertIsDisplayed()
    }

    @Test fun captureSyntheticWidthFontAndSystemThemeMatrix() {
        data class Scenario(val width: Int, val font: Float, val dark: Boolean) {
            val name get() = "w$width-f${(font * 100).toInt()}-${if (dark) "dark-system" else "light-system"}"
        }
        val scenarios = listOf(320, 412).flatMap { w -> listOf(1f, 2f).flatMap { f -> listOf(false, true).map { Scenario(w, f, it) } } }
        val scenario = mutableStateOf(scenarios.first())
        rule.runOnIdle {
            controller.selectAccount(account)
            AnalyticalChartType.entries.forEach { controller.edit(it); controller.save() }
        }
        rule.setContent {
            key(scenario.value) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(scenario.value.width.dp, 900.dp))) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scenario.value.font)) {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (scenario.value.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(LocalConfiguration provides configuration) {
                            ScaleSyncTheme {
                                // The existing theme deliberately remains light under both system settings.
                                check(MaterialTheme.colorScheme.background.red > 0.8f)
                                Screen()
                            }
                        }
                    }
                }
            }
        }
        scenarios.forEach { value ->
            rule.runOnIdle { scenario.value = value }
            rule.waitForIdle()
            capture("${value.name}-morning")
            scroll("analytical-edit-MORNING").performClick()
            rule.onNodeWithTag("analytical-save").assertIsDisplayed()
            rule.onNodeWithTag("analytical-cancel").assertIsDisplayed()
            assertTarget("analytical-save")
            capture("${value.name}-settings")
            rule.onNodeWithTag("analytical-auto").performScrollTo().performClick()
            rule.waitUntil(10_000) { controller.state.value.draft?.status == MorningCalculationStatus.SUCCESS }
            rule.onNodeWithTag("analytical-preview").performScrollTo()
            capture("${value.name}-preview")
            rule.onNodeWithTag("analytical-cancel").performClick()
            scroll("analytical-edit-DAILY_MINIMUM")
            capture("${value.name}-minimum")
            scroll("analytical-pie")
            capture("${value.name}-hourly")
            scroll("analytical-hour-23").performClick().assertIsSelected()
            assertTarget("analytical-hour-23")
            capture("${value.name}-legend")
        }
    }

    @Composable private fun Screen() {
        val state by controller.state.collectAsState()
        val start = LocalDate.of(2026, 1, 1)
        ChartsScreen(ChartsUiState(startDate = start, endDateInclusive = start.plusDays(10), currentDate = start.plusDays(10),
            metricOptions = emptyList(), selectedMetricKeys = emptySet(), series = emptyList(), analytical = state,
            analyticalCards = state.settings.map { buildAnalyticalCard(it, rows, start, start.plusDays(10), ZoneOffset.UTC) }),
            ChartsCallbacks(analytical = callbacks, openRangeFilter = {}, openMetricFilter = {}, dismissFilterSheet = {},
                selectRangePreset = {}, dismissCustomDatePicker = {}, setDateRange = { _, _ -> }, setMetricSelected = { _, _ -> },
                selectAll = {}, clearSelection = {}, doneSelectingMetrics = {}), showAccountSelector = false)
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        rule.onNodeWithTag("charts-list").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun assertTarget(tag: String) {
        val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("Target $tag: $bounds", bounds.width >= 47.5.dp && bounds.height >= 47.5.dp)
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "issue80").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

@Composable private fun Modifier.verticalScrollForTest(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

private fun syntheticRows() = List(10) { i ->
    MeasurementEntity(id = "synthetic-$i", measuredAtEpochSecond = Instant.parse("2026-01-01T06:00:00Z").epochSecond + i * 86400L,
        weightKg = if (i == 4) 73.0 else 70.0, rawWeight = 0, deviceAddress = "", rawPayloadHex = "", impedanceOhm = null, bmi = null,
        bodyFatPercent = null, bodyFatMassKg = 14.0, waterPercent = null, waterMassKg = 40.0, muscleMassKg = 50.0,
        skeletalMuscleMassKg = 30.0, boneMassKg = 3.0, proteinPercent = null, proteinMassKg = 10.0,
        visceralFatLevel = null, basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = 56.0, algorithmVersion = null)
}
