package com.palixander.scalesync.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.domain.MeasurementOrigin
import com.palixander.scalesync.measurements.MeasurementHistoryCard
import com.palixander.scalesync.measurements.MeasurementsCallbacks
import com.palixander.scalesync.ui.components.HuaweiSurface
import com.palixander.scalesync.ui.profiles.PetHistoryMeasurementDetails
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1000dp")
class HistoryIndicatorsTest : HistoryIndicatorsTestCases()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryIndicatorsScreenshotTest {
    @get:org.junit.Rule val composeRule = androidx.compose.ui.test.junit4.v2.createComposeRule()
    @Test
    fun captureSyntheticHistoryExamples() {
        lateinit var view: android.view.View
        composeRule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            ScaleSyncTheme {
                Column(Modifier.width(360.dp)) {
                    for (origin in listOf(MeasurementOrigin.SCALE, MeasurementOrigin.MANUAL)) {
                        MeasurementHistoryCard(human(origin, false), false, {}, {}, MeasurementsCallbacks.None, { _, _ -> }, mutableMapOf())
                    }
                    MeasurementHistoryCard(human(MeasurementOrigin.MANUAL, true), false, {}, {}, MeasurementsCallbacks.None, { _, _ -> }, mutableMapOf())
                    MeasurementHistoryCard(human(MeasurementOrigin.MANUAL, true), true, {}, {}, MeasurementsCallbacks.None, { _, _ -> }, mutableMapOf())
                    for (origin in listOf(MeasurementOrigin.SCALE, MeasurementOrigin.MANUAL)) {
                        HuaweiSurface {
                            PetHistoryMeasurementDetails(pet(origin, false), true, {}, {})
                        }
                    }
                    HuaweiSurface {
                        PetHistoryMeasurementDetails(pet(MeasurementOrigin.MANUAL, true), true, {}, {})
                    }
                }
            }
        }
        val directory = File("/tmp/scalesync-99-bottom-evidence").apply { mkdirs() }
        File(directory, "history-indicators-360.png").outputStream().use {
            composeRule.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
