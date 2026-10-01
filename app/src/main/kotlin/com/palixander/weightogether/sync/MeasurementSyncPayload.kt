package com.palixander.weightogether.sync

import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.MeasurementValues

/** The exact subset of one measurement that still needs to be written to a destination. */
data class MeasurementSyncPayload(
    val measurement: MeasurementEntity,
    val includesWeight: Boolean,
) {
    val composition: MeasurementValues?
        get() = measurement.fullValues

    val isEmpty: Boolean
        get() = !includesWeight && composition == null
}
