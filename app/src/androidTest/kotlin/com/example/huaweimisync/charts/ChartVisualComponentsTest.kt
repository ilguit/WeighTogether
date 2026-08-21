package com.example.huaweimisync.charts

import androidx.compose.ui.graphics.Color
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartVisualComponentsTest {
    @Test
    fun sharedLineIsCubicWithNoPersistentPointsOrAreaFill() {
        val line = smoothChartLine(Color.Red)

        assertEquals("CubicInterpolator", line.interpolator.javaClass.simpleName)
        assertNull(line.pointProvider)
        assertNull(
            LineCartesianLayer.Line::class.java
                .getDeclaredMethod("getAreaFill")
                .apply { isAccessible = true }
                .invoke(line),
        )
    }
}
