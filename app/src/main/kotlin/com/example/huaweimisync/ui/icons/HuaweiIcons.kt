package com.example.huaweimisync.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Small, dependency-free icon set derived from the redesign template's 24 dp line symbols.
 * Use a null content description only when an adjacent label already names the action.
 */
object HuaweiIcons {
    val Back: ImageVector by lazy {
        outlineIcon("Back", paths = listOf("M15 18L9 12L15 6"), autoMirror = true)
    }

    val More: ImageVector by lazy {
        outlineIcon(
            "More",
            paths = emptyList(),
            filledPaths = listOf(
                "M5 11A1 1 0 1 1 5 13A1 1 0 1 1 5 11Z",
                "M12 11A1 1 0 1 1 12 13A1 1 0 1 1 12 11Z",
                "M19 11A1 1 0 1 1 19 13A1 1 0 1 1 19 11Z",
            ),
        )
    }

    val Scale: ImageVector by lazy {
        outlineIcon(
            "Scale",
            listOf(
                "M7 4H17A4 4 0 0 1 21 8V16A4 4 0 0 1 17 20H7A4 4 0 0 1 3 16V8A4 4 0 0 1 7 4Z",
                "M8 10A4.6 4.6 0 0 1 16 10",
                "M12 10L14 8",
            ),
        )
    }

    val Charts: ImageVector by lazy {
        outlineIcon(
            "Charts",
            listOf("M4 5V19H20", "M7 15L11 11L14 13L19 7"),
        )
    }

    val Settings: ImageVector by lazy {
        outlineIcon(
            "Settings",
            listOf(
                "M15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12Z",
                "M12.22 2H11.78A2 2 0 0 0 9.78 4V4.18A2 2 0 0 1 8.78 5.91L8.35 6.16A2 2 0 0 1 6.35 6.16L6.2 6.08A2 2 0 0 0 3.47 6.81L3.25 7.19A2 2 0 0 0 3.98 9.92L4.13 10.02A2 2 0 0 1 5.13 11.74V12.25A2 2 0 0 1 4.13 13.99L3.98 14.08A2 2 0 0 0 3.25 16.81L3.47 17.19A2 2 0 0 0 6.2 17.92L6.35 17.84A2 2 0 0 1 8.35 17.84L8.78 18.09A2 2 0 0 1 9.78 19.82V20A2 2 0 0 0 11.78 22H12.22A2 2 0 0 0 14.22 20V19.82A2 2 0 0 1 15.22 18.09L15.65 17.84A2 2 0 0 1 17.65 17.84L17.8 17.92A2 2 0 0 0 20.53 17.19L20.75 16.81A2 2 0 0 0 20.02 14.08L19.87 13.99A2 2 0 0 1 18.87 12.25V11.74A2 2 0 0 1 19.87 10L20.02 9.91A2 2 0 0 0 20.75 7.18L20.53 6.8A2 2 0 0 0 17.8 6.07L17.65 6.15A2 2 0 0 1 15.65 6.15L15.22 5.9A2 2 0 0 1 14.22 4.17V4A2 2 0 0 0 12.22 2Z",
            ),
        )
    }

    val Success: ImageVector by lazy {
        outlineIcon(
            "Success",
            listOf("M20 12A8 8 0 1 1 4 12A8 8 0 1 1 20 12Z", "M8.5 12L10.8 14.3L15.7 9.3"),
        )
    }

    val Pending: ImageVector by lazy {
        outlineIcon(
            "Pending",
            listOf("M20 12A8 8 0 1 1 4 12A8 8 0 1 1 20 12Z", "M12 7V12L15 14"),
        )
    }

    val Warning: ImageVector by lazy {
        outlineIcon(
            "Warning",
            listOf(
                "M10.4 4.6L3.2 17A2 2 0 0 0 4.9 20H19.1A2 2 0 0 0 20.8 17L13.6 4.6A1.8 1.8 0 0 0 10.4 4.6Z",
                "M12 9V13",
                "M12 17H12.01",
            ),
        )
    }

    val LocalDevice: ImageVector by lazy {
        outlineIcon(
            "LocalDevice",
            listOf("M9 2H15A3 3 0 0 1 18 5V19A3 3 0 0 1 15 22H9A3 3 0 0 1 6 19V5A3 3 0 0 1 9 2Z", "M10 18H14"),
        )
    }

    val ChevronRight: ImageVector by lazy {
        outlineIcon("ChevronRight", listOf("M9 6L15 12L9 18"), autoMirror = true)
    }

    val ChevronDown: ImageVector by lazy {
        outlineIcon("ChevronDown", listOf("M6 9L12 15L18 9"))
    }

    val Calendar: ImageVector by lazy {
        outlineIcon(
            "Calendar",
            listOf(
                "M6 5H18A3 3 0 0 1 21 8V18A3 3 0 0 1 18 21H6A3 3 0 0 1 3 18V8A3 3 0 0 1 6 5Z",
                "M8 3V7",
                "M16 3V7",
                "M3 10H21",
            ),
        )
    }

    val Tune: ImageVector by lazy {
        outlineIcon(
            "Tune",
            listOf("M4 7H14", "M18 7H20", "M4 17H6", "M10 17H20", "M14 4V10", "M7 14V20"),
        )
    }

    val Profile: ImageVector by lazy {
        outlineIcon(
            "Profile",
            listOf("M16 8A4 4 0 1 1 8 8A4 4 0 1 1 16 8Z", "M4 21A8 8 0 0 1 20 21"),
        )
    }

    val Link: ImageVector by lazy {
        outlineIcon(
            "Link",
            listOf(
                "M10 13A5 5 0 0 0 17.1 13L19.1 11A5 5 0 0 0 12 3.9L10.9 5",
                "M14 11A5 5 0 0 0 6.9 11L4.9 13A5 5 0 0 0 12 20.1L13.1 19",
            ),
        )
    }

    val Bluetooth: ImageVector by lazy {
        outlineIcon("Bluetooth", listOf("M7 7L17 17L12 21V3L17 7L7 17"))
    }

    val Lab: ImageVector by lazy {
        outlineIcon(
            "Lab",
            listOf(
                "M9 3H15",
                "M10 3V9L5 18A2 2 0 0 0 6.7 21H17.3A2 2 0 0 0 19 18L14 9V3",
                "M8 15H16",
            ),
        )
    }

    val Close: ImageVector by lazy {
        outlineIcon("Close", listOf("M6 6L18 18", "M18 6L6 18"))
    }

    val Health: ImageVector by lazy {
        outlineIcon(
            "Health",
            listOf("M20.8 4.6A5.5 5.5 0 0 0 13 4.6L12 5.7L10.9 4.6A5.5 5.5 0 0 0 3.1 12.4L4.2 13.5L12 21L19.8 13.5L20.9 12.4A5.5 5.5 0 0 0 20.8 4.6Z"),
        )
    }

    val Edit: ImageVector by lazy {
        outlineIcon(
            "Edit",
            listOf("M12 20H5A1 1 0 0 1 4 19V12", "M16.5 3.5A2.1 2.1 0 0 1 19.5 6.5L10 16L6 17L7 13Z"),
        )
    }

    val Delete: ImageVector by lazy {
        outlineIcon(
            "Delete",
            listOf("M3 6H21", "M8 6V4H16V6", "M19 6L18 21H6L5 6", "M10 10V17", "M14 10V17"),
        )
    }

    val Refresh: ImageVector by lazy {
        outlineIcon(
            "Refresh",
            listOf("M20 11A8 8 0 0 0 6.1 6L4 8", "M4 4V8H8", "M4 13A8 8 0 0 0 17.9 18L20 16", "M20 20V16H16"),
        )
    }

    private fun outlineIcon(
        name: String,
        paths: List<String>,
        filledPaths: List<String> = emptyList(),
        autoMirror: Boolean = false,
    ): ImageVector = ImageVector.Builder(
        name = "Huawei.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        paths.forEach { pathData ->
            addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        filledPaths.forEach { pathData ->
            addPath(
                pathData = addPathNodes(pathData),
                fill = SolidColor(Color.Black),
                stroke = null,
            )
        }
    }.build()
}
