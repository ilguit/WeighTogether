package com.palixander.scalesync.ui.reference

import com.palixander.scalesync.core.BodyMetric
import com.palixander.scalesync.core.MetricInterpretation
import com.palixander.scalesync.core.ReferenceCategory
import com.palixander.scalesync.core.ReferenceVersion
import com.palixander.scalesync.core.ReferenceZone
import com.palixander.scalesync.core.ZoneBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePresentationTest {
    @Test
    fun `skeletal muscle percent zones are presented in kilograms`() {
        val interpretation = skeletalMuscleInterpretation()

        val zones = presentationZones(interpretation, weightKg = 70.0)

        assertEquals(listOf(null, 23.31, 27.58, 30.87), zones.map(ReferenceZone::lowerInclusive))
        assertEquals(listOf(23.31, 27.58, 30.87, null), zones.map(ReferenceZone::upperExclusive))
        assertEquals(ReferenceCategory.NORMAL, interpretation.category)
        assertEquals(38.5, interpretation.classifiedValue, 0.0)
        assertTrue(26.95 in requireNotNull(zones[1].lowerInclusive)..<requireNotNull(zones[1].upperExclusive))
    }

    @Test
    fun `skeletal muscle zones are hidden when weight cannot be converted safely`() {
        val interpretation = skeletalMuscleInterpretation()

        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { weightKg ->
            assertTrue(presentationZones(interpretation, weightKg).isEmpty())
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
