package com.example.huaweimisync.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChartVisualComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun regularSeriesIsCubicWithNoPersistentPointsOrAreaFill() {
        val line = smoothChartLine(Color.Red, pointCount = 2)

        assertEquals("CubicInterpolator", line.interpolator.javaClass.simpleName)
        assertNull(line.pointProvider)
        assertNull(line.areaFillForTest())
    }

    @Test
    fun singleValueSeriesIsCubicWithVisiblePointAndNoAreaFill() {
        val line = smoothChartLine(Color.Red, pointCount = 1)

        assertEquals("CubicInterpolator", line.interpolator.javaClass.simpleName)
        assertNotNull(line.pointProvider)
        assertNull(line.areaFillForTest())
    }

    @Test
    fun singleValueChartShowsMarkerForRealLongPressGesture() {
        var markerShownCount = 0
        val markerVisibilityListener = object : CartesianMarkerVisibilityListener {
            override fun onShown(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>,
            ) {
                markerShownCount++
            }
        }
        val date = LocalDate.of(2026, 8, 12)
        val point = ChartPoint(
            measuredAtEpochSecond = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC),
            value = 70.5,
        )

        composeRule.setContent {
            HuaweiMiSyncTheme {
                MetricLineChart(
                    metric = ChartMetricOption("weight", "Вес", "кг", 1),
                    points = listOf(point),
                    startDate = LocalDate.of(2026, 8, 9),
                    endDateInclusive = LocalDate.of(2026, 8, 15),
                    zoneId = ZoneOffset.UTC,
                    contentDescription = SinglePointChartDescription,
                    markerVisibilityListener = markerVisibilityListener,
                )
            }
        }

        val chart = composeRule.onNodeWithContentDescription(SinglePointChartDescription)
            .assertIsDisplayed()
        chart.performTouchInput { longClick(center) }

        composeRule.runOnIdle {
            assertTrue("The single point must remain a marker target.", markerShownCount > 0)
        }
        chart.assertIsDisplayed()
    }

    private fun LineCartesianLayer.Line.areaFillForTest(): Any? =
        LineCartesianLayer.Line::class.java
            .getDeclaredMethod("getAreaFill")
            .apply { isAccessible = true }
            .invoke(this)

    private companion object {
        const val SinglePointChartDescription = "График с одним значением"
    }
}
