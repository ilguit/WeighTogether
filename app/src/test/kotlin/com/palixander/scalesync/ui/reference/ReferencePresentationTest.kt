package com.palixander.scalesync.ui.reference

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.core.BodyMetric
import com.palixander.scalesync.core.MetricInterpretation
import com.palixander.scalesync.core.ReferenceCategory
import com.palixander.scalesync.core.ReferenceVersion
import com.palixander.scalesync.core.ReferenceZone
import com.palixander.scalesync.core.ZoneBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReferencePresentationTest {
    @Test
    fun `factory presents skeletal muscle percent zones in kilograms`() {
        val interpretation = skeletalMuscleInterpretation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ReferencePresentationFactory(context.resources, Locale.forLanguageTag("ru"))
        val definition = ReferenceMetricCatalog.metrics.single {
            it.metric == BodyMetric.SKELETAL_MUSCLE_MASS
        }

        val presentation = factory.create(
            definition = definition,
            value = 26.95,
            interpretation = interpretation,
            weightKg = 70.0,
        )

        assertEquals("26,95 кг", presentation.visualValue)
        assertEquals("Норма", presentation.status)
        assertEquals(
            listOf("меньше 23,31", "23,31–<27,58", "27,58–<30,87", "от 30,87"),
            presentation.zones.map(ReferenceZonePresentation::range),
        )
        assertEquals(ReferenceCategory.NORMAL, presentation.zones.single { it.isCurrent }.category)
        assertEquals(1, presentation.zones.count { it.isCurrent })
    }

    @Test
    fun `factory hides skeletal muscle zones when weight cannot be converted safely`() {
        val interpretation = skeletalMuscleInterpretation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ReferencePresentationFactory(context.resources, Locale.forLanguageTag("ru"))
        val definition = ReferenceMetricCatalog.metrics.single {
            it.metric == BodyMetric.SKELETAL_MUSCLE_MASS
        }

        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { weightKg ->
            val presentation = factory.create(
                definition = definition,
                value = 26.95,
                interpretation = interpretation,
                weightKg = weightKg,
            )

            assertTrue("Expected no zones for weight $weightKg", presentation.zones.isEmpty())
        }
    }

    private fun skeletalMuscleInterpretation() = MetricInterpretation.Rated(
        metric = BodyMetric.SKELETAL_MUSCLE_MASS,
        version = ReferenceVersion.SCALE_SYNC_1,
        category = ReferenceCategory.NORMAL,
        zones = listOf(
            ReferenceZone(ReferenceCategory.BELOW_NORMAL, null, 33.3),
            ReferenceZone(ReferenceCategory.NORMAL, 33.3, 39.4),
            ReferenceZone(ReferenceCategory.GOOD, 39.4, 44.1),
            ReferenceZone(ReferenceCategory.VERY_GOOD, 44.1, null),
        ),
        basis = ZoneBasis.SKELETAL_MUSCLE_PERCENT,
        classifiedValue = 38.5,
    )
}
