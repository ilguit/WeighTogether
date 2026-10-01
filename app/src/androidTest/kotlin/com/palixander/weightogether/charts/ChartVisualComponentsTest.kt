package com.palixander.weightogether.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class ChartVisualComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun regularSeriesIsLinearWithNoPersistentPointsOrAreaFill() {
        val line = chartLine(Color.Red, pointCount = 2)

        assertSame(LineCartesianLayer.Interpolator.Sharp, line.interpolator)
        assertNull(line.pointProvider)
        assertNull(line.areaFillForTest())
    }

    @Test
    fun singleValueSeriesIsLinearWithVisiblePointAndNoAreaFill() {
        val line = chartLine(Color.Red, pointCount = 1)

        assertSame(LineCartesianLayer.Interpolator.Sharp, line.interpolator)
        assertNotNull(line.pointProvider)
        assertNull(line.areaFillForTest())
    }

    @Test
    fun singleValueCardReachesIdleWithoutCreatingChartHost() {
        val date = LocalDate.of(2026, 8, 12)
        val point = ChartPoint(
            measuredAtEpochSecond = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC),
            value = 70.5,
        )

        composeRule.setContent {
            ScaleSyncTheme {
                MetricChartCard(
                    series = ChartSeries(ChartMetricOption("weight", "Вес", "кг", 1), listOf(point)),
                    startDate = LocalDate.of(2026, 8, 9),
                    endDateInclusive = LocalDate.of(2026, 8, 15),
                    zoneId = ZoneOffset.UTC,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MetricChartTestTags.InsufficientInterval).assertIsDisplayed()
        composeRule.onNodeWithTag(MetricChartTestTags.ChartHost).assertDoesNotExist()
    }

    @Test
    fun distinctTimestampValuesCreateChartHost() {
        val metric = ChartMetricOption("weight", "Вес", "кг", 1)
        composeRule.setContent {
            ScaleSyncTheme {
                MetricChartCard(
                    ChartSeries(metric, listOf(ChartPoint(1L, 70.0), ChartPoint(2L, 71.0))),
                    LocalDate.of(2026, 8, 9),
                    LocalDate.of(2026, 8, 15),
                    ZoneOffset.UTC,
                )
            }
        }
        composeRule.onNodeWithTag(MetricChartTestTags.ChartHost).assertIsDisplayed()
    }

    private fun LineCartesianLayer.Line.areaFillForTest(): Any? =
        LineCartesianLayer.Line::class.java
            .getDeclaredMethod("getAreaFill")
            .apply { isAccessible = true }
            .invoke(this)

}
