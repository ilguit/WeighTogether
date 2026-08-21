package com.example.huaweimisync.charts

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.MarkerCornerBasedShape
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/**
 * Creates the common line used by both single- and multi-series charts.
 *
 * Points and an area fill are deliberately absent: a marker supplies the interactive point only
 * while the user is inspecting the chart.
 */
internal fun smoothChartLine(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        areaFill = null,
        pointProvider = null,
        interpolator = LineCartesianLayer.Interpolator.cubic(),
    )

@Composable
internal fun rememberSmoothChartLine(color: Color): LineCartesianLayer.Line =
    remember(color) { smoothChartLine(color) }

@Composable
internal fun rememberSmoothLineLayer(
    lines: List<LineCartesianLayer.Line>,
    rangeProvider: CartesianLayerRangeProvider,
): LineCartesianLayer = rememberLineCartesianLayer(
    lineProvider = LineCartesianLayer.LineProvider.series(lines),
    rangeProvider = rangeProvider,
)

@Composable
internal fun rememberChartStartAxis(
    valueFormatter: CartesianValueFormatter,
): VerticalAxis<Axis.Position.Vertical.Start> =
    VerticalAxis.rememberStart(valueFormatter = valueFormatter)

@Composable
internal fun rememberChartBottomAxis(
    valueFormatter: CartesianValueFormatter,
): HorizontalAxis<Axis.Position.Horizontal.Bottom> = HorizontalAxis.rememberBottom(
    guideline = null,
    labelRotationDegrees = 35f,
    valueFormatter = valueFormatter,
)

@Composable
internal fun rememberChartMarker(
    valueFormatter: DefaultCartesianMarker.ValueFormatter,
    lineCount: Int = 2,
): DefaultCartesianMarker {
    val background = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.inverseSurface),
        shape = MarkerCornerBasedShape(RoundedCornerShape(12.dp)),
    )
    val label = rememberTextComponent(
        style = TextStyle(
            color = MaterialTheme.colorScheme.inverseOnSurface,
            textAlign = TextAlign.Center,
        ),
        lineCount = lineCount,
        padding = Insets(10.dp, 7.dp),
        background = background,
        minWidth = TextComponent.MinWidth.text("00.00.0000 00:00"),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        indicator = { color ->
            ShapeComponent(
                fill = Fill(Color.White),
                shape = CircleShape,
                strokeFill = Fill(color),
                strokeThickness = 2.dp,
            )
        },
        indicatorSize = 14.dp,
    )
}
